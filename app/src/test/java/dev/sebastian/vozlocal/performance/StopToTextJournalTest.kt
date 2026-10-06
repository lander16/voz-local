package dev.sebastian.vozlocal.performance

import android.util.AtomicFile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StopToTextJournalTest {
    @After fun reset() { StopToTextJournal.resetForTest() }

    @Test fun startupProbeIsExplicitlySyntheticAndNotModelEvidence() {
        val app = RuntimeEnvironment.getApplication()
        StopToTextJournal.initialize(app, enabled = true)
        assertTrue(StopToTextJournal.flushForTest())

        val journal = File(app.getExternalFilesDir("diagnostics"), "stop-to-text-trace-v1.json")
        val records = JSONObject(journal.readText()).getJSONArray("records")
        val record = records.getJSONObject(records.length() - 1)
        assertEquals("synthetic_journal_probe", record.getString("record_kind"))
        assertTrue(record.getBoolean("synthetic"))
        assertEquals("unknown", record.getString("model_state"))
        assertFalse(journal.readText().contains("transcript"))
    }

    @Test fun disabledJournalDoesNotCreateTheDiagnosticsDirectoryOrPersistTrace() {
        val app = RuntimeEnvironment.getApplication()
        StopToTextJournal.resetForTest()
        StopToTextJournal.initialize(app, enabled = false)
        StopToTextJournal.enqueue(record())
        assertTrue(StopToTextJournal.flushForTest())
        assertFalse(File(app.getExternalFilesDir("diagnostics"), "stop-to-text-trace-v1.json").exists())
    }

    @Test fun appendRetainsOnlyNewest64RecordsAndMetadataHasNoSpeechField() {
        val file = AtomicFile(File(RuntimeEnvironment.getApplication().cacheDir, "journal-${UUID.randomUUID()}.json"))
        repeat(66) { index -> StopToTextJournal.appendCommitted(file, record(traceId = index.toLong())) }

        val text = file.baseFile.readText()
        val records = JSONObject(text).getJSONArray("records")
        assertEquals(64, records.length())
        assertEquals(2L, records.getJSONObject(0).getLong("trace_id"))
        assertEquals(65L, records.getJSONObject(63).getLong("trace_id"))
        assertFalse(text.contains("spoken words"))
        assertFalse(text.contains("transcript"))
        assertTrue(file.baseFile.length() <= 256 * 1024)
    }

    @Test fun fastConformerStageNamesSurviveJournalAllowlist() {
        val file = AtomicFile(File(RuntimeEnvironment.getApplication().cacheDir, "journal-fc-${UUID.randomUUID()}.json"))
        StopToTextJournal.appendCommitted(file, record().copy(
            modelId = "fastconformer_es_experimental",
            spans = listOf(
                TraceSpan("fastconformer_asset_verification", 1, 2),
                TraceSpan("fastconformer_native_init", 2, 3),
                TraceSpan("fastconformer_inference", 3, 4),
                TraceSpan("fastconformer_native_teardown", 4, 5),
            ),
        ))
        val spans = JSONObject(file.baseFile.readText()).getJSONArray("records")
            .getJSONObject(0).getJSONArray("spans")
        assertEquals(4, spans.length())
        assertEquals("fastconformer_native_init", spans.getJSONObject(1).getString("name"))
    }

    @Test fun invalidOrOversizedRecordDoesNotReplacePreviousJournal() {
        val file = AtomicFile(File(RuntimeEnvironment.getApplication().cacheDir, "journal-${UUID.randomUUID()}.json"))
        StopToTextJournal.appendCommitted(file, record(traceId = 7))
        val before = file.baseFile.readBytes()
        val oversized = record(traceId = 8).copy(
            spans = List(7_000) { TraceSpan("moonshine_inference", 0, 1) },
        )

        assertThrows(IllegalArgumentException::class.java) { StopToTextJournal.appendCommitted(file, oversized) }
        assertTrue(before.contentEquals(file.baseFile.readBytes()))
        StopToTextJournal.appendCommitted(file, record(traceId = 9))
        val recovered = JSONObject(file.baseFile.readText()).getJSONArray("records")
        assertEquals(2, recovered.length())
        assertEquals(9L, recovered.getJSONObject(1).getLong("trace_id"))
        assertThrows(IllegalArgumentException::class.java) {
            record().copy(source = "private transcript").toJson()
        }
    }

    private fun record(traceId: Long = 1) = StopTraceRecord(
        recordId = UUID.randomUUID().toString(),
        processRunId = UUID.randomUUID().toString(),
        wallTimeEpochMs = 1_800_000_000_000,
        elapsedRealtimeNanos = 42,
        traceId = traceId,
        source = "in_app",
        modelId = "moonshine_small_es",
        outcome = "app_result_published",
        stopToAppStatePublishedMs = 123,
        stopToOverlayActionAcceptedMs = null,
        finalizedAtMs = 150,
        spans = listOf(TraceSpan("moonshine_inference", 10, 90)),
        pointsMs = mapOf("in_app_result_state_published" to 123),
        metadata = mapOf("pcm_samples" to "16000", "pcm_duration_ms" to "1000", "pcm_sha256_f32le" to "a".repeat(64)),
    )
}
