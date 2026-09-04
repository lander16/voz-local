package dev.sebastian.vozlocal.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun formatMegabytes(value: Float): String = String.format(Locale.US, "%.0f MB", value)

fun formatDownloadProgress(downloadedMb: Float, totalMb: Float): String =
    "${formatMegabytes(downloadedMb)} / ${formatMegabytes(totalMb)}"

fun formatEta(seconds: Int?): String? {
    val safe = seconds ?: return null
    if (safe <= 0) return null
    val mins = safe / 60
    val secs = safe % 60
    return when {
        mins > 0 -> String.format(Locale.US, "%dm %02ds left", mins, secs)
        else -> String.format(Locale.US, "%ds left", secs)
    }
}

fun formatDurationMinutes(seconds: Int): String = String.format(Locale.US, "%.1f min", seconds / 60f)

fun formatShortDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

fun historyDateGroupLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val isSameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (isSameDay) return "Today"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"

    return SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(timestamp))
}

data class ModelPresentation(
    val badge: String,
    val language: String,
    val quantization: String,
    val description: String,
)

fun modelPresentation(modelId: String): ModelPresentation = when (modelId) {
    "whisper_tiny" -> ModelPresentation(
        "Smallest download", "Multilingual", "q8_0",
        "Useful when storage and memory are constrained; recognition results can differ from larger models.",
    )
    "whisper_base" -> ModelPresentation(
        "Default", "Multilingual", "q8_0",
        "Default starting point. Compare it with Tiny or Small using recordings representative of your voice.",
    )
    "whisper_base_en" -> ModelPresentation(
        "English only", "English only", "q8_0",
        "English-only checkpoint. Do not select it for Spanish or multilingual dictation.",
    )
    "whisper_small" -> ModelPresentation(
        "Multilingual", "Multilingual", "q8_0",
        "A larger Small checkpoint. Its speed and recognition quality must be benchmarked on each device.",
    )
    "whisper_small_q5_1" -> ModelPresentation(
        "Smaller Small file", "Multilingual", "q5_1",
        "The Small checkpoint with stronger compression. Compression can change both output and performance.",
    )
    "whisper_large_v3_turbo" -> ModelPresentation(
        "Turbo checkpoint", "Multilingual", "q5_0",
        "Substantial storage and memory requirements. Benchmark it locally before making it the default.",
    )
    "whisper_medium" -> ModelPresentation(
        "Largest download", "Multilingual", "q8_0",
        "Intended for devices with ample storage and memory; sustained performance must be measured locally.",
    )
    else -> ModelPresentation(
        "Local model", "Unknown", "Unknown",
        "Recognition quality and transcription time depend on the device, audio, and decoding settings.",
    )
}
