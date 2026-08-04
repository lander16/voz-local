package com.example.ui.screens

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DictationModel
import com.example.data.model.DictionaryWord
import com.example.data.model.TranscriptionHistory
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

/** Custom Compose modifier for subtle, responsive tactile press feedback */
@Composable
fun Modifier.pressScale(): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press_scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}


enum class Tab {
    DICTATE, STATS, MODELS, DICTIONARY, HISTORY, SHARED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    initialTab: Tab = Tab.DICTATE
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(initialTab) }

    // Observe shared audio loaded intent to automatically navigate to the SHARED tab
    val sharedUri by viewModel.sharedAudioUri.collectAsStateWithLifecycle()
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            activeTab = Tab.SHARED
        }
    }

    // Permission check & tracking state
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasMicPermission by remember { mutableStateOf(false) }
    var hasAccessibilityEnabled by remember { mutableStateOf(false) }
    var bypassSetup by remember { mutableStateOf(false) }

    fun checkAllPermissions() {
        hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasAccessibilityEnabled = isAccessibilityServiceEnabled(
            context,
            com.example.service.DictationAccessibilityService::class.java
        )
    }

    // Check permissions on start and whenever the app resumes
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                checkAllPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Mic permission request launcher
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice dictation.", Toast.LENGTH_LONG).show()
        }
    }

    val isSetupComplete = hasMicPermission && hasAccessibilityEnabled
    val showSetupWizard = !isSetupComplete && !bypassSetup

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var showSettingsSheet by remember { mutableStateOf(false) }

    if (showSetupWizard) {
        SetupWizardScreen(
            viewModel = viewModel,
            hasMicPermission = hasMicPermission,
            hasAccessibilityEnabled = hasAccessibilityEnabled,
            onRequestMicPermission = {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onEnableAccessibility = {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                    Toast.makeText(context, "Locate 'VozLocal Floating Dictation' and toggle ON", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                }
            },
            onSkip = {
                bypassSetup = true
                Toast.makeText(context, "You can configure permissions in settings later", Toast.LENGTH_SHORT).show()
            },
            onDone = {
                bypassSetup = true
                Toast.makeText(context, "Setup completed successfully", Toast.LENGTH_SHORT).show()
            }
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = SurfaceDark,
                    drawerContentColor = TextPrimary,
                    modifier = Modifier.width(300.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Drawer Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Brush.linearGradient(listOf(PrimaryColor, SecondaryColor))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hearing,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "VozLocal AI",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "On-Device Dictation Studio",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        HorizontalDivider(color = GlassBorder)

                        // Drawer Navigation Items - Secondary Tools & Analytics
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "TOOLS & ANALYTICS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextMuted,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                            )

                            val drawerItems = listOf(
                                Triple(Tab.DICTIONARY, "Custom Dictionary", Icons.Default.Book),
                                Triple(Tab.STATS, "Statistics & Analytics", Icons.Default.BarChart)
                            )

                            drawerItems.forEach { (tab, label, icon) ->
                                val selected = activeTab == tab
                                NavigationDrawerItem(
                                    icon = { Icon(imageVector = icon, contentDescription = null, tint = if (selected) Color.White else TextSecondary) },
                                    label = { Text(text = label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp) },
                                    selected = selected,
                                    onClick = {
                                        activeTab = tab
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    colors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = PrimaryColor,
                                        selectedTextColor = Color.White,
                                        unselectedContainerColor = Color.Transparent,
                                        unselectedTextColor = TextSecondary
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = GlassBorder)

                        // App Preferences Action
                        NavigationDrawerItem(
                            icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = TextPrimary) },
                            label = { Text(text = "App Settings & Preferences", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
                            selected = false,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                showSettingsSheet = true
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = SurfaceCard,
                                unselectedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = { coroutineScope.launch { drawerState.open() } },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Open Drawer Menu",
                                    tint = TextPrimary
                                )
                            }
                        },
                        title = {
                            val titleText = when (activeTab) {
                                Tab.DICTATE -> "Live Dictate"
                                Tab.MODELS -> "AI Speech Models"
                                Tab.DICTIONARY -> "Custom Dictionary"
                                Tab.HISTORY -> "Transcription History"
                                Tab.SHARED -> "Shared Audio File"
                                Tab.STATS -> "Statistics & Analytics"
                            }
                            Text(
                                text = titleText,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp,
                                color = TextPrimary
                            )
                        },
                        actions = {
                            IconButton(
                                onClick = { showSettingsSheet = true },
                                modifier = Modifier
                                    .testTag("settings_button")
                                    .heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Open App Settings",
                                    tint = TextPrimary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BackgroundDark,
                            titleContentColor = TextPrimary
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = SurfaceDark,
                        tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                val tabs = listOf(
                    Triple(Tab.DICTATE, "Dictate", Icons.Default.KeyboardVoice),
                    Triple(Tab.MODELS, "Models", Icons.Default.CloudDownload),
                    Triple(Tab.SHARED, "Shared", Icons.Default.AudioFile),
                    Triple(Tab.HISTORY, "History", Icons.Default.History)
                )

                tabs.forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Icon(imageVector = icon, contentDescription = label) },
                        label = { Text(text = label, fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = PrimaryColor,
                            indicatorColor = PrimaryColor.copy(alpha = 0.35f),
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                Tab.DICTATE -> DictateTab(viewModel)
                Tab.STATS -> StatsTab(viewModel)
                Tab.MODELS -> ModelsTab(viewModel)
                Tab.DICTIONARY -> DictionaryTab(viewModel)
                Tab.HISTORY -> HistoryTab(viewModel)
                Tab.SHARED -> SharedAudioTab(viewModel)
            }
        }
    }
    }

    if (showSettingsSheet) {
        SettingsSheet(viewModel = viewModel, onDismiss = { showSettingsSheet = false })
    }
}
}

// ==================== DICTATE TAB ====================
@Composable
fun DictateTab(viewModel: MainViewModel) {
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

        // Hero Floating Assistant Onboarding Banner
        item {
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
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                                Toast.makeText(context, "Locate 'VozLocal Floating Dictation' and toggle ON", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                            }
                        },
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
                            text = "ACTIVE WHISPER MODEL",
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
                                    text = if (isRecording) "LIVE TRANSCRIPTION FEED" else "DICTATION READY",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
                                    color = if (isRecording) TertiaryColor else Color(0xFF10B981)
                                )
                            }

                            val wordsCount = if (liveText.isBlank()) 0 else liveText.trim().split(Regex("\\s+")).size
                            Text(
                                text = "$wordsCount words • ${liveText.length} chars",
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold
                            )
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

                        // Bottom Actions Row
                        if (!isRecording && liveText.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        try {
                                            clipboardManager.setText(AnnotatedString(liveText))
                                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor.copy(alpha = 0.2f), contentColor = PrimaryColor),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .pressScale()
                                        .testTag("copy_transcription_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy to Clipboard",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = "Copy Text", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
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
                            text = "Tap mic button to dictate on-device",
                            fontSize = 14.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Speech is processed 100% on-device with near-zero latency.",
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
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.25f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_anim"
                )

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
                                        listOf(TertiaryColor, Color(0xFFD97706))
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
                        String.format(Locale.US, "RECORDING  %02d:%02d", min, sec)
                    } else {
                        "TAP TO DICTATE"
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

        // Post-Processing Filters Card
        item {
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
                    Text(
                        text = "AI Post-Processing Filters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )

                    // Modifier 1: Smart Punctuation
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = smartPunctuation,
                                role = Role.Switch,
                                onValueChange = { viewModel.smartPunctuation.value = it }
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
                            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Column {
                                Text(text = "Smart Pause Correction", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Fuses long speech pauses into logical punctuation.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = smartPunctuation,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                        )
                    }

                    // Modifier 2: Auto capitalization
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = autoCapitalization,
                                role = Role.Switch,
                                onValueChange = { viewModel.autoCapitalization.value = it }
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
                            Icon(imageVector = Icons.Default.FormatSize, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Column {
                                Text(text = "Auto-Capitalization", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Starts sentences with uppercase automatically.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = autoCapitalization,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                        )
                    }

                    // Modifier 3: Dictionary replacements
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = applyDictionary,
                                role = Role.Switch,
                                onValueChange = { viewModel.applyDictionary.value = it }
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
                            Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Column {
                                Text(text = "Apply Local Dictionary", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Corrects common misspellings with custom words.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = applyDictionary,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor)
                        )
                    }

                    // Modifier 4: Show only on text input
                    val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = showOnlyOnInput,
                                role = Role.Switch,
                                onValueChange = { viewModel.setShowOnlyOnInput(it) }
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
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = null, tint = PrimaryColor, modifier = Modifier.size(20.dp))
                            Column {
                                Text(text = "Smart Overlay Button", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Only show floating button when clicking text fields.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        Switch(
                            checked = showOnlyOnInput,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PrimaryColor),
                            modifier = Modifier.testTag("main_only_on_input_switch")
                        )
                    }

                    // Modifier 5: AI Post-Processing (Qwen 0.5B)
                    val modelsList by viewModel.modelsList.collectAsStateWithLifecycle()
                    val qwenModel = modelsList.find { it.id == "qwen2.5_0.5b" }
                    val isQwenDownloaded = qwenModel?.isDownloaded == true
                    val isQwenDownloading = qwenModel?.isDownloading == true
                    val useAiPolisher by viewModel.useAiPolisher.collectAsStateWithLifecycle()

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
                                Text(text = "AI Text Polisher (Qwen 0.5B)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(text = "Cleans up filler words & polishes dictation with local LLM.", fontSize = 11.sp, color = TextSecondary)
                                
                                Surface(
                                    onClick = {
                                        if (!isQwenDownloaded && !isQwenDownloading) {
                                            viewModel.downloadModel("qwen2.5_0.5b")
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        isQwenDownloaded -> PrimaryColor.copy(alpha = 0.15f)
                                        isQwenDownloading -> SecondaryColor.copy(alpha = 0.15f)
                                        else -> TertiaryColor.copy(alpha = 0.15f)
                                    },
                                    modifier = Modifier.pressScale()
                                ) {
                                    Text(
                                        text = when {
                                            isQwenDownloaded -> "Model Ready (398 MB)"
                                            isQwenDownloading -> "Downloading... ${(qwenModel?.downloadProgress?.times(100))?.toInt()}%"
                                            else -> "Download Model (398 MB)"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            isQwenDownloaded -> PrimaryColor
                                            isQwenDownloading -> SecondaryColor
                                            else -> TertiaryColor
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
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

// ==================== MODELS TAB ====================
@Composable
fun ModelsTab(viewModel: MainViewModel) {
    val models by viewModel.modelsList.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Local Speech Models",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    text = "Download and activate GGUF/bin Whisper models directly onto your internal storage. Runs 100% offline.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        items(models, key = { it.id }) { model ->
            ModelCard(
                model = model,
                onSelect = { viewModel.selectModel(model.id) },
                onDownload = { viewModel.downloadModel(model.id) },
                onDelete = { viewModel.deleteModel(model.id) },
                onRedownload = { viewModel.redownloadModel(model.id) }
            )
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun ModelCard(
    model: DictationModel,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRedownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = model.isDownloaded) { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (model.isSelected) SurfaceLightDark else SurfaceDark
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(
                    if (model.isSelected) PrimaryColor else SecondaryColor.copy(alpha = 0.2f),
                    Color.Transparent
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title & Selected Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (model.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (model.isSelected) PrimaryColor else TextSecondary
                    )
                    Text(
                        text = model.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .background(SurfaceLightDark, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${model.sizeMb.toInt()} MB",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryColor
                    )
                }
            }

            // Accuracy and Speed Ratings Layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Spanish accuracy
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "SPANISH ACCURACY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { model.accuracySpanish / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = PrimaryColor,
                            trackColor = Color.DarkGray
                        )
                        Text(text = "${model.accuracySpanish}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }

                // Speed factor
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "MOBILE DECODING SPEED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${model.speedMultiplier}x",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (model.speedMultiplier > 4f) PrimaryColor else SecondaryColor
                        )
                        Text(text = "multiplier", fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }

            // Downloader Status / Operation Bar
            if (model.isDownloading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading model weights...",
                            fontSize = 11.sp,
                            color = PrimaryColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${(model.downloadProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = PrimaryColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { model.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = PrimaryColor,
                        trackColor = Color.DarkGray
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (model.isDownloaded) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.heightIn(min = 44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete ${model.name}",
                                    tint = TertiaryColor
                                )
                            }
                            IconButton(
                                onClick = onRedownload,
                                modifier = Modifier.heightIn(min = 44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Re-download ${model.name}",
                                    tint = PrimaryColor
                                )
                            }
                        }

                        Button(
                            onClick = onSelect,
                            enabled = !model.isSelected,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (model.isSelected) Color.Transparent else PrimaryColor,
                                disabledContainerColor = SurfaceLightDark
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .pressScale()
                        ) {
                            Text(
                                text = if (model.isSelected) "Active" else "Activate",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (model.isSelected) TextSecondary else Color.Black
                            )
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryColor),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(36.dp)
                                .pressScale()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                                Text(
                                    text = "Download Model",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== DICTIONARY TAB ====================
@Composable
fun DictionaryTab(viewModel: MainViewModel) {
    val words by viewModel.dictionaryWords.collectAsStateWithLifecycle()
    var inputWord by remember { mutableStateOf("") }
    var inputReplacement by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Personal Dictation Dictionary",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    text = "Add custom words, proper names, or jargon you use often and want the model to recognize. They are sent directly to the local model's biasing vocabulary list. You can also specify common misheard variations to automatically replace them.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        // Add custom word card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Add Word to Recognize",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = inputWord,
                            onValueChange = { inputWord = it },
                            label = { Text("Word / Phrase to Recognize") },
                            placeholder = { Text("e.g. VozLocal") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryColor,
                                unfocusedBorderColor = Color.DarkGray,
                                focusedLabelColor = PrimaryColor
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = inputReplacement,
                            onValueChange = { inputReplacement = it },
                            label = { Text("Phonetic / Misheard variants (optional)") },
                            placeholder = { Text("e.g. voz local, voice local, bos local") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryColor,
                                unfocusedBorderColor = Color.DarkGray,
                                focusedLabelColor = PrimaryColor
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            if (inputWord.isNotBlank()) {
                                viewModel.addWord(inputWord, inputReplacement)
                                inputWord = ""
                                inputReplacement = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Add Custom Word",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recognized Words (${words.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
        }

        if (words.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Book,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No custom words saved yet.",
                            fontSize = 13.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(words, key = { it.id }) { word ->
                DictionaryRow(word = word, onDelete = { viewModel.deleteWord(word.id) })
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun DictionaryRow(word: DictionaryWord, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Word / Phrase to Recognize",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
                Text(
                    text = word.word,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryColor
                )

                if (word.replacement.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Phonetic variations: ${word.replacement}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        style = androidx.compose.ui.text.TextStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    )
                } else {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Auto-correction & spelling bias active",
                        fontSize = 11.sp,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete dictionary word",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ==================== HISTORY TAB ====================
@Composable
fun HistoryTab(viewModel: MainViewModel) {
    val history by viewModel.transcriptionHistory.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Transcription History",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Review and copy offline dictations and transcribed shared files.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearHistory() },
                        colors = ButtonDefaults.textButtonColors(contentColor = TertiaryColor),
                        modifier = Modifier.pressScale()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Clear History", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.HistoryToggleOff,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.3f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No transcript history available.",
                            fontSize = 14.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(history, key = { it.id }) { item ->
                HistoryCard(
                    item = item,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(item.text))
                        Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { viewModel.deleteHistoryItem(item.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun HistoryCard(
    item: TranscriptionHistory,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault()) }
    val dateStr = formatter.format(Date(item.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Icon, Type, Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (item.type == "shared_file") Icons.Default.AudioFile else Icons.Default.KeyboardVoice,
                        contentDescription = null,
                        tint = if (item.type == "shared_file") SecondaryColor else PrimaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (item.type == "shared_file") "SHARED FILE" else "DICTATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (item.type == "shared_file") SecondaryColor else PrimaryColor,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = dateStr,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            if (!item.fileName.isNullOrEmpty()) {
                Text(
                    text = "File: ${item.fileName}",
                    fontSize = 12.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Transcribed Text Block
            Text(
                text = item.text,
                fontSize = 14.sp,
                color = TextPrimary,
                lineHeight = 20.sp
            )

            // Bottom bar: model info, duration, actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // model label
                    Box(
                        modifier = Modifier
                            .background(SurfaceLightDark, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.modelUsed,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // duration
                    if (item.durationSec > 0) {
                        Text(
                            text = "${item.durationSec}s duration",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete history log",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy transcription text",
                            tint = PrimaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== SHARED AUDIO TAB ====================
@Composable
fun SharedAudioTab(viewModel: MainViewModel) {
    val audioUri by viewModel.sharedAudioUri.collectAsStateWithLifecycle()
    val audioName by viewModel.sharedAudioName.collectAsStateWithLifecycle()
    val audioSize by viewModel.sharedAudioSize.collectAsStateWithLifecycle()
    val transcribing by viewModel.isSharedTranscribing.collectAsStateWithLifecycle()
    val progress by viewModel.sharedProgress.collectAsStateWithLifecycle()
    val statusText by viewModel.sharedStatusText.collectAsStateWithLifecycle()
    val resultText by viewModel.sharedResultText.collectAsStateWithLifecycle()
    val activeModel by viewModel.selectedModel.collectAsStateWithLifecycle()

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.setSharedAudio(context, uri)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Shared Audio Transcriber",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    text = "Transcribe voice notes from WhatsApp/Telegram, or select local audio files directly from your phone storage.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        // File Selection / Audio Picker Card
        item {
            if (audioUri == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filePickerLauncher.launch("audio/*") },
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.5.dp, PrimaryColor.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(PrimaryColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = PrimaryColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Text(
                            text = "Select Local Audio File",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = "Tap to pick any .wav, .mp3, .m4a, or .ogg file from storage, or share a voice note directly from another app.",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { filePickerLauncher.launch("audio/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Browse Storage Files", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PrimaryColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = PrimaryColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = audioName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Size: ${audioSize.ifBlank { "Loading…" }}",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        IconButton(onClick = { viewModel.clearSharedFile() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear selected shared file",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Transcription progress / controls
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (transcribing) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(56.dp),
                                color = PrimaryColor,
                                trackColor = Color.DarkGray,
                                strokeWidth = 5.dp
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = PrimaryColor
                            )
                            Text(
                                text = statusText,
                                fontSize = 12.sp,
                                color = TextPrimary,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (resultText.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "TRANSCRIPTION COMPLETED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = PrimaryColor,
                                    letterSpacing = 1.sp
                                )

                                Row {
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(resultText))
                                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy text",
                                            tint = PrimaryColor
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceLightDark)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = resultText,
                                    fontSize = 14.sp,
                                    color = TextPrimary,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Ready to Transcribe",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Active Transcription Model: ${activeModel?.name ?: "No model selected"}",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )

                            Button(
                                onClick = { viewModel.startSharedTranscription() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black
                                    )
                                    Text(
                                        text = "Start Offline Transcription",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                        color = Color.Black
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // How it works info card — fills empty space, provides guidance when idle
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "HOW IT WORKS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryColor,
                        letterSpacing = 1.2.sp
                    )
                    listOf(
                        Pair(Icons.Default.Share, "Share any audio from WhatsApp, Telegram, or Voice Memos — or pick a local file above."),
                        Pair(Icons.Default.GraphicEq, "VozLocal processes the audio 100% on-device using your active Whisper model."),
                        Pair(Icons.Default.ContentCopy, "Copy the completed transcript to your clipboard in one tap.")
                    ).forEach { (icon, stepText) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = PrimaryColor.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Text(
                                text = stepText,
                                fontSize = 13.sp,
                                color = TextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// ==================== SETTINGS MODAL SHEET ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val historyLimit by viewModel.historyLimit.collectAsStateWithLifecycle()
    val smartPunctuation by viewModel.smartPunctuation.collectAsStateWithLifecycle()
    val autoCapitalization by viewModel.autoCapitalization.collectAsStateWithLifecycle()
    val applyDictionary by viewModel.applyDictionary.collectAsStateWithLifecycle()
    val useAiPolisher by viewModel.useAiPolisher.collectAsStateWithLifecycle()
    val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
    val whisperLanguage by viewModel.whisperLanguage.collectAsStateWithLifecycle()

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

            // Section 1: Audio & Transcription Language
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TRANSCRIPTION ENGINE LANGUAGE",
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

            // Section 2: Post-Processing & AI Polisher
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "TEXT POST-PROCESSING & LOCAL AI",
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
                            onValueChange = { viewModel.smartPunctuation.value = it }
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
                            onValueChange = { viewModel.autoCapitalization.value = it }
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
                            onValueChange = { viewModel.applyDictionary.value = it }
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
                                Text(text = "LLM", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = AccentViolet)
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

            // Section 3: System Overlay Assistant
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "FLOATING ASSISTANT OVERLAY",
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

            // Section 4: History Retention Limits
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "HISTORY STORAGE RETENTION LIMIT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PrimaryColor,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Configure maximum history items retained in your local SQLite database.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                Text(text = "Save Preferences", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
    }
}

// ==================== STATS TAB ====================
@Composable
fun StatsTab(viewModel: MainViewModel) {
    val stats by viewModel.dictationStats.collectAsStateWithLifecycle()

    // Base calculations
    val totalWords = stats.sumOf { it.wordCount }
    val totalSeconds = stats.sumOf { it.durationSec }
    val totalMinutes = totalSeconds / 60f
    
    val averageWpm = if (stats.isNotEmpty()) {
        stats.map { it.wpm }.average().toFloat()
    } else {
        0f
    }

    // Today calculations
    val calendar = Calendar.getInstance()
    val todayMidnight = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val statsToday = stats.filter { it.timestamp >= todayMidnight }
    val todayWords = statsToday.sumOf { it.wordCount }
    val todayAvgWpm = if (statsToday.isNotEmpty()) statsToday.map { it.wpm }.average().toFloat() else 0f

    // Week calculations (last 7 days)
    val sdf = SimpleDateFormat("EEE", Locale.getDefault())
    val last7DaysData = (0..6).map { offset ->
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -offset) }
        val dateString = sdf.format(cal.time)
        val dayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000
        val dayStats = stats.filter { it.timestamp in dayStart until dayEnd }
        val wordCount = dayStats.sumOf { it.wordCount }
        dateString to wordCount
    }.reversed()

    val maxWordCount = last7DaysData.maxOfOrNull { it.second } ?: 1
    val displayMax = if (maxWordCount > 0) maxWordCount else 100

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Offline Dictation Analytics",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    text = "Track your typing efficiency, dictation word count and verbal speech rate (WPM) processed locally on device.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }

        // Metrics Row cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Card 1: Today's Words
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(PrimaryColor.copy(alpha = 0.4f), Color.Transparent)))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "TODAY'S WORDS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Text(text = todayWords.toString(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor)
                        Text(
                            text = String.format(Locale.US, "%.1f min duration", statsToday.sumOf { it.durationSec } / 60f),
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Card 2: Average WPM
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(SecondaryColor.copy(alpha = 0.4f), Color.Transparent)))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "VERBAL SPEED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = String.format(Locale.US, "%.0f", averageWpm), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = SecondaryColor)
                            Text(text = "WPM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SecondaryColor)
                        }
                        Text(
                            text = if (todayAvgWpm > 0f) String.format(Locale.US, "%.0f WPM today", todayAvgWpm) else "No dictations today",
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Bar Chart Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Weekly Dictation Trend",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Total words dictated per day",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                            contentDescription = null,
                            tint = PrimaryColor
                        )
                    }

                    // Render Bars
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        last7DaysData.forEach { (day, wordCount) ->
                            val normalizedHeightRatio = wordCount.toFloat() / displayMax.toFloat()
                            val barHeight = (normalizedHeightRatio * 90).coerceAtLeast(6f).dp

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (wordCount > 0) {
                                    Text(
                                        text = wordCount.toString(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(14.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(PrimaryColor, SecondaryColor)
                                            )
                                        )
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = day,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // Lifetime Metrics Panel
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Lifetime Milestones",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Stat Item 1
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "LIFETIME WORDS", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(text = totalWords.toString(), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        }

                        // Stat Item 2
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "TOTAL SPEAK TIME", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format(Locale.US, "%.1f min", totalMinutes),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        }

                        // Stat Item 3
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "DICTATIONS", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(text = stats.size.toString(), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        }
                    }

                    HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f))

                    // Helpful Insight
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SecondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        val insightsText = when {
                            stats.isEmpty() -> "Start dictating to see speech performance analytics here."
                            averageWpm > 150f -> "Your speech rate is fast! Whisper models might require clear pronunciation."
                            averageWpm > 110f -> "Excellent verbal delivery speed! This is close to standard professional presentation pace."
                            else -> "Great pace. Speaking deliberately improves transcription accuracy of local neural models."
                        }
                        Text(
                            text = insightsText,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // Action Buttons
        if (stats.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { viewModel.clearStats() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TertiaryColor),
                    modifier = Modifier.pressScale()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(text = "Reset Statistics", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

// ==================== SETUP WIZARD SCREEN ====================
@Composable
fun SetupWizardScreen(
    viewModel: MainViewModel,
    hasMicPermission: Boolean,
    hasAccessibilityEnabled: Boolean,
    onRequestMicPermission: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onSkip: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        // Beautiful Pulsing Mic Header
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(PrimaryColor, SecondaryColor)))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Hearing,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp)
            )
        }
        
        Text(
            text = "Welcome to VozLocal",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "VozLocal provides on-device, fully offline speech-to-text with post-processing. Let's finish the setup to start dictating globally!",
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Step 1: Microphone Permission
        SetupStepCard(
            title = "1. Microphone Permission",
            description = "Allows recording voice for processing with Whisper local models.",
            isGranted = hasMicPermission,
            buttonText = "Grant Microphone Permission",
            onAction = onRequestMicPermission,
            testTag = "setup_step_mic"
        )
        
        // Step 2: Accessibility Service
        SetupStepCard(
            title = "2. Accessibility Service",
            description = "Enables detecting input fields globally to draw the floating dictation button and paste typed text.",
            isGranted = hasAccessibilityEnabled,
            buttonText = "Enable Accessibility Service",
            onAction = onEnableAccessibility,
            testTag = "setup_step_accessibility"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (hasMicPermission && hasAccessibilityEnabled) {
            // Configuration Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup_config_card"),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = PrimaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Overlay Button Preference",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "VozLocal can automatically hide the floating overlay button when no text input is focused, ensuring your screen remains clean and clutter-free.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Show button ONLY on clicking inputs",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        val showOnlyOnInput by viewModel.showOnlyOnInput.collectAsStateWithLifecycle()
                        Switch(
                            checked = showOnlyOnInput,
                            onCheckedChange = { viewModel.setShowOnlyOnInput(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = PrimaryColor,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = Color.DarkGray
                            ),
                            modifier = Modifier.testTag("setup_only_on_input_switch")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .pressScale()
                    .testTag("setup_done_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black)
                    Text("Start Using VozLocal", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 14.sp)
                }
            }
        } else {
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                modifier = Modifier.testTag("setup_skip_button")
            ) {
                Text("Skip Setup & Explore App", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SetupStepCard(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onAction: () -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(
                    if (isGranted) Color(0xFF10B981).copy(alpha = 0.5f) else SecondaryColor.copy(alpha = 0.3f),
                    Color.Transparent
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                
                if (isGranted) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "ACTIVE",
                                color = Color(0xFF10B981),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFEF4444).copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "REQUIRED",
                            color = Color(0xFFEF4444),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
            
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 16.sp
            )
            
            if (!isGranted) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = buttonText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(
    context: Context,
    service: Class<out android.accessibilityservice.AccessibilityService>
): Boolean {
    val expectedComponentName = ComponentName(context, service)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}
