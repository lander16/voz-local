package dev.sebastian.vozlocal.whisper

import com.whispercpp.whisper.CpuInfoProvider
import com.whispercpp.whisper.CpuTopology
import com.whispercpp.whisper.DefaultCpuInfoProvider
import com.whispercpp.whisper.FileThreadProfileStore
import com.whispercpp.whisper.ThreadCalibrationProfile
import com.whispercpp.whisper.ThreadProfileManager
import com.whispercpp.whisper.WhisperCpuConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCpuConfigTest {

    @Test fun explicitThreadsWinAndProfilesRequireFullIdentity() {
        WhisperCpuConfig.profileManager = ThreadProfileManager()
        WhisperCpuConfig.profileManager!!.saveProfile(ThreadCalibrationProfile(
            WhisperCpuConfig.deviceId, "full-runtime-identity", 1, 1L))
        System.setProperty("vozlocal.whisper.threads", "2")
        assertEquals(1, WhisperCpuConfig.threadCountFor(WhisperParams(threadCountOverride = 1)))
        System.clearProperty("vozlocal.whisper.threads")
        assertEquals(1, WhisperCpuConfig.threadCountFor(WhisperParams(calibrationKey = "full-runtime-identity")))
        assertNull(WhisperCpuConfig.profileManager!!.getProfile(WhisperCpuConfig.deviceId, "different-runtime"))
    }

    @Test fun persistenceFailureDoesNotActivateUnsavedProfile() {
        val store = object : com.whispercpp.whisper.ThreadProfileStore {
            override fun load() = emptyList<ThreadCalibrationProfile>()
            override fun clear() = Unit
            override fun save(profiles: List<ThreadCalibrationProfile>) { error("disk full") }
        }
        val manager = ThreadProfileManager(store)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            manager.saveProfile(ThreadCalibrationProfile("device", "key", 1, 1L))
        }
        assertNull(manager.getProfile("device", "key"))
    }

    private val threadProperty = "vozlocal.whisper.threads"
    private val priorityProperty = "vozlocal.whisper.thread_priority"

    @After
    fun tearDown() {
        System.clearProperty(threadProperty)
        System.clearProperty(priorityProperty)
        WhisperCpuConfig.resetCpuInfoProvider()
        WhisperCpuConfig.resetThreadPriority()
        WhisperCpuConfig.profileManager = ThreadProfileManager()
        WhisperCpuConfig.deviceId = "default_device"
    }

    @Test
    fun adaptiveThreadCount_scales_with_cpu_cores() {
        // 9-core Google Tensor G3 (Pixel 8 Pro: 1x X3, 4x A715, 4x A510)
        assertEquals(5, WhisperCpuConfig.adaptiveThreadCount(available = 9, highPerf = 5))
        assertEquals(6, WhisperCpuConfig.maxThreadCap(available = 9))

        // 8-core modern mobile SoC (e.g. Snapdragon 8 Gen 2/3, Dimensity, Tensor G1/G2)
        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount(available = 8, highPerf = 4))
        assertEquals(5, WhisperCpuConfig.adaptiveThreadCount(available = 8, highPerf = 6))
        assertEquals(5, WhisperCpuConfig.maxThreadCap(available = 8))

        // 6-core midrange SoC
        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount(available = 6, highPerf = 4))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 6))

        // 4-core low-end SoC
        assertEquals(2, WhisperCpuConfig.adaptiveThreadCount(available = 4, highPerf = 2))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 4))

        // 2-core / single-core devices
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount(available = 2, highPerf = 1))
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount(available = 1, highPerf = 1))
        assertEquals(4, WhisperCpuConfig.maxThreadCap(available = 2))
    }

    @Test
    fun threadAllocation_on_9_core_tensor_g3_allocates_expected_threads() {
        val base = 5
        val maxCap = 6

        // Base and Small models allow up to 5 threads when maxCap >= 5
        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        val smallParams = WhisperParams(modelIdHint = "whisper_small")
        assertEquals(5, WhisperCpuConfig.threadCountFor(baseParams, base = base, maxCap = maxCap))
        assertEquals(5, WhisperCpuConfig.threadCountFor(smallParams, base = base, maxCap = maxCap))

        // Tiny models allow up to 3 threads
        val tinyParams = WhisperParams(modelIdHint = "whisper_tiny")
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams, base = base, maxCap = maxCap))

        // Medium and Large models use (base + 1).coerceAtMost(maxCap) -> 6 threads
        val mediumParams = WhisperParams(modelIdHint = "whisper_medium")
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(6, WhisperCpuConfig.threadCountFor(mediumParams, base = base, maxCap = maxCap))
        assertEquals(6, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))

        // Unspecified / default hint falls back to base threads
        val defaultParams = WhisperParams(modelIdHint = null)
        assertEquals(5, WhisperCpuConfig.threadCountFor(defaultParams, base = base, maxCap = maxCap))
    }

    @Test
    fun threadAllocation_on_8_core_soc_allocates_expected_threads() {
        val base = 4
        val maxCap = 5

        // Base & Small models allow up to 4 threads (capped by base = 4)
        val baseParams1 = WhisperParams(modelIdHint = "whisper_base")
        val baseParams2 = WhisperParams(modelIdHint = "base")
        val smallParams = WhisperParams(modelIdHint = "whisper_small")
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams1, base = base, maxCap = maxCap))
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams2, base = base, maxCap = maxCap))
        assertEquals(4, WhisperCpuConfig.threadCountFor(smallParams, base = base, maxCap = maxCap))

        // Tiny models allow up to 3 threads (expanded from previous cap of 2)
        val tinyParams1 = WhisperParams(modelIdHint = "whisper_tiny")
        val tinyParams2 = WhisperParams(modelIdHint = "tiny")
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams1, base = base, maxCap = maxCap))
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams2, base = base, maxCap = maxCap))

        // Medium and Large models use (base + 1).coerceAtMost(maxThreadCap) -> 5 threads
        val mediumParams = WhisperParams(modelIdHint = "whisper_medium")
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(5, WhisperCpuConfig.threadCountFor(mediumParams, base = base, maxCap = maxCap))
        assertEquals(5, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))

        // Unspecified / default hint falls back to base threads
        val defaultParams = WhisperParams(modelIdHint = null)
        assertEquals(4, WhisperCpuConfig.threadCountFor(defaultParams, base = base, maxCap = maxCap))
    }

    @Test
    fun threadAllocation_on_4_core_soc_respects_headroom() {
        val base = 2
        val maxCap = 4

        // Base and Small models capped at base (2 <= 4)
        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        val smallParams = WhisperParams(modelIdHint = "whisper_small")
        assertEquals(2, WhisperCpuConfig.threadCountFor(baseParams, base = base, maxCap = maxCap))
        assertEquals(2, WhisperCpuConfig.threadCountFor(smallParams, base = base, maxCap = maxCap))

        // Tiny models capped at base (2 <= 3)
        val tinyParams = WhisperParams(modelIdHint = "whisper_tiny")
        assertEquals(2, WhisperCpuConfig.threadCountFor(tinyParams, base = base, maxCap = maxCap))

        // Medium and Large models get base + 1 = 3
        val mediumParams = WhisperParams(modelIdHint = "whisper_medium")
        val largeParams = WhisperParams(modelIdHint = "whisper_large")
        assertEquals(3, WhisperCpuConfig.threadCountFor(mediumParams, base = base, maxCap = maxCap))
        assertEquals(3, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))
    }

    @Test
    fun threadAllocation_on_6_core_soc() {
        val base = 4
        val maxCap = 4

        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        val smallParams = WhisperParams(modelIdHint = "whisper_small")
        assertEquals(4, WhisperCpuConfig.threadCountFor(baseParams, base = base, maxCap = maxCap))
        assertEquals(4, WhisperCpuConfig.threadCountFor(smallParams, base = base, maxCap = maxCap))

        val tinyParams = WhisperParams(modelIdHint = "whisper_tiny")
        assertEquals(3, WhisperCpuConfig.threadCountFor(tinyParams, base = base, maxCap = maxCap))

        // Medium/large capped at maxCap = 4
        val largeParams = WhisperParams(modelIdHint = "large")
        assertEquals(4, WhisperCpuConfig.threadCountFor(largeParams, base = base, maxCap = maxCap))
    }

    @Test
    fun system_property_override_takes_precedence() {
        System.setProperty(threadProperty, "2")
        val params = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(2, WhisperCpuConfig.threadCountFor(params))
    }

    @Test
    fun live_threadCountFor_returns_positive_valid_threads() {
        val baseParams = WhisperParams(modelIdHint = "whisper_base")
        val count = WhisperCpuConfig.threadCountFor(baseParams)
        assertTrue("Thread count should be >= 1", count >= 1)
    }

    @Test
    fun topologyDetection_2_cluster_2_plus_6_architecture() {
        // 2 efficiency cores @ 1.8 GHz, 6 performance cores @ 2.4 GHz
        val freqs = listOf(1800000, 1800000, 2400000, 2400000, 2400000, 2400000, 2400000, 2400000)
        val topology = DefaultCpuInfoProvider.computeTopology(availableProcessors = 8, frequencies = freqs)

        assertEquals(8, topology.totalCores)
        assertEquals(2, topology.efficiencyCores)
        assertEquals(6, topology.performanceCores)
        assertEquals(0, topology.primeCores)
        assertEquals(6, topology.highPerfCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 8
            override fun getCoreFrequencies(): List<Int> = freqs
            override fun getCpuTopology(): CpuTopology = topology
        }

        // On 2+6, 6 performance cores are recognized as performance cores (not dropped)
        assertEquals(5, WhisperCpuConfig.adaptiveThreadCount())
        assertEquals(5, WhisperCpuConfig.preferredThreadCount)
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(5, WhisperCpuConfig.threadCountFor(largeParams))
    }

    @Test
    fun topologyDetection_3_cluster_tensor_g3_architecture() {
        // 1 prime @ 2.91 GHz, 4 performance @ 2.37 GHz, 4 efficiency @ 1.70 GHz
        val freqs = listOf(
            1700000, 1700000, 1700000, 1700000,
            2370000, 2370000, 2370000, 2370000,
            2910000
        )
        val topology = DefaultCpuInfoProvider.computeTopology(availableProcessors = 9, frequencies = freqs)

        assertEquals(9, topology.totalCores)
        assertEquals(4, topology.efficiencyCores)
        assertEquals(4, topology.performanceCores)
        assertEquals(1, topology.primeCores)
        assertEquals(5, topology.highPerfCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 9
            override fun getCoreFrequencies(): List<Int> = freqs
            override fun getCpuTopology(): CpuTopology = topology
        }

        assertEquals(5, WhisperCpuConfig.adaptiveThreadCount())
        assertEquals(5, WhisperCpuConfig.preferredThreadCount)
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(6, WhisperCpuConfig.threadCountFor(largeParams))
    }

    @Test
    fun topologyDetection_3_cluster_snapdragon_architecture() {
        // 1 prime @ 3.2 GHz, 3 performance @ 2.8 GHz, 4 efficiency @ 1.8 GHz
        val freqs = listOf(
            1800000, 1800000, 1800000, 1800000,
            2800000, 2800000, 2800000,
            3200000
        )
        val topology = DefaultCpuInfoProvider.computeTopology(availableProcessors = 8, frequencies = freqs)

        assertEquals(8, topology.totalCores)
        assertEquals(4, topology.efficiencyCores)
        assertEquals(3, topology.performanceCores)
        assertEquals(1, topology.primeCores)
        assertEquals(4, topology.highPerfCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 8
            override fun getCoreFrequencies(): List<Int> = freqs
            override fun getCpuTopology(): CpuTopology = topology
        }

        assertEquals(4, WhisperCpuConfig.adaptiveThreadCount())
        val largeParams = WhisperParams(modelIdHint = "whisper_large_v3_turbo")
        assertEquals(5, WhisperCpuConfig.threadCountFor(largeParams))
    }

    @Test
    fun topologyDetection_and_clamping_for_small_machines() {
        // 4-core homogeneous machine
        val freqs4 = listOf(2400000, 2400000, 2400000, 2400000)
        val top4 = DefaultCpuInfoProvider.computeTopology(availableProcessors = 4, frequencies = freqs4)
        assertEquals(4, top4.totalCores)
        assertEquals(0, top4.efficiencyCores)
        assertEquals(4, top4.performanceCores)
        assertEquals(0, top4.primeCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 4
            override fun getCoreFrequencies(): List<Int> = freqs4
            override fun getCpuTopology(): CpuTopology = top4
        }
        assertEquals(2, WhisperCpuConfig.adaptiveThreadCount())
        assertEquals(3, WhisperCpuConfig.threadCountFor(WhisperParams(modelIdHint = "whisper_large")))

        // 2-core machine: threads must be clamped to availableProcessors = 2
        val freqs2 = listOf(2000000, 2000000)
        val top2 = DefaultCpuInfoProvider.computeTopology(availableProcessors = 2, frequencies = freqs2)
        assertEquals(2, top2.totalCores)
        assertEquals(0, top2.efficiencyCores)
        assertEquals(2, top2.performanceCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 2
            override fun getCoreFrequencies(): List<Int> = freqs2
            override fun getCpuTopology(): CpuTopology = top2
        }
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount())
        assertEquals(2, WhisperCpuConfig.threadCountFor(WhisperParams(modelIdHint = "whisper_large")))

        // Single-core machine: threads must be clamped to availableProcessors = 1
        val freqs1 = listOf(1600000)
        val top1 = DefaultCpuInfoProvider.computeTopology(availableProcessors = 1, frequencies = freqs1)
        assertEquals(1, top1.totalCores)
        assertEquals(0, top1.efficiencyCores)
        assertEquals(1, top1.performanceCores)

        WhisperCpuConfig.cpuInfoProvider = object : CpuInfoProvider {
            override fun getAvailableProcessors(): Int = 1
            override fun getCoreFrequencies(): List<Int> = freqs1
            override fun getCpuTopology(): CpuTopology = top1
        }
        assertEquals(1, WhisperCpuConfig.adaptiveThreadCount())
        // Even with large model hint (which attempts base + 1), must never exceed availableProcessors
        assertEquals(1, WhisperCpuConfig.threadCountFor(WhisperParams(modelIdHint = "whisper_large")))
    }

    @Test
    fun fallback_when_sysfs_is_denied() {
        val nonExistentSysfs = File("/non/existent/sysfs/cpu")
        val nonExistentProc = File("/non/existent/proc/cpuinfo")
        val provider = DefaultCpuInfoProvider(nonExistentSysfs, nonExistentProc)

        assertTrue(provider.getCoreFrequencies().isEmpty())
        val topology = provider.getCpuTopology()
        assertTrue(topology.totalCores >= 1)
        assertTrue(topology.performanceCores >= 1)

        // Test fallback topology logic directly for 8-core
        val top8Fallback = DefaultCpuInfoProvider.computeTopology(availableProcessors = 8, frequencies = emptyList())
        assertEquals(8, top8Fallback.totalCores)
        assertEquals(4, top8Fallback.efficiencyCores)
        assertEquals(4, top8Fallback.performanceCores)
        assertEquals(0, top8Fallback.primeCores)

        // Test fallback topology logic for 6-core
        val top6Fallback = DefaultCpuInfoProvider.computeTopology(availableProcessors = 6, frequencies = emptyList())
        assertEquals(6, top6Fallback.totalCores)
        assertEquals(2, top6Fallback.efficiencyCores)
        assertEquals(4, top6Fallback.performanceCores)

        // Test fallback topology logic for 4-core
        val top4Fallback = DefaultCpuInfoProvider.computeTopology(availableProcessors = 4, frequencies = emptyList())
        assertEquals(4, top4Fallback.totalCores)
        assertEquals(0, top4Fallback.efficiencyCores)
        assertEquals(4, top4Fallback.performanceCores)
    }

    @Test
    fun threadCalibrationProfile_lookup_and_invalidation() {
        val tempFile = File.createTempFile("whisper_profiles_", ".tsv")
        tempFile.deleteOnExit()
        val store = FileThreadProfileStore(tempFile)
        val manager = ThreadProfileManager(store = store, currentNativeBuildId = "build_v1")

        val profile = ThreadCalibrationProfile(
            deviceId = "pixel8",
            modelId = "whisper_base",
            optimalThreads = 4,
            calibratedAtMs = 1700000000000L,
            nativeBuildId = "build_v1"
        )
        manager.saveProfile(profile)

        // Lookup profile and optimal threads
        val loaded = manager.getProfile("pixel8", "whisper_base")
        assertNotNull(loaded)
        assertEquals(4, loaded?.optimalThreads)
        assertEquals(4, manager.getOptimalThreads("pixel8", "whisper_base"))

        // Lookup with WhisperCpuConfig
        WhisperCpuConfig.profileManager = manager
        WhisperCpuConfig.deviceId = "pixel8"
        val params = WhisperParams(modelIdHint = "whisper_base")
        assertEquals(4, WhisperCpuConfig.threadCountFor(params))

        // Invalidate by model
        manager.invalidateModel("whisper_base")
        assertNull(manager.getOptimalThreads("pixel8", "whisper_base"))
        assertNull(manager.getProfile("pixel8", "whisper_base"))

        // Re-save profile
        manager.saveProfile(profile)
        assertEquals(4, manager.getOptimalThreads("pixel8", "whisper_base"))

        // Invalidate when native build changes
        manager.invalidateNativeBuild("build_v2")
        assertNull(manager.getOptimalThreads("pixel8", "whisper_base"))

        // Re-loading from file store with the new build ID discards stale profile
        val managerNewBuild = ThreadProfileManager(store = store, currentNativeBuildId = "build_v2")
        assertNull(managerNewBuild.getOptimalThreads("pixel8", "whisper_base"))

        // Saving under new build ID succeeds
        val profileV2 = ThreadCalibrationProfile(
            deviceId = "pixel8",
            modelId = "whisper_base",
            optimalThreads = 3,
            calibratedAtMs = 1700000100000L,
            nativeBuildId = "build_v2"
        )
        managerNewBuild.saveProfile(profileV2)
        assertEquals(3, managerNewBuild.getOptimalThreads("pixel8", "whisper_base"))
    }

    @Test
    fun threadPriority_configuration_and_override() {
        WhisperCpuConfig.resetThreadPriority()
        assertEquals(WhisperCpuConfig.DEFAULT_THREAD_PRIORITY, WhisperCpuConfig.threadPriority)

        WhisperCpuConfig.threadPriority = -4
        assertEquals(-4, WhisperCpuConfig.threadPriority)

        System.setProperty(priorityProperty, "-2")
        WhisperCpuConfig.resetThreadPriority()
        assertEquals(-2, WhisperCpuConfig.threadPriority)

        System.clearProperty(priorityProperty)
        WhisperCpuConfig.resetThreadPriority()
        assertEquals(WhisperCpuConfig.DEFAULT_THREAD_PRIORITY, WhisperCpuConfig.threadPriority)
    }
}
