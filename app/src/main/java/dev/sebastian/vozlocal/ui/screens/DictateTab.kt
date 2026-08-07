package dev.sebastian.vozlocal.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictateTab(
    viewModel: MainViewModel,
    showFloatingAssistantCard: Boolean = true,
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordDurationSec by viewModel.recordDurationSec.collectAsStateWithLifecycle()
    val liveWaveform by viewModel.liveWaveform.collectAsStateWithLifecycle()
    val liveText by viewModel.currentLiveTranscription.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

    // Modifier Switches
    val smartPunctuation by viewModel.smartPunctuation.collectAsStateWithLifecycle()
    val autoCapitalization by viewModel.autoCapitalization.collectAsStateWithLifecycle()
    val applyDictionary by viewModel.applyDictionary.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { Spacer(modifier = Modifier.height(6.dp)) }

        // Floating Assistant Onboarding Banner / Active Status Pill
        item {
            if (showFloatingAssistantCard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("accessibility_card"),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Brush.linearGradient(listOf(PrimaryColor.copy(alpha = 0.4f), Color.Transparent)))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Text(
                                    text = "Global Floating Dictation",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "Dictate directly into WhatsApp, Slack, Chrome, or Notes with our system floating overlay.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = onOpenAccessibilitySettings,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .pressScale()
                        ) {
                            Text(
                                text = "Enable Assistant",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Text(
                            text = "Floating assistant active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Open accessibility settings",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Active Engine Status Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = PrimaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Active Whisper model",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextMuted,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = selectedModel?.name ?: "No model selected",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(SurfaceCard, RoundedCornerShape(100.dp))
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(100.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${selectedModel?.sizeMb?.toInt() ?: 0} MB • 100% Offline",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextSecondary
                    )
                }
            }
        }

        // Live Transcription Output Canvas
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDark)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isRecording) TertiaryColor.copy(alpha = 0.6f) else GlassBorder
                        ),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(16.dp)
            ) {
                if (liveText.isNotEmpty() || isRecording) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Header Stats Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isRecording) TertiaryColor else Color(0xFF10B981))
                                )
                                Text(
                                    text = if (isRecording) "Live transcription feed" else "Dictation ready",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
                                    color = if (isRecording) TertiaryColor else Color(0xFF10B981)
                                )
                            }

                            val wordsCount = if (liveText.isBlank()) 0 else liveText.trim().split(Regex("\\s+")).size
                            Text(
                                text = "$wordsCount words • ${liveText.length} chars",
                                fontSize = 11.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold
                            )

                            IconButton(
                                onClick = {
                                    if (liveText.isNotBlank()) {
                                        try {
                                            clipboardManager.setText(AnnotatedString(liveText))
                                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                enabled = liveText.isNotBlank(),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy to clipboard",
                                    tint = if (liveText.isBlank()) TextMuted else PrimaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Output Text
                        Text(
                            text = liveText.ifEmpty { "Listening..." },
                            fontSize = 15.sp,
                            color = if (liveText.isEmpty()) TextMuted else TextPrimary,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 8.dp)
                        )

                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = PrimaryColor.copy(alpha = 0.4f),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Tap the mic to start dictating on-device",
                            fontSize = 14.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Speech is transcribed 100% on-device.",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // Dynamic Waveform Audio Visualizer
        item {
            if (isRecording && liveWaveform.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark)
                        .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val spacing = size.width / (liveWaveform.size - 1)
                        val centerY = size.height / 2
                        for (i in liveWaveform.indices) {
                            val x = i * spacing
                            val amp = liveWaveform[i]
                            val barHeight = (size.height * amp * 0.95f).coerceAtLeast(4.dp.toPx())
                            drawLine(
                                color = TertiaryColor,
                                start = Offset(x, centerY - barHeight / 2),
                                end = Offset(x, centerY + barHeight / 2),
                                strokeWidth = 4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        // Hero Pulsing Mic Button
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val pulseScale by if (isRecording) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_anim"
                    )
                } else {
                    remember { mutableFloatStateOf(1f) }
                }

                Box(
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsing Outer Aura Ring
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(126.dp)
                                .clip(CircleShape)
                                .background(TertiaryColor.copy(alpha = 0.2f * pulseScale))
                        )
                    }

                    // Main Button Core
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = if (isRecording) TertiaryColor else PrimaryColor,
                                spotColor = if (isRecording) TertiaryColor else PrimaryColor
                            )
                            .clip(CircleShape)
                            .semantics {
                                role = Role.Button
                                stateDescription = if (isRecording) "Recording" else "Ready"
                                contentDescription = if (isRecording) "Stop recording" else "Start recording"
                            }
                            .clickable { viewModel.toggleRecording() }
                            .background(
                                Brush.linearGradient(
                                    colors = if (isRecording) {
                                        listOf(TertiaryColor, Color(0xFFDC2626))
                                    } else {
                                        listOf(PrimaryColor, AccentViolet)
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                Text(
                    text = if (isRecording) {
                        val min = recordDurationSec / 60
                        val sec = recordDurationSec % 60
                        String.format(Locale.US, "Recording %02d:%02d", min, sec)
                    } else {
                        "Tap to dictate"
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 1.8.sp,
                    color = if (isRecording) TertiaryColor else PrimaryColor,
                    style = Typography.labelSmall.withTabularNumbers(),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        val min = recordDurationSec / 60
                        val sec = recordDurationSec % 60
                        contentDescription = if (isRecording) {
                            "Recording in progress: $min minutes $sec seconds"
                        } else {
                            "Start dictating"
                        }
                    }
                )
            }
        }

        // Post-Processing Filters Summary Card
        item {
            val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
            val useAiPolisher by viewModel.useAiPolisher.collectAsStateWithLifecycle()
            val modelsList by viewModel.modelsList.collectAsStateWithLifecycle()
            val qwenModel = modelsList.find { it.id == "qwen2.5_0.5b" }
            val isQwenDownloaded = qwenModel?.isDownloaded == true
            val isQwenDownloading = qwenModel?.isDownloading == true
            val qwenDownloadProgress by viewModel.downloadProgressFor("qwen2.5_0.5b").collectAsStateWithLifecycle()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Post-Processing Filters",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Button(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor.copy(alpha = 0.2f), contentColor = PrimaryColor),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(text = "Customize", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Active filter chips
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterStatusChip(
                            label = "Smart Pause",
                            active = smartPunctuation,
                            activeColor = PrimaryColor
                        )
                        FilterStatusChip(
                            label = "Auto-Cap",
                            active = autoCapitalization,
                            activeColor = SecondaryColor
                        )
                        FilterStatusChip(
                            label = "Dictionary",
                            active = applyDictionary,
                            activeColor = AccentViolet
                        )
                        FilterStatusChip(
                            label = "Smart Overlay",
                            active = showOnlyOnInput,
                            activeColor = PrimaryColor
                        )
                    }

                    HorizontalDivider(color = GlassBorder)

                    // AI Polisher row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = useAiPolisher,
                                role = Role.Switch,
                                onValueChange = { viewModel.setUseAiPolisher(it) }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Psychology, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(text = "AI Text Polisher", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Cleans up filler words & polishes dictation locally.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = useAiPolisher,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                        )
                    }
                }
            }
        }

        // Language Selection
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Recognition Language",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Setting a specific language avoids the ~300ms auto-detect overhead.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
                val currentLanguage by viewModel.whisperLanguage.collectAsStateWithLifecycle()
                val languageOptions = MainViewModel.LANGUAGE_OPTIONS

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(languageOptions, key = { it.first }) { (code, label) ->
                        val isSelected = currentLanguage == code
                        Surface(
                            onClick = { viewModel.setLanguage(code) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) PrimaryColor.copy(alpha = 0.2f) else BackgroundDark,
                            border = if (isSelected) BorderStroke(1.5.dp, PrimaryColor) else BorderStroke(1.dp, SurfaceLightDark),
                            tonalElevation = 0.dp
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) PrimaryColor else TextSecondary,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 14.dp)
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
private fun FilterStatusChip(
    label: String,
    active: Boolean,
    activeColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (active) activeColor.copy(alpha = 0.15f) else SurfaceCard)
            .border(
                BorderStroke(1.dp, if (active) activeColor.copy(alpha = 0.4f) else GlassBorder),
                RoundedCornerShape(100.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = "$label ${if (active) "ON" else "OFF"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeColor else TextSecondary
        )
    }
}
