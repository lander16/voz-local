package dev.sebastian.vozlocal.performance

import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Opt-in, text-free monotonic trace for one microphone Stop-to-delivery attempt. */
internal class StopToTextTrace private constructor(
    val id: Long,
    private val source: String,
    private val startedAtNanos: Long,
    private val nowNanos: () -> Long,
    private val logger: (String) -> Unit,
) {
    internal data class SpanToken(val name: String, val startedAtNanos: Long)
    internal data class Span(val name: String, val startOffsetNanos: Long, val endOffsetNanos: Long)

    private val spans = mutableListOf<Span>()
    private val points = linkedMapOf<String, Long>()
    private val metadata = linkedMapOf<String, String>()
    private var finished = false

    @Synchronized
    fun begin(name: String): SpanToken = SpanToken(name, nowNanos())

    @Synchronized
    fun end(token: SpanToken?) {
        if (token == null || finished) return
        val endedAt = nowNanos()
        spans += Span(
            token.name,
            (token.startedAtNanos - startedAtNanos).coerceAtLeast(0),
            (endedAt - startedAtNanos).coerceAtLeast(0),
        )
    }

    @Synchronized
    fun point(name: String) {
        if (!finished) points[name] = (nowNanos() - startedAtNanos).coerceAtLeast(0)
    }

    /** Hashes canonical little-endian float32 PCM after the delivery endpoint; audio is never stored. */
    @Synchronized
    fun recordPcmIdentity(samples: FloatArray) {
        if (finished) return
        metadata["pcm_samples"] = samples.size.toString()
        metadata["pcm_duration_ms"] = (samples.size * 1_000L / 16_000L).toString()
        val hash = begin("pcm_sha256_f32le")
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(32 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            if (bytes.remaining() < Float.SIZE_BYTES) {
                digest.update(bytes.array(), 0, bytes.position())
                bytes.clear()
            }
            bytes.putFloat(sample)
        }
        if (bytes.position() > 0) digest.update(bytes.array(), 0, bytes.position())
        val hex = "0123456789abcdef"
        metadata["pcm_sha256_f32le"] = buildString(64) {
            digest.digest().forEach {
                val unsigned = it.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
        end(hash)
    }

    @Synchronized
    fun finish(modelId: String, outcome: String) {
        if (finished) return
        finished = true
        val elapsed = (nowNanos() - startedAtNanos).coerceAtLeast(0)
        val spanFields = spans.joinToString(",") {
            "${it.name}:${it.startOffsetNanos / 1_000_000}-${it.endOffsetNanos / 1_000_000}ms"
        }
        val pointFields = points.entries.joinToString(",") {
            "${it.key}:${it.value / 1_000_000}ms"
        }
        val metadataFields = metadata.entries.joinToString(",") { "${it.key}:${it.value}" }
        // Absolute offsets retain overlap information; stage durations must not be added.
        val appReadyMs = points["in_app_result_state_published"]?.div(1_000_000L)?.toString() ?: "unavailable"
        val overlayAcceptedMs = points["accessibility_action_accepted"]?.div(1_000_000L)?.toString() ?: "unavailable"
        val safeSpans = spans.filter { it.name in JOURNAL_SPANS }.map {
            TraceSpan(it.name, it.startOffsetNanos, it.endOffsetNanos)
        }
        val safePoints = points.filterKeys { it in JOURNAL_POINTS }
        val safeMetadata = metadata.filterKeys { it in JOURNAL_METADATA }
        val record = StopTraceRecord(
            recordId = UUID.randomUUID().toString(),
            processRunId = StopToTextJournal.processRunId,
            wallTimeEpochMs = System.currentTimeMillis(),
            elapsedRealtimeNanos = startedAtNanos,
            traceId = id,
            source = source,
            modelId = modelId,
            outcome = outcome,
            stopToAppStatePublishedMs = points["in_app_result_state_published"]?.div(1_000_000L),
            stopToOverlayActionAcceptedMs = points["accessibility_action_accepted"]?.div(1_000_000L),
            finalizedAtMs = elapsed / 1_000_000,
            spans = safeSpans,
            pointsMs = safePoints.mapValues { it.value / 1_000_000L },
            metadata = safeMetadata,
        )
        logger("id=$id source=$source model=$modelId outcome=$outcome stop_to_app_state_published=${appReadyMs}ms stop_to_overlay_action_accepted=${overlayAcceptedMs}ms finalized_at=${elapsed / 1_000_000}ms metadata=[$metadataFields] spans=[$spanFields] points=[$pointFields]")
        StopToTextJournal.enqueue(record)
    }

    companion object {
        private const val TAG = "StopToTextTrace"
        private val nextId = AtomicLong()
        private val JOURNAL_SPANS = setOf(
            "moonshine_pcm_request_copy", "moonshine_coroutine_dispatch", "moonshine_native_worker_queue",
            "moonshine_inference", "moonshine_pcm_clear", "moonshine_adapter_create", "moonshine_model_load",
            "moonshine_native_teardown", "recorder_stop_total", "text_postprocess", "history_and_stats_persistence",
            "overlay_delivery_and_insertion", "recorder_hardware_release", "recorder_reader_drain",
            "recorder_pcm_snapshot_copy", "recorder_buffer_reset_and_shrink", "audio_silence_trim",
            "model_operation_lock_wait", "engine_operation_lock_wait", "moonshine_asset_verification",
            "whisper_engine_release", "pcm_sha256_f32le",
        )
        private val JOURNAL_POINTS = setOf(
            "stop_received", "transcript_available", "processed_text_ready", "accessibility_action_accepted",
            "accessibility_action_rejected", "app_text_ready_for_delivery", "in_app_result_state_published",
            "moonshine_resident_reused",
        )
        private val JOURNAL_METADATA = setOf("pcm_samples", "pcm_duration_ms", "pcm_sha256_f32le")

        fun start(source: String): StopToTextTrace = StopToTextTrace(
            id = nextId.incrementAndGet(),
            source = source,
            startedAtNanos = SystemClock.elapsedRealtimeNanos(),
            nowNanos = SystemClock::elapsedRealtimeNanos,
            logger = { Log.i(TAG, it) },
        )

        internal fun forTest(
            nowNanos: () -> Long,
            logger: (String) -> Unit,
            source: String = "test",
        ): StopToTextTrace = StopToTextTrace(nextId.incrementAndGet(), source, nowNanos(), nowNanos, logger)
    }
}
