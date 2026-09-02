package dev.sebastian.vozlocal.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.polish.QwenEngine.CleanupMode
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel
import java.util.Locale

private val WORD_SPLIT_REGEX = Regex("\\s+")

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
    val haptic = LocalHapticFeedback.current

    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordDurationSec by viewModel.recordDurationSec.collectAsStateWithLifecycle()
    val liveWaveform by viewModel.liveWaveform.collectAsStateWithLifecycle()
    val liveText by viewModel.currentLiveTranscription.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val models by viewModel.modelsList.collectAsStateWithLifecycle()
    val hasDownloadedModel = selectedModel?.isDownloaded == true || models.any { it.isDownloaded }
    val isModelLoading by viewModel.isModelLoading.collectAsStateWithLifecycle()
    val vadDownloadUiState by viewModel.vadDownloadUiState.collectAsStateWithLifecycle()
    var confirmVadDelete by remember { mutableStateOf(false) }

    // Modifier Switches
    val smartPunctuation by viewModel.smartPunctuation.collectAsStateWithLifecycle()
    val autoCapitalization by viewModel.autoCapitalization.collectAsStateWithLifecycle()
    val applyDictionary by viewModel.applyDictionary.collectAsStateWithLifecycle()
    val cleanupMode by viewModel.cleanupMode.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.whisperLanguage.collectAsStateWithLifecycle()

    var showAdvancedDetails by remember { mutableStateOf(false) }
    val isAccessibilityEnabled = isAccessibilityServiceEnabled(
        context,
        dev.sebastian.vozlocal.service.DictationAccessibilityService::class.java
    )

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleRecording()
        } else {
            Toast.makeText(context, "Microphone permission is required to dictate", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        // 1. Top Quick Control Row: Model Pill & Language Pill
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Model indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = PrimaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    val isModelDownloaded = selectedModel?.isDownloaded == true
                    val rawModelName = selectedModel?.name ?: "Whisper Base"
                    val baseModelName = rawModelName.substringBefore(" (")
                    val modelTag = rawModelName.substringAfter(" (", "").removeSuffix(")")
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = baseModelName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!isModelDownloaded) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(Color(0xFFEF4444).copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Not Downloaded",
                                        color = Color(0xFFEF4444),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isModelDownloaded) {
                                "${selectedModel?.sizeMb?.toInt() ?: 78} MB • Ready Offline" + if (modelTag.isNotEmpty()) " • $modelTag" else ""
                            } else {
                                "${selectedModel?.sizeMb?.toInt() ?: 78} MB • Download required"
                            },
                            fontSize = 10.sp,
                            color = if (isModelDownloaded) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF4444),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // VAD indicator + Language pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (vadDownloadUiState.isReady) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "VAD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    // Language Quick Switcher
                    Surface(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "🌐 ${currentLanguage.uppercase()}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 1b. Prominent Model Download Call-to-Action if no model is downloaded yet
        if (!hasDownloadedModel) {
            item {
                val targetModelId = selectedModel?.id ?: "whisper_base"
                val downloadProgress by viewModel.downloadProgressFor(targetModelId).collectAsStateWithLifecycle()
                val downloadStatus by viewModel.downloadStatusFor(targetModelId).collectAsStateWithLifecycle()

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = PrimaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Speech Model Download Required",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "To dictate offline, download '${selectedModel?.name ?: "Whisper Base"}' (${selectedModel?.sizeMb?.toInt() ?: 78} MB). It runs 100% locally on your phone.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                        if (downloadStatus != null && (downloadStatus?.progress ?: 0f) < 1f) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = PrimaryColor,
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                                Text(
                                    text = "${downloadStatus?.statusLabel ?: "Downloading..."} • ${(downloadProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = PrimaryColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Button(
                                onClick = { viewModel.downloadModel(targetModelId) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Download ${selectedModel?.name ?: "Whisper Base"} (78 MB)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Center Stage: Live Transcription Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 190.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    if (isRecording) TertiaryColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 154.dp)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header Status Row
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
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) TertiaryColor else Color(0xFF10B981))
                            )
                            Text(
                                text = if (isRecording) "Listening..." else "Dictation Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) TertiaryColor else Color(0xFF10B981)
                            )
                        }

                        val wordsCount = if (liveText.isBlank()) 0 else liveText.trim().split(WORD_SPLIT_REGEX).size
                        Text(
                            text = "$wordsCount words • ${liveText.length} chars",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Transcript Output Area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                    ) {
                        if (liveText.isNotBlank()) {
                            Text(
                                text = liveText,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 24.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = PrimaryColor.copy(alpha = 0.35f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = if (isRecording) "Speak now..." else "Tap the microphone below to start dictating",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom Action Bar: Copy, Share, Clear
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (liveText.isNotBlank()) {
                            // Copy button
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(liveText))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy text",
                                    tint = PrimaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Share button
                            IconButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, liveText)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Transcription"))
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share text",
                                    tint = SecondaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Clear button
                            IconButton(
                                onClick = {
                                    viewModel.clearLiveTranscription()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear text",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Audio Waveform Display
        item {
            if (isRecording && liveWaveform.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
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
                                strokeWidth = 3.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }
        }

        // 4. Hero Pulsing Mic Button & Live Duration
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
                    // Pulsing Outer Glow Ring
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(CircleShape)
                                .background(TertiaryColor.copy(alpha = 0.22f * pulseScale))
                        )
                    }

                    // Main Microphone Button Core
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = if (isRecording) TertiaryColor else PrimaryColor,
                                spotColor = if (isRecording) TertiaryColor else PrimaryColor
                            )
                            .clip(CircleShape)
                            .pressScale()
                            .semantics {
                                role = Role.Button
                                stateDescription = if (isRecording) "Recording" else "Ready"
                                contentDescription = if (isRecording) "Stop recording" else "Start recording"
                            }
                            .clickable(enabled = !isModelLoading) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    viewModel.toggleRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
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
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isRecording -> {
                            val min = recordDurationSec / 60
                            val sec = recordDurationSec % 60
                            String.format(Locale.US, "Recording %02d:%02d", min, sec)
                        }
                        isModelLoading -> "Loading model..."
                        !hasDownloadedModel -> "Download model to dictate"
                        else -> "Tap to dictate"
                    },
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 1.5.sp,
                    color = when {
                        isRecording -> TertiaryColor
                        !hasDownloadedModel -> Color(0xFFEF4444)
                        else -> PrimaryColor
                    },
                    style = Typography.labelSmall.withTabularNumbers(),
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }
        }

        // 5. Post-Processing Quick Filter Chips
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterStatusChip(
                    label = "Smart Pause",
                    active = smartPunctuation,
                    activeColor = PrimaryColor,
                    onClick = { viewModel.setSmartPunctuation(!smartPunctuation) }
                )
                FilterStatusChip(
                    label = "Auto-Cap",
                    active = autoCapitalization,
                    activeColor = SecondaryColor,
                    onClick = { viewModel.setAutoCapitalization(!autoCapitalization) }
                )
                FilterStatusChip(
                    label = "Dictionary",
                    active = applyDictionary,
                    activeColor = AccentViolet,
                    onClick = { viewModel.setApplyDictionary(!applyDictionary) }
                )
                FilterStatusChip(
                    label = "Cleanup: ${cleanupMode.displayLabel()}",
                    active = cleanupMode != CleanupMode.MINIMAL,
                    activeColor = Color(0xFF10B981),
                    onClick = {
                        val next = when (cleanupMode) {
                            CleanupMode.MINIMAL -> CleanupMode.BALANCED
                            CleanupMode.BALANCED -> CleanupMode.AGGRESSIVE
                            CleanupMode.AGGRESSIVE -> CleanupMode.MINIMAL
                        }
                        viewModel.setCleanupMode(next)
                    }
                )
            }
        }

        // 6. Secondary / Advanced Details Toggle
        item {
            TextButton(
                onClick = { showAdvancedDetails = !showAdvancedDetails },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = if (showAdvancedDetails) "Hide Assistant & VAD info ▴" else "Show Assistant & VAD info ▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 7. Collapsible Advanced Cards (Assistant + Silero VAD)
        if (showAdvancedDetails) {
            // Floating Microphone Button Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Global Floating Microphone",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(
                                            if (isAccessibilityEnabled) Color(0xFF10B981).copy(alpha = 0.15f)
                                            else PrimaryColor.copy(alpha = 0.15f)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isAccessibilityEnabled) "Active" else "Setup Needed",
                                        color = if (isAccessibilityEnabled) Color(0xFF10B981) else PrimaryColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                            Text(
                                text = "Shows a floating microphone button alongside your traditional keyboard in WhatsApp, Slack, Notes, or Chrome.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = onOpenAccessibilitySettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAccessibilityEnabled) SecondaryColor else PrimaryColor
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isAccessibilityEnabled) "Settings" else "Enable",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Silero VAD Card
            item {
                if (confirmVadDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmVadDelete = false },
                        title = { Text("Delete VAD model?") },
                        text = { Text("This removes the small Silero VAD file from local storage. You can download it again later.") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.deleteVadModel()
                                confirmVadDelete = false
                            }) { Text("Delete", color = TertiaryColor) }
                        },
                        dismissButton = { TextButton(onClick = { confirmVadDelete = false }) { Text("Cancel") } }
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Silero VAD Silence Skipping",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (vadDownloadUiState.isReady) "Installed (~0.9 MB). Silence is skipped." else "Skips empty speech frames for faster transcription.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (vadDownloadUiState.isReady) {
                            OutlinedButton(
                                onClick = { confirmVadDelete = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TertiaryColor),
                                border = BorderStroke(1.dp, TertiaryColor.copy(alpha = 0.4f))
                            ) {
                                Text("Delete", fontSize = 11.sp)
                            }
                        } else {
                            Button(
                                onClick = { viewModel.downloadVadModel() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Download", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private fun CleanupMode.displayLabel(): String = when (this) {
    CleanupMode.MINIMAL -> "Minimal"
    CleanupMode.BALANCED -> "Balanced"
    CleanupMode.AGGRESSIVE -> "Aggressive"
}

@Composable
private fun FilterStatusChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100.dp),
        color = if (active) activeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) activeColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = "$label ${if (active) "ON" else "OFF"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
