package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorder
import com.example.data.model.DictationModel
import com.example.data.model.DictationStat
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.data.repository.DictationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

private const val TAG = "MainViewModel"

class MainViewModel(
    private val context: Context,
    private val repository: DictationRepository
) : ViewModel() {

    private val audioRecorder = AudioRecorder()

    // Models & Data Flows
    val modelsList: StateFlow<List<DictationModel>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedModel: StateFlow<DictationModel?> = repository.allModels
        .map { list -> list.find { it.isSelected } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val transcriptionHistory: StateFlow<List<TranscriptionHistory>> = repository.allHistory
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

    private val _currentLiveTranscription = MutableStateFlow("")
    val currentLiveTranscription: StateFlow<String> = _currentLiveTranscription.asStateFlow()

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

    // Settings State
    val smartPunctuation = MutableStateFlow(true)
    val autoCapitalization = MutableStateFlow(true)
    val applyDictionary = MutableStateFlow(true)
    val historyLimit = MutableStateFlow(repository.getHistoryLimit())
    val showOnlyOnInput = MutableStateFlow(repository.getShowOnlyOnInput())
    val whisperLanguage = MutableStateFlow(repository.getLanguage())
    val useAiPolisher = MutableStateFlow(repository.getUseAiPolisher())

    companion object {
        // Supported whisper.cpp language codes for the selector
        val LANGUAGE_OPTIONS = listOf(
            "es" to "Español",
            "en" to "English",
            "fr" to "Français",
            "de" to "Deutsch",
            "pt" to "Português",
            "it" to "Italiano",
            "auto" to "Auto-detect (slower)"
        )
    }

    fun setShowOnlyOnInput(value: Boolean) {
        repository.saveShowOnlyOnInput(value)
        showOnlyOnInput.value = value
    }

    fun setLanguage(language: String) {
        repository.saveLanguage(language)
        whisperLanguage.value = language
    }

    fun setUseAiPolisher(value: Boolean) {
        repository.saveUseAiPolisher(value)
        useAiPolisher.value = value
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
        repository.startModelDownload(modelId, viewModelScope)
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedModel(modelId)
        }
    }

    fun redownloadModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteDownloadedModel(modelId)
            repository.startModelDownload(modelId, viewModelScope)
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
        _liveWaveform.value = List(25) { 0.05f }

        // Start duration timer
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _recordDurationSec.value += 1
            }
        }

        audioRecorder.startRecording(viewModelScope) { amplitude ->
            val newWaves = _liveWaveform.value.toMutableList()
            if (newWaves.size > 25) newWaves.removeAt(0)
            newWaves.add(amplitude)
            _liveWaveform.value = newWaves
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!_isRecording.value) return

        timerJob?.cancel()
        _isRecording.value = false

        val samples = audioRecorder.stopRecording()
        val finalDuration = _recordDurationSec.value
        val model = selectedModel.value

        if (model == null) {
            _currentLiveTranscription.value = "Please select a model in the Models tab."
            return
        }

        if (samples.isEmpty()) {
            _currentLiveTranscription.value = "No mic audio captured."
            return
        }

        _currentLiveTranscription.value = "Running local Whisper model inference..."

        viewModelScope.launch(Dispatchers.Default) {
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
                applyDict = applyDictionary.value
            )

            val wordCount = processedText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
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
                _sharedProgress.value = prog
                _sharedStatusText.value = status
            }

            val processedResult = repository.postProcessText(
                text = result,
                smartPunctuation = smartPunctuation.value,
                autoCapitalize = autoCapitalization.value,
                applyDict = applyDictionary.value
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

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
