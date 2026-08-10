package dev.sebastian.vozlocal.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sebastian.vozlocal.audio.AudioRecorder
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.data.model.DictationStat
import dev.sebastian.vozlocal.data.model.DictionaryWord
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.data.repository.DictationRepository
import dev.sebastian.vozlocal.data.repository.VadDownloadStatus
import dev.sebastian.vozlocal.polish.QwenEngine
import dev.sebastian.vozlocal.polish.QwenEngine.CleanupMode
import dev.sebastian.vozlocal.whisper.StreamingTranscriptReconciler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.ArrayList
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MainViewModel"

data class ModelDownloadUiState(
    val progress: Float = 0f,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 0f,
    val etaSeconds: Int? = null,
    val verificationLabel: String = "Unverified",
    val statusLabel: String = "Preparing"
)

data class VadDownloadUiState(
    val progress: Float = 0f,
    val status: VadDownloadStatus = VadDownloadStatus.NOT_DOWNLOADED,
    val statusLabel: String = "Not downloaded",
    val statusMessage: String = "Silero VAD model is not downloaded.",
    val sizeBytes: Long? = null,
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val isError: Boolean = false,
    val path: String? = null
)

class MainViewModel(
    private val repository: DictationRepository,
    private val audioRecorder: AudioRecorder
) : ViewModel() {

    // Models & Data Flows
    val modelsList: StateFlow<List<DictationModel>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedModel: StateFlow<DictationModel?> = repository.allModels
        .map { list -> list.find { it.isSelected } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val transcriptionHistory: StateFlow<List<TranscriptionHistory>> = repository.pagedHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dictionaryWords: StateFlow<List<DictionaryWord>> = repository.allWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dictationStats: StateFlow<List<DictationStat>> = repository.allStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Main App Record State
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordDurationSec = MutableStateFlow(0)
    val recordDurationSec: StateFlow<Int> = _recordDurationSec.asStateFlow()

    private val _liveWaveform = MutableStateFlow<List<Float>>(emptyList())
    val liveWaveform: StateFlow<List<Float>> = _liveWaveform.asStateFlow()

    // Zero-allocation ring buffer for live waveform (size 32); snapshot list created per emission.
    private val waveformBuffer = FloatArray(32) { 0.05f }
    private var waveformSize = 25
    private var waveformWrite = 0

    private val _currentLiveTranscription = MutableStateFlow("")
    val currentLiveTranscription: StateFlow<String> = _currentLiveTranscription.asStateFlow()
    private var streamingJob: Job? = null

    // Shared File Transcription State
    private val _sharedAudioUri = MutableStateFlow<Uri?>(null)
    val sharedAudioUri: StateFlow<Uri?> = _sharedAudioUri.asStateFlow()

    private val _sharedAudioName = MutableStateFlow("")
    val sharedAudioName: StateFlow<String> = _sharedAudioName.asStateFlow()

    private val _sharedAudioSize = MutableStateFlow("")
    val sharedAudioSize: StateFlow<String> = _sharedAudioSize.asStateFlow()

    private val _isSharedTranscribing = MutableStateFlow(false)
    val isSharedTranscribing: StateFlow<Boolean> = _isSharedTranscribing.asStateFlow()

    private val _sharedProgress = MutableStateFlow(0f)
    val sharedProgress: StateFlow<Float> = _sharedProgress.asStateFlow()

    private val _sharedStatusText = MutableStateFlow("")
    val sharedStatusText: StateFlow<String> = _sharedStatusText.asStateFlow()

    private val _sharedResultText = MutableStateFlow("")
    val sharedResultText: StateFlow<String> = _sharedResultText.asStateFlow()

    // In-memory map of per-model download progress, exposed to the UI via downloadProgressFor().
    private val _downloadProgressMap = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _downloadUiStateMap = MutableStateFlow<Map<String, ModelDownloadUiState>>(emptyMap())
    private val downloadStartedAtMs = ConcurrentHashMap<String, Long>()

    fun downloadProgressFor(modelId: String): StateFlow<Float> =
        _downloadProgressMap
            .map { map -> map[modelId] ?: 0f }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    fun downloadStatusFor(modelId: String): StateFlow<ModelDownloadUiState?> =
        _downloadUiStateMap
            .map { map -> map[modelId] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Settings State
    val smartPunctuation = MutableStateFlow(repository.getSmartPunctuation())
    val autoCapitalization = MutableStateFlow(repository.getAutoCapitalization())
    val applyDictionary = MutableStateFlow(repository.getApplyDictionary())
    val themeMode = MutableStateFlow(repository.getThemeMode())
    val historyLimit = MutableStateFlow(repository.getHistoryLimit())
    val saveHistory = MutableStateFlow(repository.getSaveHistory())
    val showOnlyOnInput = MutableStateFlow(repository.getShowOnlyOnInput())
    val whisperLanguage = MutableStateFlow(repository.getLanguage())
    val useAiPolisher = MutableStateFlow(repository.getUseAiPolisher())
    val cleanupMode = MutableStateFlow(CleanupMode.valueOf(repository.getCleanupMode().name))

    // AI Engine Settings (whisper_full_params surface)
    val noSpeechThold = MutableStateFlow(repository.getNoSpeechThold())
    val logprobThold = MutableStateFlow(repository.getLogprobThold())
    val entropyThold = MutableStateFlow(repository.getEntropyThold())
    val initialPrompt = MutableStateFlow(repository.getInitialPrompt() ?: "")
    val useVad = MutableStateFlow(repository.getUseVad())
    val spokenPunctuationCommands = MutableStateFlow(repository.getSpokenPunctuationCommands())
    val useStreamingDictation = MutableStateFlow(repository.getUseStreamingDictation())

    // Silero VAD + model-loading state (updated by the app-start background work)
    val isVadModelReady: StateFlow<Boolean> = repository.isVadModelReady
    val vadModelPath: StateFlow<String?> = repository.vadModelPath
    val vadDownloadProgress: StateFlow<Float> = repository.vadDownloadProgress
    val vadDownloadStatus: StateFlow<VadDownloadStatus> = repository.vadDownloadStatus
    val vadStatusLabel: StateFlow<String> = repository.vadStatusLabel
    val vadStatusMessage: StateFlow<String> = repository.vadStatusMessage
    val vadModelSizeBytes: StateFlow<Long?> = repository.vadModelSizeBytes
    val vadDownloadUiState: StateFlow<VadDownloadUiState> = combine(
        listOf(
            repository.vadDownloadProgress.map { it as Any? },
            repository.vadDownloadStatus.map { it as Any? },
            repository.vadStatusLabel.map { it as Any? },
            repository.vadStatusMessage.map { it as Any? },
            repository.vadModelSizeBytes.map { it as Any? },
            repository.isVadModelReady.map { it as Any? },
            repository.vadModelPath.map { it as Any? }
        )
    ) { values ->
        val status = values[1] as VadDownloadStatus
        VadDownloadUiState(
            progress = values[0] as Float,
            status = status,
            statusLabel = values[2] as String,
            statusMessage = values[3] as String,
            sizeBytes = values[4] as Long?,
            isReady = values[5] as Boolean,
            isDownloading = status == VadDownloadStatus.DOWNLOADING,
            isError = status == VadDownloadStatus.ERROR,
            path = values[6] as String?
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VadDownloadUiState())

    // The engine can load on demand when recording stops. Do not block dictation
    // just because no model is preloaded yet; missing downloads are reported as
    // actionable model-selection errors instead.
    val isModelLoading: StateFlow<Boolean> = MutableStateFlow(false)

    companion object {
        private val REGEX_WORD_SPLIT = Regex("\\s+")
        private const val STREAMING_WINDOW_SAMPLES = 8 * 16_000
        private const val STREAMING_MIN_SAMPLES = 3 * 16_000
        private const val STREAMING_INTERVAL_MS = 2_500L

        // Supported whisper.cpp language codes for the selector
        val LANGUAGE_OPTIONS = listOf(
            "es" to "🇲🇽 Español",
            "en" to "🇺🇸 English",
            "fr" to "🇫🇷 Français",
            "de" to "🇩🇪 Deutsch",
            "pt" to "🇧🇷 Português",
            "it" to "🇮🇹 Italiano",
            "auto" to "🌐 Auto-detect (slower)"
        )
    }

    fun setShowOnlyOnInput(value: Boolean) {
        repository.saveShowOnlyOnInput(value)
        showOnlyOnInput.value = value
    }

    fun setSmartPunctuation(value: Boolean) {
        repository.saveSmartPunctuation(value)
        smartPunctuation.value = value
    }

    fun setAutoCapitalization(value: Boolean) {
        repository.saveAutoCapitalization(value)
        autoCapitalization.value = value
    }

    fun setApplyDictionary(value: Boolean) {
        repository.saveApplyDictionary(value)
        applyDictionary.value = value
    }

    fun setThemeMode(value: String) {
        repository.saveThemeMode(value)
        themeMode.value = value
    }

    fun setLanguage(language: String) {
        repository.saveLanguage(language)
        whisperLanguage.value = language
    }

    fun setUseAiPolisher(value: Boolean) {
        repository.saveUseAiPolisher(value)
        useAiPolisher.value = value
    }

    fun setCleanupMode(value: CleanupMode) {
        repository.saveCleanupMode(QwenEngine.CleanupMode.valueOf(value.name))
        cleanupMode.value = value
    }

    fun setNoSpeechThold(value: Float) {
        repository.saveNoSpeechThold(value)
        noSpeechThold.value = value
    }

    fun setLogprobThold(value: Float) {
        repository.saveLogprobThold(value)
        logprobThold.value = value
    }

    fun setEntropyThold(value: Float) {
        repository.saveEntropyThold(value)
        entropyThold.value = value
    }

    fun setInitialPrompt(value: String) {
        repository.saveInitialPrompt(value)
        initialPrompt.value = value
    }

    fun setUseVad(value: Boolean) {
        repository.saveUseVad(value)
        useVad.value = value
    }

    fun setSpokenPunctuationCommands(value: Boolean) {
        repository.saveSpokenPunctuationCommands(value)
        spokenPunctuationCommands.value = value
    }

    fun setUseStreamingDictation(value: Boolean) {
        repository.saveUseStreamingDictation(value)
        useStreamingDictation.value = value
    }

    fun downloadVadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureVadModel()
        }
    }

    fun retryVadDownload() = downloadVadModel()

    fun deleteVadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteVadModel()
        }
    }

    fun setSaveHistory(value: Boolean) {
        repository.saveSaveHistory(value)
        saveHistory.value = value
    }

    fun setHistoryLimit(limit: Int) {
        repository.saveHistoryLimit(limit)
        historyLimit.value = limit
        viewModelScope.launch {
            repository.pruneHistory(limit)
        }
    }

    fun clearStats() {
        viewModelScope.launch {
            repository.clearStats()
        }
    }

    private var timerJob: Job? = null
    private var ownsRecorderSession = false

    // Dictionary Operations
    fun addWord(word: String, replacement: String) {
        viewModelScope.launch {
            repository.insertWord(DictionaryWord(word = word.trim(), replacement = replacement.trim()))
        }
    }

    fun deleteWord(id: Int) {
        viewModelScope.launch {
            repository.deleteWordById(id)
        }
    }

    // Models Operations
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            repository.selectModel(modelId)
        }
    }

    fun downloadModel(modelId: String) {
        val model = modelsList.value.find { it.id == modelId } ?: return
        beginDownloadState(modelId, model.sizeMb)
        repository.startModelDownload(modelId, viewModelScope) { progress ->
            updateDownloadState(modelId, model.sizeMb, progress, "Downloading")
            _downloadProgressMap.update { it + (modelId to progress) }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedModel(modelId)
        }
    }

    fun redownloadModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedModel(modelId)
            val model = modelsList.value.find { it.id == modelId } ?: return@launch
            beginDownloadState(modelId, model.sizeMb)
            repository.startModelDownload(modelId, viewModelScope) { progress ->
                updateDownloadState(modelId, model.sizeMb, progress, "Downloading")
                _downloadProgressMap.update { it + (modelId to progress) }
            }
        }
    }

    private fun beginDownloadState(modelId: String, totalMb: Float) {
        downloadStartedAtMs[modelId] = System.currentTimeMillis()
        _downloadUiStateMap.update {
            it + (modelId to ModelDownloadUiState(
                progress = 0.01f,
                downloadedMb = totalMb * 0.01f,
                totalMb = totalMb,
                etaSeconds = null,
                verificationLabel = repository.modelDownloader.verificationLabel(modelId),
                statusLabel = "Starting"
            ))
        }
    }

    private fun updateDownloadState(modelId: String, totalMb: Float, progress: Float, statusLabel: String) {
        val startedAt = downloadStartedAtMs[modelId] ?: System.currentTimeMillis()
        val etaSeconds = when {
            progress <= 0.02f || progress >= 1f -> null
            else -> {
                val elapsedSec = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1L)) / 1000f
                val estimatedTotalSec = elapsedSec / progress
                (estimatedTotalSec - elapsedSec).roundToInt().coerceAtLeast(0)
            }
        }
        _downloadUiStateMap.update {
            it + (modelId to ModelDownloadUiState(
                progress = progress.coerceIn(0f, 1f),
                downloadedMb = totalMb * progress.coerceIn(0f, 1f),
                totalMb = totalMb,
                etaSeconds = etaSeconds,
                verificationLabel = repository.modelDownloader.verificationLabel(modelId),
                statusLabel = statusLabel
            ))
        }
        if (progress >= 1f) {
            downloadStartedAtMs.remove(modelId)
        }
    }

    // History Operations
    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            repository.deleteHistoryById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    // Real Audio Recording & Local Whisper Inference
    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecordingAndTranscribe()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        _isRecording.value = true
        _recordDurationSec.value = 0
        _currentLiveTranscription.value = "Recording mic audio (PCM 16kHz)..."
        resetWaveform()

        selectedModel.value?.takeIf { it.isDownloaded }?.let { model ->
            viewModelScope.launch(Dispatchers.IO) { repository.preloadModel(model.id) }
        }

        // Start duration timer
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1.seconds)
                _recordDurationSec.value += 1
            }
        }

        try {
            val started = audioRecorder.startRecording(viewModelScope) { amplitude ->
                pushWaveform(amplitude)
            }
            if (!started) {
                timerJob?.cancel()
                timerJob = null
                _isRecording.value = false
                _currentLiveTranscription.value = "Microphone is already in use."
                _liveWaveform.value = emptyList()
                return
            }
            ownsRecorderSession = true
            if (useStreamingDictation.value) {
                selectedModel.value?.takeIf { it.isDownloaded }?.let(::startStreamingPreview)
            }
        } catch (e: SecurityException) {
            timerJob?.cancel()
            timerJob = null
            _isRecording.value = false
            _currentLiveTranscription.value = e.message ?: "Microphone permission not granted."
            _liveWaveform.value = emptyList()
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!_isRecording.value) return

        timerJob?.cancel()
        _isRecording.value = false

        val activeStreamingJob = streamingJob
        streamingJob = null
        activeStreamingJob?.cancel()
        val samples = if (ownsRecorderSession) audioRecorder.stopRecording() else FloatArray(0)
        ownsRecorderSession = false
        val finalDuration = _recordDurationSec.value
        val model = selectedModel.value

        if (model == null) {
            _currentLiveTranscription.value = "Please select a model in the Models tab."
            return
        }

        if (!model.isDownloaded) {
            _currentLiveTranscription.value = "Please download ${model.name} in the Models tab first."
            return
        }

        if (samples.isEmpty()) {
            _currentLiveTranscription.value = "No mic audio captured."
            return
        }

        _currentLiveTranscription.value = "Running local Whisper model inference..."

        viewModelScope.launch(Dispatchers.Default) {
            // Do not race the authoritative final pass with an in-flight preview
            // inference against the same non-thread-safe Whisper context.
            activeStreamingJob?.join()
            val rawOutput = repository.transcribeAudio(samples, model.id)

            if (rawOutput.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _currentLiveTranscription.value = "No speech detected in recorded audio."
                    _liveWaveform.value = emptyList()
                }
                return@launch
            }

            val processedText = repository.postProcessText(
                text = rawOutput,
                smartPunctuation = smartPunctuation.value,
                autoCapitalize = autoCapitalization.value,
                applyDict = applyDictionary.value,
                useAiPolisher = useAiPolisher.value,
                cleanupMode = QwenEngine.CleanupMode.valueOf(cleanupMode.value.name)
            )

            val wordCount = processedText.split(REGEX_WORD_SPLIT).count { it.isNotBlank() }
            val calcDuration = if (finalDuration > 0) finalDuration else 1
            val calculatedWpm = (wordCount.toFloat() / (calcDuration.toFloat() / 60f))

            repository.insertStat(
                DictationStat(
                    wordCount = wordCount,
                    durationSec = finalDuration,
                    wpm = calculatedWpm
                )
            )

            repository.insertHistory(
                TranscriptionHistory(
                    text = processedText,
                    durationSec = finalDuration,
                    modelUsed = model.name,
                    type = "dictation"
                )
            )

            withContext(Dispatchers.Main) {
                _currentLiveTranscription.value = processedText
                _liveWaveform.value = emptyList()
            }
        }
    }

    /**
     * Experimental in-app preview: every pass transcribes an overlapping rolling
     * window. The reconciler commits only text preceding a confirmed overlap; the
     * final full-recording pass above remains authoritative for history/copying.
     */
    private fun startStreamingPreview(model: DictationModel) {
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch(Dispatchers.Default) {
            val reconciler = StreamingTranscriptReconciler()
            while (isActive && _isRecording.value) {
                delay(STREAMING_INTERVAL_MS)
                val window = audioRecorder.snapshotRecording(STREAMING_WINDOW_SAMPLES)
                if (window.size < STREAMING_MIN_SAMPLES) continue

                val preview = repository.transcribeAudio(window, model.id)
                if (preview.isNotBlank() && isActive && _isRecording.value) {
                    _currentLiveTranscription.value = reconciler.accept(preview).text
                }
            }
        }
    }

    // Shared Audio Intake & Asynchronous Local Transcription
    fun setSharedAudio(context: Context, uri: Uri) {
        _sharedAudioUri.value = uri
        _sharedResultText.value = ""
        _sharedProgress.value = 0f
        _sharedStatusText.value = "Audio file loaded. Select model above to transcribe."

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    _sharedAudioName.value = cursor.getString(nameIndex) ?: "shared_audio_track.mp3"
                    val sizeBytes = cursor.getLong(sizeIndex)
                    _sharedAudioSize.value = formatFileSize(sizeBytes)
                }
            }
        } catch (e: Exception) {
            _sharedAudioName.value = "Shared Audio Track"
            _sharedAudioSize.value = "Unknown Size"
        }
    }

    fun startSharedTranscription() {
        val uri = _sharedAudioUri.value ?: return
        val model = selectedModel.value ?: return

        _isSharedTranscribing.value = true
        _sharedProgress.value = 0.05f
        _sharedResultText.value = ""
        _sharedStatusText.value = "Preparing local decoder..."

        viewModelScope.launch(Dispatchers.Default) {
            val result = repository.transcribeSharedFile(uri, model.id) { prog, status ->
                _sharedProgress.value = prog.coerceIn(0f, 1f)
                _sharedStatusText.value = status
            }

            if (result.startsWith("Error:")) {
                withContext(Dispatchers.Main) {
                    _sharedProgress.value = 0f
                    _sharedStatusText.value = result
                    _sharedResultText.value = result
                    _isSharedTranscribing.value = false
                }
                return@launch
            }

            val processedResult = repository.postProcessText(
                text = result,
                smartPunctuation = smartPunctuation.value,
                autoCapitalize = autoCapitalization.value,
                applyDict = applyDictionary.value,
                useAiPolisher = useAiPolisher.value,
                cleanupMode = QwenEngine.CleanupMode.valueOf(cleanupMode.value.name)
            )

            repository.insertHistory(
                TranscriptionHistory(
                    text = processedResult,
                    durationSec = 0,
                    modelUsed = model.name,
                    type = "shared_file",
                    fileName = _sharedAudioName.value
                )
            )

            withContext(Dispatchers.Main) {
                _sharedProgress.value = 1.0f
                _sharedStatusText.value = "Transcription Completed!"
                _sharedResultText.value = processedResult
                _isSharedTranscribing.value = false
            }
        }
    }

    fun clearSharedFile() {
        _sharedAudioUri.value = null
        _sharedAudioName.value = ""
        _sharedAudioSize.value = ""
        _sharedStatusText.value = ""
        _sharedResultText.value = ""
        _sharedProgress.value = 0f
    }

    fun loadHistoryDraft(text: String) {
        _currentLiveTranscription.value = text
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun resetWaveform() {
        waveformBuffer.fill(0.05f)
        waveformSize = 25
        waveformWrite = 0
        _liveWaveform.value = snapshotWaveform()
    }

    private fun pushWaveform(amplitude: Float) {
        waveformBuffer[waveformWrite] = amplitude
        waveformWrite = (waveformWrite + 1) % waveformBuffer.size
        if (waveformSize < waveformBuffer.size) waveformSize++
        _liveWaveform.value = snapshotWaveform()
    }

    private fun snapshotWaveform(): List<Float> {
        val snapshot = ArrayList<Float>(waveformSize)
        var idx = if (waveformSize < waveformBuffer.size) 0 else waveformWrite
        repeat(waveformSize) {
            snapshot.add(waveformBuffer[idx])
            idx = (idx + 1) % waveformBuffer.size
        }
        return snapshot
    }

    override fun onCleared() {
        streamingJob?.cancel()
        streamingJob = null
        if (ownsRecorderSession) {
            audioRecorder.stopRecording()
            ownsRecorderSession = false
        }
        super.onCleared()
    }
}
