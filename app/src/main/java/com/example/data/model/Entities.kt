package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictation_models")
data class DictationModel(
    @PrimaryKey val id: String,
    val name: String,
    val sizeMb: Float,
    val accuracySpanish: Int, // percentage (e.g., 80)
    val accuracyEnglish: Int, // percentage
    val speedMultiplier: Float, // speed relative to baseline (e.g., 10.0f)
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

