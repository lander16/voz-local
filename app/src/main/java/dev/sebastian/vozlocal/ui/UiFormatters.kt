package dev.sebastian.vozlocal.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.sebastian.vozlocal.R
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
    @param:StringRes val nameRes: Int,
    @param:StringRes val badgeRes: Int,
    @param:StringRes val languageRes: Int,
    val quantization: String,
    @param:StringRes val descriptionRes: Int,
)

fun modelPresentation(modelId: String): ModelPresentation = when (modelId) {
    "whisper_tiny" -> ModelPresentation(
        R.string.model_name_tiny_q8, R.string.model_badge_smallest_download,
        R.string.model_language_multilingual, "q8_0", R.string.model_description_tiny_q8,
    )
    "whisper_base" -> ModelPresentation(
        R.string.model_name_base_q8, R.string.model_badge_default,
        R.string.model_language_multilingual, "q8_0", R.string.model_description_base_q8,
    )
    "whisper_base_en" -> ModelPresentation(
        R.string.model_name_base_en_q8, R.string.model_badge_english_only,
        R.string.model_language_english_only, "q8_0", R.string.model_description_base_en_q8,
    )
    "whisper_small" -> ModelPresentation(
        R.string.model_name_small_q8, R.string.model_badge_multilingual,
        R.string.model_language_multilingual, "q8_0", R.string.model_description_small_q8,
    )
    "whisper_small_q5_1" -> ModelPresentation(
        R.string.model_name_small_q5, R.string.model_badge_smaller_small,
        R.string.model_language_multilingual, "q5_1", R.string.model_description_small_q5,
    )
    "whisper_large_v3_turbo" -> ModelPresentation(
        R.string.model_name_turbo_q5, R.string.model_badge_turbo,
        R.string.model_language_multilingual, "q5_0", R.string.model_description_turbo_q5,
    )
    "whisper_medium" -> ModelPresentation(
        R.string.model_name_medium_q8, R.string.model_badge_largest_download,
        R.string.model_language_multilingual, "q8_0", R.string.model_description_medium_q8,
    )
    else -> ModelPresentation(
        R.string.model_name_local, R.string.model_badge_local,
        R.string.model_language_unknown, "—", R.string.model_description_unknown,
    )
}

@Composable
fun modelDownloadStatusLabel(status: String?): String = when (status) {
    "Starting" -> stringResource(R.string.model_download_starting)
    "Downloading" -> stringResource(R.string.model_download_downloading)
    "Preparing" -> stringResource(R.string.model_download_preparing)
    else -> stringResource(R.string.model_download_preparing)
}

@Composable
fun modelVerificationLabel(label: String?): String = when {
    label?.contains("SHA-256") == true -> stringResource(R.string.model_verified_sha256)
    label?.startsWith("Verified") == true -> stringResource(R.string.model_verified_transport)
    else -> stringResource(R.string.model_unverified)
}
