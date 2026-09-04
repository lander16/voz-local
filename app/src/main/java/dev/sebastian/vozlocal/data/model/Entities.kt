package dev.sebastian.vozlocal.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictation_models")
data class DictationModel(
    @PrimaryKey val id: String,
    val name: String,
    val sizeMb: Float,
    // Legacy schema fields retained until the next Room migration. The UI does
    // not present universal accuracy or speed claims without benchmark provenance.
    val accuracySpanish: Int,
    val accuracyEnglish: Int,
    val speedMultiplier: Float,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0.0f,
    val isDownloading: Boolean = false,
    val isSelected: Boolean = false
)

@Entity(tableName = "transcription_history")
data class TranscriptionHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationSec: Int = 0,
    val modelUsed: String,
    val type: String, // "dictation" or "shared_file"
    val fileName: String? = null
)

@Entity(tableName = "dictionary_words")
data class DictionaryWord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word: String,
    val replacement: String
)

@Entity(tableName = "dictation_stats")
data class DictationStat(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val wordCount: Int,
    val durationSec: Int,
    val wpm: Float
)
