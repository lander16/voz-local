package dev.sebastian.vozlocal

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("VozLocalApp", "onTrimMemory received level: $level")
        // Under critical memory pressure, release the idle native model to avoid process kill
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!audioRecorder.isRecording()) {
                Log.i("VozLocalApp", "Critical memory pressure ($level). Releasing idle Whisper model to protect process.")
                applicationScope.launch {
                    runCatching {
                        repository.whisperEngine.release()
                        repository.updateModelLoadedState(false)
                    }
                }
            }
        }
    }
}
