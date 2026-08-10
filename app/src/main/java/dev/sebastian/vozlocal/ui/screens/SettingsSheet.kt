package dev.sebastian.vozlocal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.polish.QwenEngine.CleanupMode
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel
import java.util.Locale
import kotlin.math.roundToInt

/** Snaps a slider value to the nearest [step] within [min]..[max]. */
private fun snapSlider(value: Float, min: Float, max: Float, step: Float): Float {
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(min, max)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val historyLimit by viewModel.historyLimit.collectAsStateWithLifecycle()
    val saveHistory by viewModel.saveHistory.collectAsStateWithLifecycle()
    val smartPunctuation by viewModel.smartPunctuation.collectAsStateWithLifecycle()
    val autoCapitalization by viewModel.autoCapitalization.collectAsStateWithLifecycle()
    val applyDictionary by viewModel.applyDictionary.collectAsStateWithLifecycle()
    val cleanupMode by viewModel.cleanupMode.collectAsStateWithLifecycle()
    val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
    val whisperLanguage by viewModel.whisperLanguage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val noSpeechThold by viewModel.noSpeechThold.collectAsStateWithLifecycle()
    val logprobThold by viewModel.logprobThold.collectAsStateWithLifecycle()
    val entropyThold by viewModel.entropyThold.collectAsStateWithLifecycle()
    val initialPrompt by viewModel.initialPrompt.collectAsStateWithLifecycle()
    val isVadModelReady by viewModel.isVadModelReady.collectAsStateWithLifecycle()
    val useVad by viewModel.useVad.collectAsStateWithLifecycle()
    val spokenPunctuationCommands by viewModel.spokenPunctuationCommands.collectAsStateWithLifecycle()
    val useStreamingDictation by viewModel.useStreamingDictation.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(GlassBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = PrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "VozLocal Preferences",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Local privacy, model, and dictation controls",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = TextSecondary
                    )
                }
            }

            HorizontalDivider(color = GlassBorder)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = "Local-only by design", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = "No telemetry, no cloud dictation. Model downloads are the only network use.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Section 1: Appearance
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Theme",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Choose light, dark, or follow your phone.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                val themeOptions = listOf(
                    "light" to "Light",
                    "dark" to "Dark",
                    "system" to "System"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    themeOptions.forEach { (value, label) ->
                        val selected = themeMode == value
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) PrimaryColor else SurfaceCard)
                                .border(
                                    BorderStroke(1.dp, if (selected) PrimaryColor else GlassBorder),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setThemeMode(value) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) Color.White else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 2: Audio & Transcription Language
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Dictation language",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Pick a language to skip Whisper auto-detect and start faster.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val languageOptions = MainViewModel.LANGUAGE_OPTIONS
                    languageOptions.forEach { (code, label) ->
                        val isSelected = whisperLanguage == code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) PrimaryColor.copy(alpha = 0.2f) else SurfaceCard)
                                .border(
                                    BorderStroke(1.dp, if (isSelected) PrimaryColor else GlassBorder),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setLanguage(code) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextPrimary
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = PrimaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 3: Post-Processing & Cleanup
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Text cleanup",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                // Smart Pause
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = smartPunctuation,
                            role = Role.Switch,
                            onValueChange = { viewModel.setSmartPunctuation(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Smart punctuation", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Turns pauses into punctuation", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = smartPunctuation,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                // Auto-Capitalization
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = autoCapitalization,
                            role = Role.Switch,
                            onValueChange = { viewModel.setAutoCapitalization(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Auto-capitalize", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Starts sentences with uppercase", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = autoCapitalization,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                // Apply Dictionary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = applyDictionary,
                            role = Role.Switch,
                            onValueChange = { viewModel.setApplyDictionary(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Custom dictionary", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Replaces words you’ve added", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = applyDictionary,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                Text(
                    text = "Local cleanup is rule-based, not AI/LLM.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cleanupOptionRow(
                        title = "Minimal",
                        description = "Keeps the transcript closest to raw speech.",
                        selected = cleanupMode == CleanupMode.MINIMAL,
                        onClick = { viewModel.setCleanupMode(CleanupMode.MINIMAL) }
                    )
                    cleanupOptionRow(
                        title = "Balanced",
                        description = "Default cleanup for fillers, pauses, and punctuation.",
                        selected = cleanupMode == CleanupMode.BALANCED,
                        onClick = { viewModel.setCleanupMode(CleanupMode.BALANCED) }
                    )
                    cleanupOptionRow(
                        title = "Aggressive",
                        description = "Stronger cleanup for rough dictation, with more rewriting.",
                        selected = cleanupMode == CleanupMode.AGGRESSIVE,
                        onClick = { viewModel.setCleanupMode(CleanupMode.AGGRESSIVE) }
                    )
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 4: System Overlay Assistant
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Floating assistant",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = showOnlyOnInput,
                            role = Role.Switch,
                            onValueChange = { viewModel.setShowOnlyOnInput(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Only show on text fields", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Hides the mic when no input is focused", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = showOnlyOnInput,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 5: History Retention Limits
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "History & storage",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                // Save History toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .semantics(mergeDescendants = true) {}
                        .toggleable(
                            value = saveHistory,
                            role = Role.Switch,
                            onValueChange = { viewModel.setSaveHistory(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Save transcripts locally", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Turn off to keep transcripts out of the database", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = saveHistory,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                // Retention limit chips (dimmed when saving is off)
                Text(
                    text = "Limit how many local transcripts are kept on device.",
                    fontSize = 13.sp,
                    color = if (saveHistory) TextSecondary else TextSecondary.copy(alpha = 0.4f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (saveHistory) 1f else 0.35f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(
                        5 to "5",
                        10 to "10",
                        20 to "20",
                        50 to "50",
                        -1 to "All"
                    )
                    options.forEach { (valLimit, label) ->
                        val isSelected = historyLimit == valLimit
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) PrimaryColor else SurfaceCard)
                                .border(
                                    BorderStroke(1.dp, if (isSelected) PrimaryColor else GlassBorder),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.setHistoryLimit(valLimit) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 6: AI Engine
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Advanced engine",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Tune whisper.cpp for your voice and hardware.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                // No-speech threshold
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "No-speech threshold", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "%.2f", noSpeechThold),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PrimaryColor
                        )
                    }
                    Slider(
                        value = noSpeechThold.coerceIn(0f, 1f),
                        onValueChange = { viewModel.setNoSpeechThold(snapSlider(it, 0f, 1f, 0.05f)) },
                        valueRange = 0.0f..1.0f,
                        steps = 19
                    )
                }

                // Log-probability threshold
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Log-probability threshold", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "%.2f", logprobThold),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PrimaryColor
                        )
                    }
                    Slider(
                        value = logprobThold.coerceIn(-2f, 0f),
                        onValueChange = { viewModel.setLogprobThold(snapSlider(it, -2f, 0f, 0.1f)) },
                        valueRange = -2.0f..0.0f,
                        steps = 19
                    )
                }

                // Entropy threshold
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Entropy threshold", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            text = String.format(Locale.US, "%.2f", entropyThold),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PrimaryColor
                        )
                    }
                    Slider(
                        value = entropyThold.coerceIn(0f, 5f),
                        onValueChange = { viewModel.setEntropyThold(snapSlider(it, 0f, 5f, 0.1f)) },
                        valueRange = 0.0f..5.0f,
                        steps = 49
                    )
                }

                // Initial prompt
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Initial prompt", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    OutlinedTextField(
                        value = initialPrompt,
                        onValueChange = { viewModel.setInitialPrompt(it) },
                        placeholder = {
                            Text(
                                text = dev.sebastian.vozlocal.whisper.SPANISH_PROMPT,
                                fontSize = 13.sp,
                                color = TextSecondary.copy(alpha = 0.6f)
                            )
                        },
                        supportingText = {
                            Text("Optional. Primes Whisper with this text before transcription.", fontSize = 12.sp, color = TextSecondary)
                        },
                        minLines = 4,
                        maxLines = 6,
                        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryColor,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = SurfaceCard,
                            unfocusedContainerColor = SurfaceCard,
                            cursorColor = PrimaryColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // VAD model status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Silero VAD model", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Voice-activity detection removes silence before inference", fontSize = 13.sp, color = TextSecondary)
                    }
                    Text(
                        text = if (isVadModelReady) "Ready" else "Downloading...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isVadModelReady) Color(0xFF10B981) else TextSecondary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = useStreamingDictation,
                            role = Role.Switch,
                            onValueChange = { viewModel.setUseStreamingDictation(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Streaming dictation (experimental)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Shows text while you speak; may revise the latest words.", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = useStreamingDictation,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .toggleable(value = useVad, role = Role.Switch, onValueChange = { viewModel.setUseVad(it) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Use VAD when ready", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Default on for faster dictation; disable if it clips speech", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = useVad,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .toggleable(value = spokenPunctuationCommands, role = Role.Switch, onValueChange = { viewModel.setSpokenPunctuationCommands(it) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Spoken punctuation commands", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Opt-in replacement of words like comma/coma/punto", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = spokenPunctuationCommands,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }
            }

            HorizontalDivider(color = GlassBorder)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Privacy & trust", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor, letterSpacing = 1.sp)
                    Text(text = "Everything here is stored locally. Network access is only used for model downloads.", fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
                    listOf(
                        "Accessibility scope is kept narrow",
                        "Models and history stay in app-private storage",
                        "Release builds don’t log raw transcripts"
                    ).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(100.dp)).background(PrimaryColor).padding(top = 5.dp))
                            Text(text = line, fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
                        }
                    }
                }
            }

            // Confirm Done Button
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(text = "Done", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
    }
}

@Composable
private fun cleanupOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrimaryColor.copy(alpha = 0.16f) else SurfaceCard)
            .border(BorderStroke(1.dp, if (selected) PrimaryColor else GlassBorder), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) PrimaryColor else TextPrimary
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 16.sp
            )
        }

        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(if (selected) PrimaryColor else Color.Transparent)
                .border(BorderStroke(1.5.dp, if (selected) PrimaryColor else GlassBorder), RoundedCornerShape(100.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
