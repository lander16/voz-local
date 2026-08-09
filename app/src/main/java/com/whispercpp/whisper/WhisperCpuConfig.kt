package com.whispercpp.whisper

import android.util.Log
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

    private fun configuredThreadCount(): Int? = System.getProperty(THREAD_PROPERTY)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    private fun adaptiveThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val highPerf = CpuInfo.getHighPerfCpuCount().takeIf { it > 0 } ?: available
        val reserved = if (available >= 8) 2 else 1
        val usable = minOf(highPerf, (available - reserved).coerceAtLeast(1))
        val cap = if (available >= 6) 4 else 2
        return usable.coerceIn(1, cap)
    }
}

private class CpuInfo(private val lines: List<String>) {
    private fun getHighPerfCpuCount(): Int = try {
        getHighPerfCpuCountByFrequencies()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Couldn't read CPU frequencies", e)
        getHighPerfCpuCountByVariant()
    }

    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues(property = "processor") { getMaxCpuFrequency(it.toInt()) }
            .also { Log.d(LOG_TAG, "Binned cpu frequencies (frequency, count): ${it.binnedValues()}") }
            .countDroppingMin()

    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues(property = "CPU variant") { it.substringAfter("0x").toInt(radix = 16) }
            .also { Log.d(LOG_TAG, "Binned cpu variants (variant, count): ${it.binnedValues()}") }
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
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Couldn't read CPU info", e)
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
