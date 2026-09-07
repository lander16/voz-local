package com.whispercpp.whisper

import android.util.Log
import java.io.File

private const val LOG_TAG = "CpuInfoProvider"

data class CpuTopology(
    val totalCores: Int,
    val efficiencyCores: Int,
    val performanceCores: Int,
    val primeCores: Int = 0
) {
    val highPerfCores: Int get() = performanceCores + primeCores
}

interface CpuInfoProvider {
    fun getAvailableProcessors(): Int
    fun getCoreFrequencies(): List<Int>
    fun getCpuTopology(): CpuTopology
}

open class DefaultCpuInfoProvider(
    private val sysCpuDir: File = File("/sys/devices/system/cpu"),
    private val procCpuInfo: File = File("/proc/cpuinfo")
) : CpuInfoProvider {

    override fun getAvailableProcessors(): Int =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    override fun getCoreFrequencies(): List<Int> {
        val sysfsFreqs = readFrequenciesFromSysfs()
        if (sysfsFreqs.isNotEmpty()) {
            return sysfsFreqs
        }
        return readFrequenciesFromProc()
    }

    override fun getCpuTopology(): CpuTopology {
        val freqs = getCoreFrequencies()
        val available = getAvailableProcessors()
        return computeTopology(available, freqs)
    }

    private fun readFrequenciesFromSysfs(): List<Int> {
        return try {
            if (!sysCpuDir.exists() || !sysCpuDir.isDirectory) return emptyList()
            val cpuDirs = sysCpuDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("cpu\\d+"))
            } ?: return emptyList()

            val sortedCpuDirs = cpuDirs.sortedBy { dir ->
                dir.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE
            }

            val frequencies = mutableListOf<Int>()
            for (cpuDir in sortedCpuDirs) {
                val maxFreqFile = File(cpuDir, "cpufreq/cpuinfo_max_freq")
                val scalingMaxFreqFile = File(cpuDir, "cpufreq/scaling_max_freq")
                val fileToRead = when {
                    maxFreqFile.canRead() -> maxFreqFile
                    scalingMaxFreqFile.canRead() -> scalingMaxFreqFile
                    else -> null
                }
                if (fileToRead != null) {
                    val freq = fileToRead.readText().trim().toIntOrNull()
                    if (freq != null && freq > 0) {
                        frequencies.add(freq)
                    }
                }
            }
            frequencies
        } catch (e: Throwable) {
            logDebug("Couldn't read CPU frequencies from sysfs", e)
            emptyList()
        }
    }

    private fun readFrequenciesFromProc(): List<Int> {
        return try {
            if (!procCpuInfo.exists() || !procCpuInfo.canRead()) return emptyList()
            val frequencies = mutableListOf<Int>()
            procCpuInfo.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("cpu MHz", ignoreCase = true)) {
                    val mhz = trimmed.substringAfter(":").trim().toDoubleOrNull()
                    if (mhz != null && mhz > 0.0) {
                        frequencies.add((mhz * 1000).toInt())
                    }
                }
            }
            frequencies
        } catch (e: Throwable) {
            logDebug("Couldn't read CPU frequencies from proc", e)
            emptyList()
        }
    }

    companion object {
        fun computeTopology(availableProcessors: Int, frequencies: List<Int>): CpuTopology {
            if (frequencies.isEmpty()) {
                val available = availableProcessors.coerceAtLeast(1)
                return when {
                    available >= 8 -> CpuTopology(
                        totalCores = available,
                        efficiencyCores = 4,
                        performanceCores = available - 4,
                        primeCores = 0
                    )
                    available >= 6 -> CpuTopology(
                        totalCores = available,
                        efficiencyCores = 2,
                        performanceCores = available - 2,
                        primeCores = 0
                    )
                    else -> CpuTopology(
                        totalCores = available,
                        efficiencyCores = 0,
                        performanceCores = available,
                        primeCores = 0
                    )
                }
            }

            val total = frequencies.size
            val grouped = frequencies.groupBy { it }
            val sortedClusters = grouped.keys.sorted()

            return when (sortedClusters.size) {
                1 -> {
                    // 1 cluster = homogeneous
                    CpuTopology(
                        totalCores = total,
                        efficiencyCores = 0,
                        performanceCores = total,
                        primeCores = 0
                    )
                }
                2 -> {
                    // 2 clusters = efficiency (lower frequency) + performance (higher frequency)
                    // e.g. 2+6: 2 efficiency + 6 performance
                    val effCount = grouped[sortedClusters[0]]?.size ?: 0
                    val perfCount = grouped[sortedClusters[1]]?.size ?: 0
                    CpuTopology(
                        totalCores = total,
                        efficiencyCores = effCount,
                        performanceCores = perfCount,
                        primeCores = 0
                    )
                }
                3 -> {
                    // 3 clusters = efficiency + performance + prime
                    // e.g. 4+4+1 Tensor G3, or 4+3+1 Snapdragon
                    val effCount = grouped[sortedClusters[0]]?.size ?: 0
                    val perfCount = grouped[sortedClusters[1]]?.size ?: 0
                    val primeCount = grouped[sortedClusters[2]]?.size ?: 0
                    CpuTopology(
                        totalCores = total,
                        efficiencyCores = effCount,
                        performanceCores = perfCount,
                        primeCores = primeCount
                    )
                }
                else -> {
                    // >3 clusters: lowest is efficiency, highest is prime, middle are performance
                    val effCount = grouped[sortedClusters.first()]?.size ?: 0
                    val primeCount = grouped[sortedClusters.last()]?.size ?: 0
                    val perfCount = sortedClusters.subList(1, sortedClusters.size - 1)
                        .sumOf { grouped[it]?.size ?: 0 }
                    CpuTopology(
                        totalCores = total,
                        efficiencyCores = effCount,
                        performanceCores = perfCount,
                        primeCores = primeCount
                    )
                }
            }
        }
    }
}

private fun logDebug(msg: String, e: Throwable? = null) {
    runCatching {
        if (e != null) Log.d(LOG_TAG, msg, e) else Log.d(LOG_TAG, msg)
    }
}
