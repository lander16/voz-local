package dev.sebastian.vozlocal.performance

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class TraceSpan(val name: String, val startOffsetNanos: Long, val endOffsetNanos: Long)

/** Explicitly allowlisted, text-free timing record. */
internal data class StopTraceRecord(
    val recordId: String,
    val processRunId: String,
    val wallTimeEpochMs: Long,
    val elapsedRealtimeNanos: Long,
    val traceId: Long,
    val source: String,
    val modelId: String,
    val outcome: String,
    val stopToAppStatePublishedMs: Long?,
    val stopToOverlayActionAcceptedMs: Long?,
    val finalizedAtMs: Long,
    val spans: List<TraceSpan>,
    val pointsMs: Map<String, Long>,
    val metadata: Map<String, String>,
    val synthetic: Boolean = false,
    val recordKind: String = "stop_to_text",
) {
    fun toJson(): JSONObject {
        require(recordId.matches(UUID_PATTERN) && processRunId.matches(UUID_PATTERN))
        require(SAFE_LABEL.matches(source) && SAFE_LABEL.matches(modelId) && SAFE_LABEL.matches(outcome))
        require(spans.all { it.name in ALLOWED_SPANS && it.startOffsetNanos >= 0 && it.endOffsetNanos >= it.startOffsetNanos })
        require(pointsMs.keys.all { it in ALLOWED_POINTS } && pointsMs.values.all { it >= 0 })
        require(metadata.keys.all { it in ALLOWED_METADATA })
        require(metadata.all { (key, value) ->
            when (key) {
                "pcm_sha256_f32le" -> value.matches(Regex("[0-9a-f]{64}"))
                else -> value.matches(Regex("[0-9]{1,12}"))
            }
        })
        return JSONObject()
        .put("record_id", recordId)
        .put("process_run_id", processRunId)
        .put("wall_time_epoch_ms", wallTimeEpochMs)
        .put("elapsed_realtime_ns", elapsedRealtimeNanos)
        .put("trace_id", traceId)
        .put("record_kind", recordKind)
        .put("synthetic", synthetic)
        .put("source", source)
        .put("model_id", modelId)
        .put("model_state", if (synthetic) "unknown" else JSONObject.NULL)
        .put("outcome", outcome)
        .put("stop_to_app_state_published_ms", stopToAppStatePublishedMs ?: JSONObject.NULL)
        .put("stop_to_overlay_action_accepted_ms", stopToOverlayActionAcceptedMs ?: JSONObject.NULL)
        .put("finalized_at_ms", finalizedAtMs)
        .put("spans", JSONArray().also { array ->
            spans.forEach { span ->
                array.put(JSONObject().put("name", span.name)
                    .put("start_offset_ns", span.startOffsetNanos)
                    .put("end_offset_ns", span.endOffsetNanos))
            }
        })
        .put("points_ms", JSONObject().also { obj -> pointsMs.forEach { (key, value) -> obj.put(key, value) } })
        .put("metadata", JSONObject().also { obj -> metadata.forEach { (key, value) -> obj.put(key, value) } })
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val SAFE_LABEL = Regex("[A-Za-z0-9_.-]{1,80}")
        private val ALLOWED_SPANS = setOf(
            "moonshine_pcm_request_copy", "moonshine_coroutine_dispatch", "moonshine_native_worker_queue",
            "moonshine_inference", "moonshine_pcm_clear", "moonshine_adapter_create", "moonshine_model_load",
            "moonshine_native_teardown", "recorder_stop_total", "text_postprocess", "history_and_stats_persistence",
            "overlay_delivery_and_insertion", "recorder_hardware_release", "recorder_reader_drain",
            "recorder_pcm_snapshot_copy", "recorder_buffer_reset_and_shrink", "audio_silence_trim",
            "model_operation_lock_wait", "engine_operation_lock_wait", "moonshine_asset_verification",
            "whisper_engine_release", "pcm_sha256_f32le",
        )
        private val ALLOWED_POINTS = setOf(
            "stop_received", "transcript_available", "processed_text_ready", "accessibility_action_accepted",
            "accessibility_action_rejected", "app_text_ready_for_delivery", "in_app_result_state_published",
            "moonshine_resident_reused",
        )
        private val ALLOWED_METADATA = setOf("pcm_samples", "pcm_duration_ms", "pcm_sha256_f32le")
    }
}

