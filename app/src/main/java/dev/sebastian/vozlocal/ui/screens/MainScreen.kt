package dev.sebastian.vozlocal.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

enum class Tab {
    DICTATE,
    MODELS,
    DICTIONARY,
    HISTORY,
    SHARED,
    STATS
}

/**
 * Reusable press scale micro-interaction modifier for buttons and interactive cards.
 * Uses the InteractionSource provided by the enclosing clickable (Button, Surface, etc.)
 * so it doesn't add its own clickable and won't swallow events.
 */
fun Modifier.pressScale(
    targetScale: Float = 0.95f,
    interactionSource: MutableInteractionSource? = null
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "press_scale_anim"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    initialTab: Tab = Tab.DICTATE
) {
    val context = LocalContext.current
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600
    var activeTab by remember { mutableStateOf(initialTab) }

    // Theme mode is collected here so MainScreen can re-apply the theme.
    // "system" is resolved inside MyApplicationTheme via isSystemInDarkTheme().
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

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
    var skippedSetup by remember { mutableStateOf(false) }

    fun checkAllPermissions() {
        hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        hasAccessibilityEnabled = isAccessibilityServiceEnabled(
            context,
            dev.sebastian.vozlocal.service.DictationAccessibilityService::class.java
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

    val models by viewModel.modelsList.collectAsStateWithLifecycle()
    val hasDownloadedModel = models.any { it.isDownloaded }
    val isSetupComplete = hasMicPermission && hasAccessibilityEnabled && hasDownloadedModel
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
                skippedSetup = true
                Toast.makeText(context, "You can configure permissions in settings later", Toast.LENGTH_SHORT).show()
            },
            onDone = {
                bypassSetup = true
                skippedSetup = false
                Toast.makeText(context, "Setup completed successfully", Toast.LENGTH_SHORT).show()
            }
        )
    } else {
        // Re-apply the theme from the ViewModel (nested theme override).
        MyApplicationTheme(themeMode = themeMode) {
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
                                text = "Tools & Analytics",
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
                            if (!isWideLayout) {
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
                            } else {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        },
                        title = {
                            val titleText = when (activeTab) {
                                Tab.DICTATE -> "Dictate"
                                Tab.MODELS -> "Models"
                                Tab.DICTIONARY -> "Dictionary"
                                Tab.HISTORY -> "History"
                                Tab.SHARED -> "Shared"
                                Tab.STATS -> "Stats"
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
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                },
                bottomBar = {
                    if (!isWideLayout) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
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
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isWideLayout) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(
                                containerColor = BackgroundDark,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                listOf(
                                    Triple(Tab.DICTATE, "Dictate", Icons.Default.KeyboardVoice),
                                    Triple(Tab.MODELS, "Models", Icons.Default.CloudDownload),
                                    Triple(Tab.SHARED, "Shared", Icons.Default.AudioFile),
                                    Triple(Tab.HISTORY, "History", Icons.Default.History),
                                    Triple(Tab.DICTIONARY, "Dictionary", Icons.Default.Book),
                                    Triple(Tab.STATS, "Stats", Icons.Default.BarChart)
                                ).forEach { (tab, label, icon) ->
                                    NavigationRailItem(
                                        selected = activeTab == tab,
                                        onClick = { activeTab = tab },
                                        icon = { Icon(imageVector = icon, contentDescription = label) },
                                        label = { Text(text = label) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = Color.White,
                                            selectedTextColor = PrimaryColor,
                                            indicatorColor = PrimaryColor.copy(alpha = 0.35f),
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                MainTabContent(
                                    viewModel = viewModel,
                                    activeTab = activeTab,
                                    showSetupRetryBanner = skippedSetup,
                                    showFloatingAssistantCard = !hasAccessibilityEnabled,
                                    onOpenAccessibilitySettings = {
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            context.startActivity(intent)
                                            Toast.makeText(context, "Locate 'VozLocal Floating Dictation' and toggle ON", Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onOpenSettings = { showSettingsSheet = true },
                                    onReuseHistory = { draft ->
                                        viewModel.loadHistoryDraft(draft)
                                        activeTab = Tab.DICTATE
                                    },
                                    onGrantPermissions = {
                                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        try {
                                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDismissRetry = { skippedSetup = false }
                                )
                            }
                        }
                    } else {
                        MainTabContent(
                            viewModel = viewModel,
                            activeTab = activeTab,
                            showSetupRetryBanner = skippedSetup,
                            showFloatingAssistantCard = !hasAccessibilityEnabled,
                            onOpenAccessibilitySettings = {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                    Toast.makeText(context, "Locate 'VozLocal Floating Dictation' and toggle ON", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenSettings = { showSettingsSheet = true },
                            onReuseHistory = { draft ->
                                viewModel.loadHistoryDraft(draft)
                                activeTab = Tab.DICTATE
                            },
                            onGrantPermissions = {
                                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDismissRetry = { skippedSetup = false }
                        )
                    }
                }
            }
        }

        if (showSettingsSheet) {
            SettingsSheet(viewModel = viewModel, onDismiss = { showSettingsSheet = false })
        }
    }
}

}

@Composable
private fun MainTabContent(
    viewModel: MainViewModel,
    activeTab: Tab,
    showSetupRetryBanner: Boolean,
    showFloatingAssistantCard: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onReuseHistory: (String) -> Unit,
    onGrantPermissions: () -> Unit,
    onDismissRetry: () -> Unit
) {
    if (showSetupRetryBanner) {
        SetupRetryBanner(
            onGrantPermissions = onGrantPermissions,
            onDismiss = onDismissRetry
        )
    }

    AnimatedContent(
        targetState = activeTab,
        transitionSpec = {
            fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
        },
        label = "tab_content_anim"
    ) { targetTab ->
        when (targetTab) {
            Tab.DICTATE -> DictateTab(
                viewModel = viewModel,
                showFloatingAssistantCard = showFloatingAssistantCard,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenSettings = onOpenSettings
            )
            Tab.MODELS -> ModelsTab(viewModel)
            Tab.STATS -> StatsTab(viewModel)
            Tab.DICTIONARY -> DictionaryTab(viewModel)
            Tab.HISTORY -> HistoryTab(
                viewModel = viewModel,
                onReuse = onReuseHistory
            )
            Tab.SHARED -> SharedAudioTab(viewModel)
        }
    }
}

@Composable
private fun SetupRetryBanner(
    onGrantPermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PrimaryColor.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = PrimaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Finish setup to dictate anywhere",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Microphone and accessibility permissions are needed for the floating assistant.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Dismiss", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onGrantPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(text = "Grant", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black)
                }
            }
        }
    }
}
