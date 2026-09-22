package dev.sebastian.vozlocal

import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whispercpp.whisper.WhisperContext
import dev.sebastian.vozlocal.whisper.PromptMode
import dev.sebastian.vozlocal.whisper.WhisperParams
import dev.sebastian.vozlocal.whisper.CpuBackendManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Matched Whisper CPU control run for the isolated Moonshine screening fixture. */
@RunWith(AndroidJUnit4::class)
class MoonshineControlValidationTest {
    @Test
    fun whisperCompleteClipControl() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".validation"))
        val iterations = InstrumentationRegistry.getArguments().getString("moonshineIterations", "3")!!.toInt().coerceIn(1, 30)
        val root = File(context.filesDir, "validation/moonshine")
        val model = File(root, "whisper-small-q8.bin")
        val pcm = File(root, "speech.f32")
        require(model.isFile && pcm.isFile) { "Missing staged Whisper control fixture" }
        require(sha256(model).equals("49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", true)) {
            "Whisper control SHA-256 mismatch"
        }
        require(pcm.length() in 4L..(30L * 16000 * 4))
        require(sha256(pcm) == File(root, "speech.sha256").readText().trim().split(Regex("\\s+"), limit = 2).first())
        val pcmBytes = pcm.readBytes()
        require(pcmBytes.size % 4 == 0) { "PCM fixture is not float32 little-endian" }
        val samples = FloatArray(pcmBytes.size / 4)
        require(samples.size <= 30 * 16000)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        require(samples.all { it.isFinite() && it in -1f..1f })
        val report = JSONObject().put("schema", "moonshine-validation-v1")
            .put("app_commit", InstrumentationRegistry.getArguments().getString("appCommit") ?: JSONObject.NULL)
            .put("timestamp_epoch_ms", System.currentTimeMillis())
            .put("model", "whisper-small-q8-control").put("language", "es")
            .put("iterations_requested", iterations).put("pcm_sha256", sha256(pcm))
            .put("model_sha256", sha256(model)).put("pcm_samples", samples.size)
            .put("sample_rate", 16000).put("thread_count", 4)
            .put("runtime", runtimeSnapshot(context))
            .put("streaming", JSONObject().put("status", "not_applicable"))
        val timings = JSONArray(); val hypotheses = JSONArray()
        var loadMs: Long? = null; var warmupMs: Long? = null; var failure: Throwable? = null
        var native: WhisperContext? = null
        try {
            requireCool(context)
            CpuBackendManager.ensureInitialized(context)
            report.put("backend", CpuBackendManager.diagnostics.value.toString())
                .put("prompt_mode", "OFF").put("no_context", true)
            val loadStart = System.nanoTime()
            native = WhisperContext.createContextFromFile(model.absolutePath)
            loadMs = (System.nanoTime() - loadStart) / 1_000_000
            val params = WhisperParams(language = "es", promptMode = PromptMode.OFF,
                noContext = true, modelIdHint = "whisper_small", threadCountOverride = 4)
            val warmStart = System.nanoTime()
            native!!.transcribeData(samples, params)
            warmupMs = (System.nanoTime() - warmStart) / 1_000_000
            repeat(iterations) {
                requireCool(context)
                val started = System.nanoTime()
                hypotheses.put(native!!.transcribeData(samples, params))
                timings.put((System.nanoTime() - started) / 1_000_000)
                requireCool(context)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val closeError = runCatching { native?.release() }.exceptionOrNull()
            report.put("cold_load_ms", loadMs?.let { it } ?: JSONObject.NULL)
                .put("warmup_ms", warmupMs?.let { it } ?: JSONObject.NULL)
                .put("warm_inference_ms", timings).put("hypotheses", hypotheses)
                .put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("close_failure", closeError?.toString() ?: JSONObject.NULL)
                .put("runtime_after", runtimeSnapshot(context))
            File(root, "report-whisper-small-q8-control.json").writeText(report.toString(2))
            if (failure == null && closeError != null) throw closeError
        }
    }

    private fun requireCool(context: android.content.Context) {
        val power = context.getSystemService(PowerManager::class.java)
        check(power?.isPowerSaveMode != true) { "Disable Battery Saver for validation" }
        if (Build.VERSION.SDK_INT >= 29) check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE)
    }

    private fun runtimeSnapshot(context: android.content.Context) = JSONObject()
        .put("sdk", Build.VERSION.SDK_INT).put("device", Build.MODEL).put("build", Build.DISPLAY)
        .put("battery_saver", context.getSystemService(PowerManager::class.java)?.isPowerSaveMode)
        .put("charging", context.getSystemService(BatteryManager::class.java)?.isCharging)
        .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java)?.currentThermalStatus else JSONObject.NULL)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
