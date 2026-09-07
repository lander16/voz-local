package com.whispercpp.whisper

import android.util.Log
import dev.sebastian.vozlocal.whisper.WhisperParams
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val LOG_TAG = "WhisperCpuConfig"

data class ThreadCalibrationProfile(
    val deviceId: String,
    val modelId: String,
    val optimalThreads: Int,
    val calibratedAtMs: Long,
    val nativeBuildId: String? = null
)

interface ThreadProfileStore {
    fun load(): List<ThreadCalibrationProfile>
    fun save(profiles: List<ThreadCalibrationProfile>)
    fun clear()
}

class FileThreadProfileStore(private val file: File) : ThreadProfileStore {
    override fun load(): List<ThreadCalibrationProfile> {
        if (!file.exists() || !file.canRead()) return emptyList()
        return try {
            file.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 4) {
                        val deviceId = parts[0]
                        val modelId = parts[1]
                        val optimalThreads = parts[2].toIntOrNull() ?: return@mapNotNull null
                        val calibratedAtMs = parts[3].toLongOrNull() ?: return@mapNotNull null
                        val nativeBuildId = if (parts.size >= 5) parts[4].takeIf { it.isNotBlank() } else null
                        ThreadCalibrationProfile(deviceId, modelId, optimalThreads, calibratedAtMs, nativeBuildId)
                    } else null
                }
        } catch (e: Throwable) {
            logDebug("Failed to load thread calibration profiles from file", e)
            emptyList()
        }
    }

    override fun save(profiles: List<ThreadCalibrationProfile>) {
        try {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.printWriter().use { writer ->
                writer.println("# deviceId\tmodelId\toptimalThreads\tcalibratedAtMs\tnativeBuildId")
                for (p in profiles) {
                    writer.println("${p.deviceId}\t${p.modelId}\t${p.optimalThreads}\t${p.calibratedAtMs}\t${p.nativeBuildId ?: ""}")
                }
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Throwable) {
            logDebug("Failed to save thread calibration profiles to file", e)
        }
    }

    override fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Throwable) {
            logDebug("Failed to delete thread calibration profiles file", e)
        }
    }
}

class ThreadProfileManager(
    private val store: ThreadProfileStore? = null,
    var currentNativeBuildId: String? = null
) {
    private val cache = ConcurrentHashMap<String, ThreadCalibrationProfile>()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        if (store == null) return
        val loaded = store.load()
        cache.clear()
        for (profile in loaded) {
            if (currentNativeBuildId != null && profile.nativeBuildId != null && profile.nativeBuildId != currentNativeBuildId) {
                continue
            }
            cache[cacheKey(profile.deviceId, profile.modelId)] = profile
        }
    }

    private fun persist() {
        store?.save(cache.values.toList())
    }

    private fun cacheKey(deviceId: String, modelId: String): String = "$deviceId:$modelId"

    fun getProfile(deviceId: String, modelId: String): ThreadCalibrationProfile? {
        val profile = cache[cacheKey(deviceId, modelId)] ?: return null
        if (currentNativeBuildId != null && profile.nativeBuildId != null && profile.nativeBuildId != currentNativeBuildId) {
            cache.remove(cacheKey(deviceId, modelId))
            persist()
            return null
        }
        return profile
    }

    fun getOptimalThreads(deviceId: String, modelId: String): Int? =
        getProfile(deviceId, modelId)?.optimalThreads

    fun saveProfile(profile: ThreadCalibrationProfile) {
        val effective = if (profile.nativeBuildId == null && currentNativeBuildId != null) {
            profile.copy(nativeBuildId = currentNativeBuildId)
        } else {
            profile
        }
        cache[cacheKey(effective.deviceId, effective.modelId)] = effective
        persist()
    }

    fun invalidate(deviceId: String, modelId: String) {
        if (cache.remove(cacheKey(deviceId, modelId)) != null) {
            persist()
        }
    }

    fun invalidateModel(modelId: String) {
        val keysToRemove = cache.filter { it.value.modelId == modelId }.keys
        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { cache.remove(it) }
            persist()
        }
    }

    fun invalidateNativeBuild(newNativeBuildId: String) {
        this.currentNativeBuildId = newNativeBuildId
        val staleKeys = cache.filter {
            it.value.nativeBuildId != null && it.value.nativeBuildId != newNativeBuildId
        }.keys
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { cache.remove(it) }
            persist()
        }
    }

    fun clear() {
        cache.clear()
        store?.clear()
    }

    fun getAllProfiles(): List<ThreadCalibrationProfile> = cache.values.toList()
}

