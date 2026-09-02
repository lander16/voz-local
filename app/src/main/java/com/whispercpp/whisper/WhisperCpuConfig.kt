package com.whispercpp.whisper

import android.util.Log
import dev.sebastian.vozlocal.whisper.WhisperParams
import java.io.BufferedReader
import java.io.FileReader

private const val LOG_TAG = "WhisperCpuConfig"

object WhisperCpuConfig {
    private const val THREAD_PROPERTY = "vozlocal.whisper.threads"

    // Use high-perf cores by default but reserve CPU for audio/UI and cap to avoid
    // mobile SoC oversubscription/thermal throttling. Tunable with
    // -Dvozlocal.whisper.threads=N for tests or device-specific builds.
    val preferredThreadCount: Int by lazy {
        configuredThreadCount() ?: adaptiveThreadCount()
    }

    fun threadCountFor(params: WhisperParams): Int {
        configuredThreadCount()?.let { return it }
        val base = preferredThreadCount
        return threadCountFor(params, base, maxThreadCap())
    }

    internal fun threadCountFor(
        params: WhisperParams,
        base: Int,
        maxCap: Int = maxThreadCap()
    ): Int {
        val modelHint = params.modelIdHint
        return when {
            modelHint?.contains("large", ignoreCase = true) == true -> (base + 1).coerceAtMost(maxCap)
            modelHint?.contains("medium", ignoreCase = true) == true -> (base + 1).coerceAtMost(maxCap)
            modelHint?.contains("base", ignoreCase = true) == true -> minOf(base, 4)
            modelHint?.contains("tiny", ignoreCase = true) == true -> minOf(base, 3)
            else -> base
        }
    }

    internal fun configuredThreadCount(): Int? = System.getProperty(THREAD_PROPERTY)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    internal fun adaptiveThreadCount(
        available: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        highPerf: Int? = null
    ): Int {
        val highPerfCount = highPerf?.takeIf { it > 0 } ?: available
        val reserved = if (available >= 8) 2 else 1
        val usable = minOf(highPerfCount, (available - reserved).coerceAtLeast(1))
        val cap = if (available >= 6) 4 else 2
        return usable.coerceIn(1, cap)
    }

    internal fun maxThreadCap(
        available: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): Int = if (available >= 8) 5 else 4

    private fun adaptiveThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val highPerf = CpuInfo.getHighPerfCpuCount().takeIf { it > 0 } ?: available
        return adaptiveThreadCount(available, highPerf)
    }
}

private fun logDebug(msg: String, e: Throwable? = null) {
    runCatching {
        if (e != null) Log.d(LOG_TAG, msg, e) else Log.d(LOG_TAG, msg)
    }
}

private class CpuInfo(private val lines: List<String>) {
    private fun getHighPerfCpuCount(): Int = try {
        getHighPerfCpuCountByFrequencies()
    } catch (e: Throwable) {
        logDebug("Couldn't read CPU frequencies", e)
        getHighPerfCpuCountByVariant()
    }

    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues(property = "processor") { getMaxCpuFrequency(it.toInt()) }
            .also { logDebug("Binned cpu frequencies (frequency, count): ${it.binnedValues()}") }
            .countDroppingMin()

    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues(property = "CPU variant") { it.substringAfter("0x").toInt(radix = 16) }
            .also { logDebug("Binned cpu variants (variant, count): ${it.binnedValues()}") }
            .countKeepingMin()

    private fun List<Int>.binnedValues() = groupingBy { it }.eachCount()

    private fun getCpuValues(property: String, mapper: (String) -> Int) = lines
        .asSequence()
        .filter { it.startsWith(property) }
        .map { mapper(it.substringAfter(':').trim()) }
        .sorted()
        .toList()


    private fun List<Int>.countDroppingMin(): Int {
        val min = min()
        return count { it > min }
    }

    private fun List<Int>.countKeepingMin(): Int {
        val min = min()
        return count { it == min }
    }

    companion object {
        fun getHighPerfCpuCount(): Int = try {
            readCpuInfo().getHighPerfCpuCount()
        } catch (e: Throwable) {
            logDebug("Couldn't read CPU info", e)
            // Our best guess -- just return the # of CPUs minus 4.
            (Runtime.getRuntime().availableProcessors() - 4).coerceAtLeast(0)
        }

        private fun readCpuInfo() = CpuInfo(
            BufferedReader(FileReader("/proc/cpuinfo"))
                .useLines { it.toList() }
        )

        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu${cpuIndex}/cpufreq/cpuinfo_max_freq"
            val maxFreq = BufferedReader(FileReader(path)).use { it.readLine() }
            return maxFreq.toInt()
        }
    }
}
