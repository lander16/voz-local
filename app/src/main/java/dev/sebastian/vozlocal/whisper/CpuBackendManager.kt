package dev.sebastian.vozlocal.whisper

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CpuBackendMode {
    COMPATIBILITY,
    AUTOMATIC;

    companion object {
        fun parse(value: String?): CpuBackendMode =
            values().firstOrNull { it.name == value } ?: COMPATIBILITY
    }
}

data class CpuBackendDiagnostics(
    val status: String = "uninitialized",
    val requestedMode: CpuBackendMode = CpuBackendMode.COMPATIBILITY,
    val effectiveMode: CpuBackendMode = CpuBackendMode.COMPATIBILITY,
    val tier: String = "none",
    val features: List<String> = emptyList(),
    val nativeBuildId: String = "unknown",
    val fallbackReason: String? = null,
    val recoveredFromInterruptedProbe: Boolean = false,
) {
    val isReady: Boolean get() = status == "ready"

    companion object {
        fun fromNative(
            raw: String,
            requestedMode: CpuBackendMode,
            effectiveMode: CpuBackendMode,
            recovered: Boolean,
        ): CpuBackendDiagnostics {
            val values = raw.split(';').mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null
                else field.substring(0, separator) to field.substring(separator + 1)
            }.toMap()
            return CpuBackendDiagnostics(
                status = values["status"] ?: "error",
                requestedMode = requestedMode,
                effectiveMode = effectiveMode,
                tier = values["tier"] ?: "none",
                features = values["features"]
                    ?.split(',')
                    ?.filter { it.isNotBlank() && it != "none" }
                    .orEmpty(),
                nativeBuildId = values["build"] ?: "unknown",
                fallbackReason = values["error"]
                    ?: if (recovered) "previous_optimized_probe_interrupted" else null,
                recoveredFromInterruptedProbe = recovered,
            )
        }
    }
}

/**
 * Owns the process-wide ggml CPU selection. A backend cannot be switched after
 * ggml registers it, so preference changes deliberately apply next process.
 */
object CpuBackendManager {
    private const val TAG = "CpuBackendManager"
    private const val PREFS = "vozlocal_prefs"
    private const val KEY_MODE = "cpu_backend_mode"
    private const val KEY_PROBE_PENDING = "cpu_backend_probe_pending"
    private const val KEY_QUARANTINED = "cpu_backend_quarantined"

    private val mutableDiagnostics = MutableStateFlow(CpuBackendDiagnostics())
    val diagnostics: StateFlow<CpuBackendDiagnostics> = mutableDiagnostics.asStateFlow()

    @Volatile
    private var initialized = false
    private var processRequestedMode = CpuBackendMode.COMPATIBILITY
    private var processEffectiveMode = CpuBackendMode.COMPATIBILITY
    private var recovered = false

    fun startup(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        processRequestedMode = CpuBackendMode.parse(prefs.getString(KEY_MODE, null))
        if (prefs.getBoolean(KEY_PROBE_PENDING, false)) {
            prefs.edit()
                .putBoolean(KEY_PROBE_PENDING, false)
                .putBoolean(KEY_QUARANTINED, true)
                .commit()
        }
        recovered = prefs.getBoolean(KEY_QUARANTINED, false)
        processEffectiveMode = if (processRequestedMode == CpuBackendMode.AUTOMATIC && !recovered) {
            CpuBackendMode.AUTOMATIC
        } else {
            CpuBackendMode.COMPATIBILITY
        }
        mutableDiagnostics.value = CpuBackendDiagnostics(
            requestedMode = processRequestedMode,
            effectiveMode = processEffectiveMode,
            fallbackReason = if (recovered) "previous_optimized_probe_interrupted" else null,
            recoveredFromInterruptedProbe = recovered,
        )
    }

    @Synchronized
    fun ensureInitialized(context: Context): CpuBackendDiagnostics {
        startup(context)
        if (initialized) return mutableDiagnostics.value
        val automatic = processEffectiveMode == CpuBackendMode.AUTOMATIC
        if (automatic) {
            // A synchronous write survives a native process termination during
            // module loading or the first warmup inference.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PROBE_PENDING, true).commit()
        }
        val raw = WhisperContext.initializeCpuBackend(
            context.applicationInfo.nativeLibraryDir,
            automatic,
        )
        val result = CpuBackendDiagnostics.fromNative(
            raw = raw,
            requestedMode = processRequestedMode,
            effectiveMode = processEffectiveMode,
            recovered = recovered,
        )
        mutableDiagnostics.value = result
        initialized = result.isReady
        if (!result.isReady) {
            Log.e(TAG, "CPU backend initialization failed: ${result.fallbackReason}")
            throw IllegalStateException("No compatible local transcription CPU backend")
        }
        return result
    }

    fun markWarmupSuccessful(context: Context) {
        if (processEffectiveMode != CpuBackendMode.AUTOMATIC) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROBE_PENDING, false).commit()
    }

    fun getSavedMode(context: Context): CpuBackendMode = CpuBackendMode.parse(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
    )

    fun saveMode(context: Context, mode: CpuBackendMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name)
            .putBoolean(KEY_PROBE_PENDING, false)
            .putBoolean(KEY_QUARANTINED, false)
            .commit()
    }
}
