package dev.sebastian.vozlocal

import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
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
import java.util.concurrent.Executors
import java.util.concurrent.Callable

/** Offline, fixture-only Moonshine smoke validation. Not an accuracy benchmark. */
@RunWith(AndroidJUnit4::class)
class MoonshineValidationTest {
    @Test
    fun completeClipSmoke() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".validation"))
        val args = InstrumentationRegistry.getArguments()
        val modelName = args.getString("moonshineModel", "tiny-es")!!
        require(modelName == "tiny-es" || modelName == "small-es") { "moonshineModel must be tiny-es or small-es" }
        val iterations = args.getString("moonshineIterations", "3")!!.toInt().coerceIn(1, 30)
        val root = File(context.filesDir, "validation/moonshine")
        val modelDir = File(root, modelName)
        val pcmFile = File(root, "speech.f32")
        require(modelDir.isDirectory) { "Missing staged model directory: $modelDir" }
        require(pcmFile.isFile) { "Missing staged PCM fixture: $pcmFile" }
        verifyManifest(modelDir, File(modelDir, "manifest.json"), modelName)
        val pcmSha = sha256(pcmFile)
        val expectedPcmSha = File(root, "speech.sha256").readText().trim().split(Regex("\\s+"), limit = 2).first()
        require(pcmSha.equals(expectedPcmSha, true)) { "PCM SHA-256 mismatch" }
        require(pcmFile.length() in 4L..(30L * 16000 * 4)) { "PCM must be at most 30 seconds" }
        val pcmBytes = pcmFile.readBytes()
        require(pcmBytes.size % 4 == 0) { "PCM fixture is not float32 little-endian" }
        val floats = FloatArray(pcmBytes.size / 4)
        require(floats.size <= 30 * 16000) { "PCM fixture exceeds 30 seconds" }
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        require(floats.isNotEmpty() && floats.all { it.isFinite() && it in -1f..1f })

        val report = JSONObject()
            .put("schema", "moonshine-validation-v1")
            .put("app_commit", args.getString("appCommit") ?: JSONObject.NULL)
            .put("moonshine_version", "0.1.5")
            .put("moonshine_aar_sha256", "ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571")
            .put("timestamp_epoch_ms", System.currentTimeMillis())
            .put("model", modelName)
            .put("language", "es")
            .put("architecture", if (modelName == "tiny-es") JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING else JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING)
            .put("iterations_requested", iterations)
            .put("pcm_sha256", pcmSha)
            .put("model_manifest_sha256", sha256(File(modelDir, "manifest.json")))
            .put("native_header_version", JNI.MOONSHINE_HEADER_VERSION)
            .put("pcm_samples", floats.size)
            .put("sample_rate", 16000)
            .put("runtime", runtimeSnapshot(context))
            .put("streaming", JSONObject().put("status", "pending").put("reason", "complete-clip smoke only"))
        val loads = JSONArray()
        var warmupMs: Any = JSONObject.NULL
        val warm = JSONArray()
        val hypotheses = JSONArray()
        val executor = Executors.newSingleThreadExecutor()
        var failure: Throwable? = null
        var native: Transcriber? = null
        try {
            requireCool(context)
            val loadStart = System.nanoTime()
            val architecture = if (modelName == "tiny-es") JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
            else JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
            executor.submit {
                native = Transcriber()
                native!!.loadFromFiles(modelDir.absolutePath, architecture)
            }.get()
            loads.put((System.nanoTime() - loadStart) / 1_000_000)
            val warmupStart = System.nanoTime()
            executor.submit { native!!.transcribeWithoutStreaming(floats, 16000, 0) }.get()
            warmupMs = (System.nanoTime() - warmupStart) / 1_000_000
            repeat(iterations) {
                requireCool(context)
                val started = System.nanoTime()
                val result = executor.submit(Callable {
                    native!!.transcribeWithoutStreaming(floats, 16000, 0).lines
                        .joinToString(" ") { it.text }
                }).get()
                warm.put((System.nanoTime() - started) / 1_000_000)
                hypotheses.put(result)
                requireCool(context)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val closeError = if (native != null) runCatching { executor.submit { native?.close() }.get() }.exceptionOrNull() else null
            executor.shutdown()
            report.put("cold_load_ms", if (loads.length() == 0) JSONObject.NULL else loads.getLong(0))
                .put("warmup_ms", warmupMs)
                .put("warm_inference_ms", warm)
                .put("hypotheses", hypotheses)
                .put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("close_failure", closeError?.toString() ?: JSONObject.NULL)
                .put("runtime_after", runtimeSnapshot(context))
            File(root, "report-$modelName.json").apply { parentFile?.mkdirs(); writeText(report.toString(2)) }
            if (failure == null && closeError != null) throw closeError
        }
    }

    private fun verifyManifest(directory: File, manifest: File, modelName: String) {
        require(manifest.isFile) { "Missing model manifest: $manifest" }
        val document = JSONObject(manifest.readText())
        require(document.optString("model") == modelName) { "Manifest model identity mismatch" }
        require(document.optString("upstreamRevision") == "234f60faa0eb388b01cdf7e60aca232af37aefda") {
            "Unexpected Moonshine upstream revision"
        }
        require(document.optString("runtime") == "0.1.5") { "Unexpected Moonshine runtime in manifest" }
        val files = document.optJSONArray("files")
            ?: error("Manifest must contain files[]")
        val expected = setOf("adapter.ort", "cross_kv.ort", "decoder_kv.ort", "encoder.ort",
            "frontend.model.ort", "frontend.weights.ort", "streaming_config.json", "tokenizer.bin")
        require(files.length() == expected.size)
        val seen = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val name = entry.getString("name")
            require(name in expected && !name.contains('/') && !name.contains('\\')) { "Unexpected model asset: $name" }
            require(seen.add(name)) { "Duplicate model asset: $name" }
            val file = File(directory, name)
            require(file.isFile) { "Missing model asset: $file" }
            require(entry.getLong("size") > 0 && file.length() == entry.getLong("size"))
            require(entry.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            require(sha256(file).equals(entry.getString("sha256"), true)) { "SHA-256 mismatch: $file" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun runtimeSnapshot(context: android.content.Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        return JSONObject().put("sdk", Build.VERSION.SDK_INT).put("device", Build.MODEL)
            .put("build", Build.DISPLAY).put("battery_saver", power?.isPowerSaveMode)
            .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else JSONObject.NULL)
            .put("charging", battery?.isCharging)
    }

    private fun requireCool(context: android.content.Context) {
        val power = context.getSystemService(PowerManager::class.java)
        check(power?.isPowerSaveMode != true) { "Disable Battery Saver for validation" }
        if (Build.VERSION.SDK_INT >= 29) {
            check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE) {
                "Phone is thermally throttled"
            }
        }
    }
}