/** Opt-in metadata journal. Its only destination is app-scoped external files; no public fallback. */
internal class StopToTextJournal private constructor(private val file: AtomicFile) {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "stop-trace-journal").apply { isDaemon = true }
    }

    fun append(record: StopTraceRecord) {
        writer.execute {
            runCatching { appendCommitted(file, record) }
                .onSuccess { Log.i(TAG, "persisted record_id=${record.recordId} kind=${record.recordKind}") }
                .onFailure { Log.e(TAG, "journal write failed record_id=${record.recordId} kind=${record.recordKind}", it) }
        }
    }

    internal fun awaitIdleForTest(timeoutMs: Long = 5_000): Boolean {
        writer.shutdown()
        return writer.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "StopToTextJournal"
        private const val MAX_RECORDS = 64
        private const val MAX_BYTES = 256 * 1024
        private const val VERSION = 1
        @Volatile private var active: StopToTextJournal? = null
        @Volatile var processRunId: String = UUID.randomUUID().toString()
            private set

        fun initialize(context: Context, enabled: Boolean) {
            if (!enabled) return
            val directory = context.getExternalFilesDir("diagnostics") ?: run {
                Log.e(TAG, "journal unavailable: app-scoped external files directory missing")
                return
            }
            if (!directory.exists() && !directory.mkdirs()) {
                Log.e(TAG, "journal unavailable: app-scoped diagnostics directory could not be created")
                return
            }
            processRunId = UUID.randomUUID().toString()
            val journal = StopToTextJournal(AtomicFile(File(directory, "stop-to-text-trace-v1.json")))
            active = journal
            journal.append(StopTraceRecord(
                recordId = UUID.randomUUID().toString(),
                processRunId = processRunId,
                wallTimeEpochMs = System.currentTimeMillis(),
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                traceId = 0,
                source = "diagnostic_startup",
                modelId = "unknown",
                outcome = "journal_probe",
                stopToAppStatePublishedMs = null,
                stopToOverlayActionAcceptedMs = null,
                finalizedAtMs = 0,
                spans = emptyList(),
                pointsMs = emptyMap(),
                metadata = emptyMap(),
                synthetic = true,
                recordKind = "synthetic_journal_probe",
            ))
        }

        fun enqueue(record: StopTraceRecord) { active?.append(record) }

        internal fun resetForTest() { active = null }
        internal fun flushForTest(): Boolean = active?.awaitIdleForTest() ?: true

        internal fun appendCommitted(file: AtomicFile, record: StopTraceRecord) {
            val oldText = if (file.baseFile.exists()) file.readFully().toString(Charsets.UTF_8) else null
            val records = oldText?.let(::parseRecords)?.toMutableList() ?: mutableListOf()
            records += record.toJson()
            while (records.size > MAX_RECORDS) records.removeAt(0)
            var payload = encode(records)
            while (payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES && records.size > 1) {
                records.removeAt(0)
                payload = encode(records)
            }
            require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "single trace record exceeds journal limit" }
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (failure: Throwable) {
                stream?.let(file::failWrite)
                throw failure
            }
        }

        private fun parseRecords(text: String): MutableList<JSONObject> {
            val root = JSONObject(text)
            require(root.optInt("schema_version") == VERSION) { "unsupported trace journal schema" }
            val array = root.getJSONArray("records")
            return MutableList(array.length()) { array.getJSONObject(it) }
        }

        private fun encode(records: List<JSONObject>): String = JSONObject()
            .put("schema_version", VERSION)
            .put("records", JSONArray(records))
            .toString()
    }
}
