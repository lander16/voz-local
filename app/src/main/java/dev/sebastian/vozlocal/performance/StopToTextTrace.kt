package dev.sebastian.vozlocal.performance

import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

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
        logger("id=$id source=$source model=$modelId outcome=$outcome stop_to_app_state_published=${appReadyMs}ms stop_to_overlay_action_accepted=${overlayAcceptedMs}ms finalized_at=${elapsed / 1_000_000}ms metadata=[$metadataFields] spans=[$spanFields] points=[$pointFields]")
    }

    companion object {
        private const val TAG = "StopToTextTrace"
        private val nextId = AtomicLong()

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
