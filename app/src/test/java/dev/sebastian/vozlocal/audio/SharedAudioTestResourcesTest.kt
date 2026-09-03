package dev.sebastian.vozlocal.audio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAudioTestResourcesTest {

    @Test
    fun verifyTestAudioResourceExistsAndIsNonEmpty() {
        val stream = javaClass.classLoader?.getResourceAsStream("test_audio_2min.ogg")
        assertNotNull("test_audio_2min.ogg must be present in test resources", stream)
        val bytes = stream?.readBytes() ?: ByteArray(0)
        assertTrue("Test audio should be ~235 KB, actual size: ${bytes.size}", bytes.size > 200_000)
    }
}
