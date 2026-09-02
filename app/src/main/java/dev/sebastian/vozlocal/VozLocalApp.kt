package dev.sebastian.vozlocal

import android.app.Application
import dev.sebastian.vozlocal.audio.AudioRecorder
import dev.sebastian.vozlocal.data.repository.DictationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VozLocalApp : Application() {
    lateinit var repository: DictationRepository
        private set

    /**
     * Process-wide recorder shared by the in-app ViewModel and the
     * global accessibility-service floating overlay. Serialized internally
     * with a Mutex so the two cannot open `AudioRecord` on the same mic.
     */
    val audioRecorder: AudioRecorder = AudioRecorder()

    /**
     * Long-lived scope for repository background work that must outlive any
     * individual ViewModel (e.g. model metadata sync on first launch).
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = DictationRepository(this)
        applicationScope.launch {
            runCatching { repository.initializeModels() }
        }
        // Model downloads are always user-initiated. Preloading only reads a model
        // already stored locally and never opens a network connection.
        applicationScope.launch {
            runCatching { repository.preloadModel() }
        }
    }
}
