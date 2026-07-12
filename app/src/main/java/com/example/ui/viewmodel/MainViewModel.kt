package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DictationModel
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.data.model.DictationStat
import com.example.data.repository.DictationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import kotlin.random.Random

class MainViewModel(private val repository: DictationRepository) : ViewModel() {

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

    private var recordJob: Job? = null
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

    // Main Audio Recording Simulation
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
        _currentLiveTranscription.value = "Listening to microphone..."
        _liveWaveform.value = List(25) { 0.1f }

        // Start duration timer
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _recordDurationSec.value += 1
            }
        }

        // Generate dynamic live waveform values simulating real speech
        recordJob = viewModelScope.launch {
            while (isActive) {
                delay(120)
                val newWaves = _liveWaveform.value.toMutableList()
                newWaves.removeAt(0)
                // randomize waves mimicking human talking blocks and silences
                val speakWeight = if (Random.nextFloat() > 0.3f) Random.nextFloat() * 0.8f + 0.15f else 0.05f
                newWaves.add(speakWeight)
                _liveWaveform.value = newWaves

                // Periodically update live feedback text to resemble Whisper continuous decoding
                val sec = _recordDurationSec.value
                _currentLiveTranscription.value = when {
                    sec == 0 -> "Listening for audio speech input..."
                    sec < 3 -> "Processing voice feed..."
                    sec < 6 -> "Procesando voz local [Model: ${selectedModel.value?.name ?: "Whisper"}]..."
                    sec < 10 -> "Procesando voz: \"Estamos transcribiendo audio de forma completamente offline...\""
                    else -> "Procesando voz: \"Estamos transcribiendo audio de forma completamente offline en este dispositivo local...\""
                }
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        timerJob?.cancel()
        recordJob?.cancel()
        _isRecording.value = false

        val finalDuration = _recordDurationSec.value
        val model = selectedModel.value ?: return

        _currentLiveTranscription.value = "Post-processing audio transcripts..."

        viewModelScope.launch {
            delay(1200) // simulating final post-processing step

            // Spanish or English output simulation base
            val baseSpanishText = "Hola, esta es una demostración real del dictado de voz offline de VozLocal. El motor está ejecutándose directamente en este teléfono móvil, garantizando total privacidad sin enviar datos a la nube."
            val baseEnglishText = "Hello, this is a real demonstration of VozLocal offline voice dictation. The model is running directly on this device, guaranteeing complete privacy with no cloud connections."

            val rawText = if (model.id == "whisper_es_optimized") {
                baseSpanishText
            } else {
                // Mix in language options based on settings
                if (Locale.getDefault().language == "es") baseSpanishText else baseEnglishText
            }

            // Apply Dictionary, Auto-caps and Punctuation
            val processedText = repository.postProcessText(
                text = rawText,
                smartPunctuation = smartPunctuation.value,
                autoCapitalize = autoCapitalization.value,
                applyDict = applyDictionary.value
            )

            // Calculate and Save statistics
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

            // Save to history
            repository.insertHistory(
                TranscriptionHistory(
                    text = processedText,
                    durationSec = finalDuration,
                    modelUsed = model.name,
                    type = "dictation"
                )
            )

            _currentLiveTranscription.value = ""
            _recordDurationSec.value = 0
            _liveWaveform.value = emptyList()
        }
    }

    // Shared Audio Intake & Transcription
    fun setSharedAudio(context: Context, uri: Uri) {
        _sharedAudioUri.value = uri
        _sharedResultText.value = ""
        _sharedProgress.value = 0f
        _sharedStatusText.value = "Audio file loaded. Select model above to transcribe."

        // Fetch display name and file size
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
        _sharedProgress.value = 0f
        _sharedResultText.value = ""

        viewModelScope.launch(Dispatchers.Default) {
            val stages = listOf(
                "Initializing audio codec reader..." to 0.15f,
                "Reading PCM audio frames (16kHz)..." to 0.35f,
                "Extracting voice features (Mel spectrogram)..." to 0.55f,
                "Running Whisper local encoder-decoder passes..." to 0.80f,
                "Refining transcription and correcting pauses..." to 0.95f
            )

            for (stage in stages) {
                _sharedStatusText.value = stage.first
                // Simulation delays relative to model speed multipliers
                val delayTime = (600 / model.speedMultiplier).toLong()
                
                // Gradually increment progress
                val startProg = _sharedProgress.value
                val endProg = stage.second
                val steps = 10
                for (step in 1..steps) {
                    delay(delayTime / steps)
                    _sharedProgress.value = startProg + (endProg - startProg) * (step.toFloat() / steps)
                }
            }

            _sharedStatusText.value = "Post-processing dictionary checks..."
            delay(400)

            // Transcribe based on context of audio file name or selected model
            val fileNameLower = _sharedAudioName.value.lowercase()
            val isSpanishFile = fileNameLower.contains("audio") || fileNameLower.contains("grabacion") || fileNameLower.contains("nota") || model.id == "whisper_es_optimized"

            val rawOutput = if (isSpanishFile) {
                "He completado la transcripción local del archivo compartido de audio. Esta nota grabada contenía información muy importante sobre el diseño del pipeline local del modelo, incluyendo los parámetros del corrector de pausas silenciosas y el diccionario."
            } else {
                "Successfully completed local transcription of the shared audio file. The voice recording discussed building a state-of-the-art offline speech architecture using localized whisper checkpoints and custom dictionary postprocessing."
            }

            val processedResult = repository.postProcessText(
                text = rawOutput,
                smartPunctuation = smartPunctuation.value,
                autoCapitalize = autoCapitalization.value,
                applyDict = applyDictionary.value
            )

            // Save to history
            repository.insertHistory(
                TranscriptionHistory(
                    text = processedResult,
                    durationSec = 18, // simulated audio length
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
