package dev.sebastian.vozlocal.fastconformervalidation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FastConformerSmokeTest {
    @Test
    fun prepareStagingDirectory() {
        val staging = stagingDirectory()
        assertTrue("Could not create app-scoped staging directory", staging.isDirectory || staging.mkdirs())
    }

    @Test
    fun offlineCtcDecodesPinnedFixtureWithoutPersistingText() {
        val requestedRunId = requireNotNull(InstrumentationRegistry.getArguments().getString("requestedRunId")) {
            "Missing host-requested run ID"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.sebastian.vozlocal.fastconformer.validation", context.packageName)
        val staging = stagingDirectory()
        val pcmFile = File(staging, "speech.f32")
        assertTrue("Missing locally staged PCM fixture", pcmFile.isFile)
        assertEquals(640_000L, pcmFile.length())
        val pcmSha = FastConformerModelBundle.sha256(pcmFile)
        assertEquals("23b8db3cdc813839c01bc4d7f7920a780de5f078a91a1fd67184c8acea81f25a", pcmSha)
        assertTrue("Pinned model files are missing or corrupt", FastConformerModelBundle.verify(staging))

        val pcmBytes = pcmFile.readBytes()
        assertEquals(0, pcmBytes.size % 4)
        val samples = FloatArray(pcmBytes.size / 4)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        assertTrue("PCM samples must be finite and normalized", samples.all { it.isFinite() && it in -1f..1f })
        assertEquals(160_000, samples.size)

        val threads = 2
        val before = systemSnapshot(context)
        val bundleVerifyAndInitStart = System.nanoTime()
        val adapter = FastConformerAdapter(staging, numThreads = threads)
        val bundleVerifyAndNativeInitMs = elapsedMs(bundleVerifyAndInitStart)
        val timings = JSONArray()
        var resultDigest = ""
        var punctuation = JSONObject()
        try {
            repeat(4) { iteration ->
                val start = System.nanoTime()
                val text = try {
                    adapter.transcribe(samples)
                } catch (_: Throwable) {
                    throw AssertionError("Native inference failed; transcript intentionally omitted")
                }
                val duration = elapsedMs(start)
                assertTrue("Model returned an empty result", text.isNotBlank())
                val bytes = text.toByteArray(Charsets.UTF_8)
                val digest = sha256(bytes)
                if (iteration > 0) assertEquals("Repeated output changed for identical PCM", resultDigest, digest)
                resultDigest = digest
                punctuation = JSONObject()
                    .put("period", text.count { it == '.' })
                    .put("comma", text.count { it == ',' })
                    .put("inverted_question", text.count { it == '¿' })
                    .put("question", text.count { it == '?' })
                    .put("text_length_chars", text.length)
                timings.put(JSONObject()
                    .put("kind", if (iteration == 0) "first_inference" else "warm_inference")
                    .put("iteration", iteration)
                    .put("elapsed_ms", duration))
            }
        } finally {
            adapter.close()
        }

        val report = JSONObject()
            .put("schema", "fastconformer-device-smoke-v1")
            .put("run_id", requestedRunId)
            .put("completed_epoch_ms", System.currentTimeMillis())
            .put("source_commit", InstrumentationRegistry.getArguments().getString("sourceCommit"))
            .put("source_tree_dirty", InstrumentationRegistry.getArguments().getString("sourceTreeDirty"))
            .put("app_apk_sha256", InstrumentationRegistry.getArguments().getString("appApkSha256"))
            .put("test_apk_sha256", InstrumentationRegistry.getArguments().getString("testApkSha256"))
            .put("device", Build.DEVICE)
            .put("android_release", Build.VERSION.RELEASE)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("model_id", FastConformerModelBundle.MODEL_ID)
            .put("derivative_id", FastConformerModelBundle.DERIVATIVE_ID)
            .put("hf_revision", FastConformerModelBundle.HF_REVISION)
            .put("license", FastConformerModelBundle.LICENSE)
            .put("sherpa_version", FastConformerModelBundle.SHERPA_VERSION)
            .put("runtime_aar_sha256", FastConformerModelBundle.SHERPA_AAR_SHA256)
            .put("model_sha256", FastConformerModelBundle.artifacts[0].sha256)
            .put("tokens_sha256", FastConformerModelBundle.artifacts[1].sha256)
            .put("source_fixture_sha256", "19d015c3c78fe806134ddffbe144d216263b4d195b27cd7799ea667a3c575ba1")
            .put("pcm_sha256", pcmSha)
            .put("sample_rate_hz", FastConformerAdapter.SAMPLE_RATE)
            .put("sample_count", samples.size)
            .put("num_threads", threads)
            .put("bundle_verify_and_native_init_ms", bundleVerifyAndNativeInitMs)
            .put("inference", timings)
            .put("output_sha256", resultDigest)
            .put("punctuation_counts", punctuation)
            .put("system_before", before)
            .put("system_after", systemSnapshot(context))
            .put("transcript_persisted", false)
        File(staging, "fastconformer-smoke-result.json").writeText(report.toString(2))
    }

    private fun stagingDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null) ?: error("App-scoped external storage unavailable"), "fastconformer-validation")
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun systemSnapshot(context: Context): JSONObject {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("battery_percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("battery_temp_tenths_c", batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .put("plugged", (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            .put("battery_saver", power.isPowerSaveMode)
            .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
