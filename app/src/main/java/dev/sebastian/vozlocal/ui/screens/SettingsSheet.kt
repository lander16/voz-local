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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel

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
    val useAiPolisher by viewModel.useAiPolisher.collectAsStateWithLifecycle()
    val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
    val whisperLanguage by viewModel.whisperLanguage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

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
                            text = "On-Device Engine & System Customization",
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

            // Section 1: Appearance
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Appearance",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Choose how VozLocal looks across the app.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )

                val themeOptions = listOf(
                    "light" to "Light",
                    "dark" to "Dark",
                    "system" to "System (default Dark)"
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
                    text = "Transcription engine language",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Explicitly selecting your target language avoids Whisper auto-detection latency (~200ms speedup).",
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

            // Section 3: Post-Processing & AI Polisher
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Text post-processing & local AI",
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
                        Text(text = "Smart Pause Correction", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Fuses speech pauses into punctuation", fontSize = 13.sp, color = TextSecondary)
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
                        Text(text = "Auto-Capitalization", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                        Text(text = "Apply Custom Dictionary", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Auto-replaces custom vocabulary", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = applyDictionary,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                // AI Polisher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .toggleable(
                            value = useAiPolisher,
                            role = Role.Switch,
                            onValueChange = { viewModel.setUseAiPolisher(it) }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = "Qwen2.5 AI Text Polisher", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Box(
                                modifier = Modifier
                                    .background(AccentViolet.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = "LLM", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = AccentViolet)
                            }
                        }
                        Text(text = "Removes stutters, filler words & enhances grammar locally", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = useAiPolisher,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet)
                    )
                }
            }

            HorizontalDivider(color = GlassBorder)

            // Section 4: System Overlay Assistant
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Floating assistant overlay",
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
                        Text(text = "Show Only On Input Focus", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "Hides floating button when no text box is focused", fontSize = 13.sp, color = TextSecondary)
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
                    text = "History storage retention limit",
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
                        Text(text = "Save Transcription History", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(text = "When off, transcriptions are never written to local storage", fontSize = 13.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = saveHistory,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                    )
                }

                // Retention limit chips (dimmed when saving is off)
                Text(
                    text = "Configure maximum history items retained in your local SQLite database.",
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