object WhisperCpuConfig {
    private const val THREAD_PROPERTY = "vozlocal.whisper.threads"
    private const val PRIORITY_PROPERTY = "vozlocal.whisper.thread_priority"
    const val DEFAULT_THREAD_PRIORITY = 0

    @Volatile
    var cpuInfoProvider: CpuInfoProvider = DefaultCpuInfoProvider()

    @Volatile
    var profileManager: ThreadProfileManager? = ThreadProfileManager()

    @Volatile
    var deviceId: String = runCatching { android.os.Build.MODEL }.getOrNull()?.takeIf { it.isNotBlank() } ?: "default_device"

    @Volatile
    var threadPriority: Int = configuredThreadPriority() ?: DEFAULT_THREAD_PRIORITY

    fun resetCpuInfoProvider() {
        cpuInfoProvider = DefaultCpuInfoProvider()
    }

    fun resetThreadPriority() {
        threadPriority = configuredThreadPriority() ?: DEFAULT_THREAD_PRIORITY
    }

    internal fun configuredThreadPriority(): Int? =
        System.getProperty(PRIORITY_PROPERTY)?.toIntOrNull()

    // Use high-perf cores by default but reserve CPU for audio/UI and cap to avoid
    // mobile SoC oversubscription/thermal throttling. Tunable with
    // -Dvozlocal.whisper.threads=N for tests or device-specific builds.
    val preferredThreadCount: Int
        get() = configuredThreadCount() ?: adaptiveThreadCount()

    fun threadCountFor(params: WhisperParams): Int {
        val available = cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1)
        configuredThreadCount()?.let { return it.coerceIn(1, available) }

        val modelHint = params.modelIdHint
        if (modelHint != null) {
            val optimal = profileManager?.getOptimalThreads(deviceId, modelHint)
            if (optimal != null) {
                return optimal.coerceIn(1, available)
            }
        }

        val base = preferredThreadCount
        val maxCap = maxThreadCap(available)
        return threadCountFor(params, base, maxCap, available)
    }

    internal fun threadCountFor(
        params: WhisperParams,
        base: Int,
        maxCap: Int = maxThreadCap(),
        availableProcessors: Int = maxOf(maxCap, cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1))
    ): Int {
        val modelHint = params.modelIdHint
        val baseOrSmallCap = if (maxCap >= 5) 5 else 4
        val raw = when {
            modelHint?.contains("large", ignoreCase = true) == true -> (base + 1).coerceAtMost(maxCap)
            modelHint?.contains("medium", ignoreCase = true) == true -> (base + 1).coerceAtMost(maxCap)
            modelHint?.contains("small", ignoreCase = true) == true -> minOf(base, baseOrSmallCap)
            modelHint?.contains("base", ignoreCase = true) == true -> minOf(base, baseOrSmallCap)
            modelHint?.contains("tiny", ignoreCase = true) == true -> minOf(base, 3)
            else -> base
        }
        return raw.coerceIn(1, availableProcessors)
    }

    internal fun configuredThreadCount(): Int? = System.getProperty(THREAD_PROPERTY)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1))

    internal fun adaptiveThreadCount(
        available: Int = cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1),
        highPerf: Int? = null
    ): Int {
        val highPerfCount = highPerf?.takeIf { it > 0 } ?: available
        val reserved = if (available >= 8) 2 else 1
        val usable = minOf(highPerfCount, (available - reserved).coerceAtLeast(1))
        val cap = when {
            available >= 8 || highPerfCount >= 5 -> 5
            available >= 6 -> 4
            else -> 2
        }
        return usable.coerceIn(1, cap).coerceAtMost(available)
    }

    internal fun maxThreadCap(
        available: Int = cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1)
    ): Int = when {
        available >= 9 -> 6
        available >= 8 -> 5
        else -> 4
    }

    fun adaptiveThreadCount(): Int {
        val available = cpuInfoProvider.getAvailableProcessors().coerceAtLeast(1)
        val topology = cpuInfoProvider.getCpuTopology()
        val highPerf = topology.highPerfCores.takeIf { it > 0 } ?: available
        return adaptiveThreadCount(available, highPerf)
    }
}

private fun logDebug(msg: String, e: Throwable? = null) {
    runCatching {
        if (e != null) Log.d(LOG_TAG, msg, e) else Log.d(LOG_TAG, msg)
    }
}
