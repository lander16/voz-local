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

fun modelRecommendationLabel(modelId: String): String = when (modelId) {
    "whisper_tiny" -> "Fastest"
    "whisper_base" -> "Balanced"
    "whisper_small" -> "Best accuracy"
    "whisper_medium" -> "Largest"
    "whisper_large_v3_turbo" -> "Quality + speed"
    else -> "Recommended"
}
