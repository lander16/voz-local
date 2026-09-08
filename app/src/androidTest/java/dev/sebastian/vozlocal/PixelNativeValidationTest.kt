package dev.sebastian.vozlocal

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.WhisperContext
import dev.sebastian.vozlocal.benchmark.BenchmarkRunner
import dev.sebastian.vozlocal.benchmark.TranscriptionBenchmarkConfig
import dev.sebastian.vozlocal.data.repository.ModelDownloader
import dev.sebastian.vozlocal.data.repository.ModelUrls
import dev.sebastian.vozlocal.whisper.CpuBackendManager
import dev.sebastian.vozlocal.whisper.CpuBackendMode
import dev.sebastian.vozlocal.whisper.PromptMode
import dev.sebastian.vozlocal.whisper.WhisperParams
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit fixture-driven tests, never run against the production package. */
@RunWith(AndroidJUnit4::class)
class PixelNativeValidationTest {
    @Test fun calibrationPersistsAcrossReload() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(target.packageName.endsWith(".validation"))
        val repository = (target.applicationContext as VozLocalApp).repository
        repository.ensureModelCatalogInitialized()
        // Exercise the full 18-pass persistence flow with Tiny to bound battery
        // cost; Small's native performance/cancellation is tested separately.
        val fixtures = File(target.filesDir, "validation")
        val calibrationModel = "whisper_tiny"
        File(fixtures, "tiny-q8_0.bin").copyTo(ModelUrls.getModelFile(target, calibrationModel), overwrite = true)
        check(ModelDownloader(target).verifiedModelFile(calibrationModel) != null)
        dev.sebastian.vozlocal.data.local.AppDatabase.getDatabase(target).modelDao()
            .setDownloadState(calibrationModel, downloaded = true, downloading = false, progress = 1f)
        repository.saveLanguage("en")
        repository.saveUseVad(false)
        repository.savePromptMode(PromptMode.OFF)
        repository.selectModel(calibrationModel)
        val buffer = ByteBuffer.wrap(File(fixtures, "speech.f32").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val mode = CpuBackendManager.diagnostics.value.effectiveMode.name.lowercase()
        val report = File(fixtures, "calibration-$mode.txt")
        val started = System.nanoTime()
        val winner = repository.calibrateCpu(samples) { done, total ->
            report.writeText("completed=$done/$total\n")
        }
        val saved = com.whispercpp.whisper.WhisperCpuConfig.profileManager!!.getAllProfiles()
        assertTrue(saved.any { it.optimalThreads == winner })
        dev.sebastian.vozlocal.whisper.CpuCalibration.initialize(target)
        assertEquals(saved.toSet(), com.whispercpp.whisper.WhisperCpuConfig.profileManager!!.getAllProfiles().toSet())
        report.appendText("model=$calibrationModel\nwinner=$winner\nelapsed_ms=${(System.nanoTime() - started) / 1_000_000}\nreload_verified=true\n")
        repository.whisperEngine.release()
    }

    @Test fun configureBackendForNextProcess() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(target.packageName.endsWith(".validation"))
        val requested = InstrumentationRegistry.getArguments().getString("backend")
        assumeTrue(requested != null)
        CpuBackendManager.saveMode(target, CpuBackendMode.valueOf(requested!!))
    }

    @Test fun smallQ51CancellationAndThreadMeasurements() = runBlocking { runValidation(true) }
    @Test fun threadMeasurementsOnly() = runBlocking { runValidation(false) }

    private suspend fun runValidation(includeCancellation: Boolean) = coroutineScope {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(target.packageName.endsWith(".validation"))
        val fixtures = File(target.filesDir, "validation")
        val source = File(fixtures, "small-q5_1.bin")
        check(source.isFile) { "Push the official Small q5_1 model to $source" }
        val modelId = "whisper_small_q5_1"
        val model = ModelUrls.getModelFile(target, modelId)
        source.copyTo(model, overwrite = true)
        check(ModelDownloader(target).verifiedModelFile(modelId) != null) { "Model checksum failed" }
        val pcm = File(fixtures, "speech.f32").readBytes()
        val floats = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining()).also { floats.get(it) }
        check(samples.size in 3 * 16000..30 * 16000)
        val power = target.getSystemService(PowerManager::class.java)
        fun requireCool() {
            check(!power.isPowerSaveMode) { "Disable battery saver" }
            check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE) { "Cool the phone before testing" }
        }
        requireCool()
        CpuBackendManager.ensureInitialized(target)
        val mode = CpuBackendManager.diagnostics.value.effectiveMode.name.lowercase()
        val native = WhisperContext.createContextFromFile(model.absolutePath)
        val params = WhisperParams(language = "en", promptMode = PromptMode.OFF,
            noContext = true, modelIdHint = modelId, threadCountOverride = 4)
        val record = StringBuilder("model=$modelId\nbackend=${CpuBackendManager.diagnostics.value}\n")
        try {
            assertTrue(native.warmup(4))
            if (includeCancellation) repeat(3) {
                requireCool()
                val longAudio = FloatArray(samples.size * 6) { samples[it % samples.size] }
                val inference = async(Dispatchers.Default) { native.transcribeData(longAudio, params) }
                delay(1500)
                assertTrue("Inference must still be running when cancelled", inference.isActive)
                val started = System.nanoTime()
                inference.cancelAndJoin()
                val latencyMs = (System.nanoTime() - started) / 1_000_000
                record.append("cancel_${it + 1}_ms=$latencyMs\n")
                assertTrue("Native cancel took ${latencyMs}ms", latencyMs < 3000)
            }
            val recovered = native.transcribeData(samples, params)
            record.append("recovery_transcript=$recovered\n")
            assertTrue("Speech must transcribe after repeated cancellation", recovered.contains("country", true))
        } finally {
            native.release()
            File(fixtures, "native-validation-$mode-${if (includeCancellation) "cancel" else "measurement"}.txt")
                .writeText(record.toString())
        }
        val repository = (target.applicationContext as VozLocalApp).repository
        repository.ensureModelCatalogInitialized()
        val backend = CpuBackendManager.diagnostics.value
        val results = mutableListOf<dev.sebastian.vozlocal.benchmark.TranscriptionBenchmarkResult>()
        try {
            for (round in 0..2) {
                for (threads in listOf(3, 4, 5).drop(round) + listOf(3, 4, 5).take(round)) {
                    requireCool()
                    val config = TranscriptionBenchmarkConfig(modelId, "q5_1", threads, "en", 0, 0.2f,
                        false, "jfk-public-domain-pcm", backendMode = backend.effectiveMode.name,
                        coldStart = results.isEmpty(), promptMode = PromptMode.OFF)
                    results += BenchmarkRunner.run(config, samples, samples.size * 1000L / 16000,
                        "jfk", "And so my fellow Americans ask not what your country can do for you ask what you can do for your country",
                        repository = repository, context = target, modelFile = model)
                    File(fixtures, "benchmark-results-$mode.json").writeText(BenchmarkRunner.exportToJson(results))
                    assertTrue(results.last().hypothesis.contains("country", true))
                }
            }
        } finally { repository.whisperEngine.release() }
    }
}
