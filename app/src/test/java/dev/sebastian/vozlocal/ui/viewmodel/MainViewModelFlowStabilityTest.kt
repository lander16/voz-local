package dev.sebastian.vozlocal.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.audio.AudioRecorder
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.data.repository.DictationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainViewModelFlowStabilityTest {

    private lateinit var repository: DictationRepository
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = DictationRepository(context)
        audioRecorder = AudioRecorder()
        viewModel = MainViewModel(repository, audioRecorder)
    }

    @Test
    fun downloadProgressFor_returnsIdenticalStateFlowInstanceForSameModel() {
        val flow1 = viewModel.downloadProgressFor("whisper-tiny")
        val flow2 = viewModel.downloadProgressFor("whisper-tiny")
        val flowOther = viewModel.downloadProgressFor("whisper-base")

        assertNotNull("flow1 must not be null", flow1)
        assertSame("Repeated calls for the same model must return the identical StateFlow instance", flow1, flow2)
        assertNotSame("Calls for different models must return distinct StateFlow instances", flow1, flowOther)

        val flowOther2 = viewModel.downloadProgressFor("whisper-base")
        assertSame("Repeated calls for the other model must return the memoized instance", flowOther, flowOther2)
    }

    @Test
    fun downloadStatusFor_returnsIdenticalStateFlowInstanceForSameModel() {
        val status1 = viewModel.downloadStatusFor("whisper-tiny")
        val status2 = viewModel.downloadStatusFor("whisper-tiny")
        val statusOther = viewModel.downloadStatusFor("whisper-base")

        assertNotNull("status1 must not be null", status1)
        assertSame("Repeated calls for the same model must return the identical StateFlow instance", status1, status2)
        assertNotSame("Calls for different models must return distinct StateFlow instances", status1, statusOther)

        val statusOther2 = viewModel.downloadStatusFor("whisper-base")
        assertSame("Repeated calls for the other model must return the memoized instance", statusOther, statusOther2)
    }

    @Test
    fun publicMaps_areExposedAsStateFlows() {
        assertNotNull(viewModel.downloadProgressMap)
        assertNotNull(viewModel.downloadUiStateMap)
        assertEquals(emptyMap<String, Float>(), viewModel.downloadProgressMap.value)
        assertEquals(emptyMap<String, ModelDownloadUiState>(), viewModel.downloadUiStateMap.value)
    }

    @Test
    fun waveformThrottling_suppressesEmissionsUnderThresholdAndEmitsWhenElapsed() {
        val baseTime = 100000L
        viewModel.resetWaveform(currentTimeMs = baseTime)

        val initialSnapshot = viewModel.liveWaveform.value
        assertEquals("Initial reset snapshot should have 25 elements", 25, initialSnapshot.size)
        assertTrue("All initial waveform values should be 0.05f", initialSnapshot.all { it == 0.05f })

        // Push at +10ms (< 33ms throttle limit): should NOT trigger new emission
        viewModel.pushWaveform(0.8f, currentTimeMs = baseTime + 10L)
        assertSame(
            "Emissions under 33ms must be throttled; snapshot instance must be identical",
            initialSnapshot,
            viewModel.liveWaveform.value
        )

        // Push at +20ms (< 33ms throttle limit): should still NOT trigger new emission
        viewModel.pushWaveform(0.85f, currentTimeMs = baseTime + 20L)
        assertSame(
            "Snapshot instance must remain identical when still within throttle window",
            initialSnapshot,
            viewModel.liveWaveform.value
        )

        // Push at +33ms (>= 33ms throttle limit): should trigger emission
        viewModel.pushWaveform(0.9f, currentTimeMs = baseTime + 33L)
        val emittedSnapshot = viewModel.liveWaveform.value
        assertNotSame(
            "New snapshot should be emitted once throttle threshold of 33ms is reached",
            initialSnapshot,
            emittedSnapshot
        )
        assertTrue("Emitted snapshot must contain the newest amplitude 0.9f", emittedSnapshot.contains(0.9f))

        // Push at +40ms (+7ms since last emit at +33ms): should NOT trigger new emission
        viewModel.pushWaveform(0.3f, currentTimeMs = baseTime + 40L)
        assertSame(
            "Emissions under 33ms from last emit must be throttled",
            emittedSnapshot,
            viewModel.liveWaveform.value
        )

        // Push at +66ms (>= 33ms since last emit at +33ms): should trigger emission
        viewModel.pushWaveform(0.4f, currentTimeMs = baseTime + 66L)
        val secondEmittedSnapshot = viewModel.liveWaveform.value
        assertNotSame(emittedSnapshot, secondEmittedSnapshot)
        assertTrue("Second emitted snapshot must contain the newest amplitude 0.4f", secondEmittedSnapshot.contains(0.4f))
    }

    @Test
    fun waveformThrottling_resetAndFinalSnapshotBypassThrottleWindow() {
        val baseTime = 200000L
        viewModel.resetWaveform(currentTimeMs = baseTime)
        val snap0 = viewModel.liveWaveform.value

        // Advance to +33ms and emit
        viewModel.pushWaveform(0.7f, currentTimeMs = baseTime + 33L)
        val snap1 = viewModel.liveWaveform.value
        assertNotSame(snap0, snap1)

        // Immediate next millisecond (+34ms): normally throttled
        viewModel.pushWaveform(0.8f, currentTimeMs = baseTime + 34L)
        assertSame("Within 1ms should be throttled", snap1, viewModel.liveWaveform.value)

        // But pushFinalWaveformSnapshot immediately emits the final snapshot
        viewModel.pushFinalWaveformSnapshot(currentTimeMs = baseTime + 35L)
        val snapFinal = viewModel.liveWaveform.value
        assertNotSame("Final snapshot must emit immediately without waiting for throttle window", snap1, snapFinal)
        assertTrue("Final snapshot must contain the newest amplitude 0.8f", snapFinal.contains(0.8f))

        // resetWaveform also immediately resets and emits
        viewModel.resetWaveform(currentTimeMs = baseTime + 36L)
        val snapReset = viewModel.liveWaveform.value
        assertNotSame("resetWaveform must emit immediately", snapFinal, snapReset)
        assertTrue("All reset waveform values should be 0.05f", snapReset.all { it == 0.05f })
    }

    @Test
    fun pruneHistoryToLimit_prunesOldRecordsAtomically() = runBlocking {
        repository.clearHistory()

        // Insert 5 history items with ascending timestamps
        for (i in 1..5) {
            repository.insertHistory(
                TranscriptionHistory(
                    text = "Transcription $i",
                    timestamp = 1000L * i,
                    durationSec = i,
                    modelUsed = "whisper-tiny",
                    type = "dictation"
                )
            )
        }

        val beforePruning = repository.allHistory.first()
        assertEquals(5, beforePruning.size)

        // Prune to limit = 3 (keep newest 3: items 5, 4, 3)
        repository.pruneHistory(3)

        val afterPruning = repository.allHistory.first()
        assertEquals(3, afterPruning.size)
        assertEquals(listOf("Transcription 5", "Transcription 4", "Transcription 3"), afterPruning.map { it.text })
    }
}
