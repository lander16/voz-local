package dev.sebastian.vozlocal.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class BenchmarkIntegrityTest {
    @Test fun absentEngineCannotProduceAPerfectSyntheticResult() = runBlocking {
        val config = TranscriptionBenchmarkConfig("whisper_tiny", "q8_0", 1, "es", 0, 0f, false, "pcm")
        try {
            BenchmarkRunner.run(config, FloatArray(16000), 1000, "clip", "reference")
            fail("Missing real inference must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
