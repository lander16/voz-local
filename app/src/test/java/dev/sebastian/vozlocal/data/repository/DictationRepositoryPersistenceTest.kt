package dev.sebastian.vozlocal.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the SharedPreferences-backed getters/setters of [DictationRepository].
 *
 * The repository constructor does heavier setup (Room, whisper engine, audio decoder),
 * but all of that is either lazy or fire-and-forget inside `init`; the prefs getters
 * and setters only touch the `vozlocal_prefs` SharedPreferences instance, so exercising
 * the real repository (approach (a)) is safe and fast under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DictationRepositoryPersistenceTest {

    private fun newRepository(): DictationRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DictationRepository(context)
    }

    @Test
    fun smartPunctuation_defaultsToTrue() {
        assertTrue(newRepository().getSmartPunctuation())
    }

    @Test
    fun smartPunctuation_roundTrips() {
        val repo = newRepository()
        repo.saveSmartPunctuation(false)
        assertFalse(repo.getSmartPunctuation())
        repo.saveSmartPunctuation(true)
        assertTrue(repo.getSmartPunctuation())
    }

    @Test
    fun autoCapitalization_defaultsToTrue() {
        assertTrue(newRepository().getAutoCapitalization())
    }

    @Test
    fun autoCapitalization_roundTrips() {
        val repo = newRepository()
        repo.saveAutoCapitalization(false)
        assertFalse(repo.getAutoCapitalization())
        repo.saveAutoCapitalization(true)
        assertTrue(repo.getAutoCapitalization())
    }

    @Test
    fun applyDictionary_defaultsToTrue() {
        assertTrue(newRepository().getApplyDictionary())
    }

    @Test
    fun applyDictionary_roundTrips() {
        val repo = newRepository()
        repo.saveApplyDictionary(false)
        assertFalse(repo.getApplyDictionary())
        repo.saveApplyDictionary(true)
        assertTrue(repo.getApplyDictionary())
    }

    @Test
    fun themeMode_defaultsToDark() {
        assertEquals("dark", newRepository().getThemeMode())
    }

    @Test
    fun themeMode_roundTrips() {
        val repo = newRepository()
        for (value in listOf("light", "dark", "system")) {
            repo.saveThemeMode(value)
            assertEquals(value, repo.getThemeMode())
        }
    }

    @Test
    fun getLanguage_defaultsToEs() {
        assertEquals("es", newRepository().getLanguage())
    }

    @Test
    fun getUseAiPolisher_defaultsToFalse() {
        assertFalse(newRepository().getUseAiPolisher())
    }

    @Test
    fun allPersistedAcrossNewRepositoryInstance() {
        val repo = newRepository()
        repo.saveSmartPunctuation(false)
        repo.saveAutoCapitalization(false)
        repo.saveApplyDictionary(false)
        repo.saveThemeMode("light")
        repo.saveLanguage("en")
        repo.saveUseAiPolisher(true)

        // A second repository against the same Context must read the same persisted prefs.
        val repo2 = newRepository()
        assertFalse(repo2.getSmartPunctuation())
        assertFalse(repo2.getAutoCapitalization())
        assertFalse(repo2.getApplyDictionary())
        assertEquals("light", repo2.getThemeMode())
        assertEquals("en", repo2.getLanguage())
        assertTrue(repo2.getUseAiPolisher())
    }
}
