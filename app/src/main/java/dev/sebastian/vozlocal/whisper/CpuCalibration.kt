package dev.sebastian.vozlocal.whisper

import android.content.Context
import android.os.Build
import com.whispercpp.whisper.FileThreadProfileStore
import com.whispercpp.whisper.ThreadProfileManager
import com.whispercpp.whisper.WhisperCpuConfig
import dev.sebastian.vozlocal.data.repository.ModelDownloader
import java.io.File
import java.security.MessageDigest

/** Profiles are opt-in and usable only for the exact measured runtime/workload. */
object CpuCalibration {
    fun initialize(context: Context) {
        WhisperCpuConfig.profileManager = ThreadProfileManager(
            FileThreadProfileStore(File(context.filesDir, "cpu-calibration-v2.tsv"))
        )
    }

    internal fun key(modelId: String, params: WhisperParams, samples: Int): String? {
        val checksum = ModelDownloader.sha256Map[modelId] ?: return null
        val backend = CpuBackendManager.diagnostics.value
        if (!backend.isReady || backend.nativeBuildId == "unknown") return null
        val workload = if (samples < 25 * 16000) "short" else "long"
        val identity = listOf(
            "v2", Build.FINGERPRINT, Build.HARDWARE,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown",
            backend.nativeBuildId, backend.effectiveMode, backend.tier, checksum, workload,
            params.copy(threadCountOverride = null, calibrationKey = null, modelIdHint = modelId)
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
