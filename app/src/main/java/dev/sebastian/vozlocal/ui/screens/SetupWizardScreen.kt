package dev.sebastian.vozlocal.ui.screens

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel

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

        val completionProgress = (if (hasMicPermission) 0.5f else 0f) + (if (hasAccessibilityEnabled) 0.5f else 0f)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Setup progress", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Text(text = "${(completionProgress * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor)
                }
                LinearProgressIndicator(
                    progress = { completionProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = PrimaryColor,
                    trackColor = SurfaceCard
                )
                Text(
                    text = "Only the mic and accessibility permission are needed. Transcription stays on your device.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
        
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "Privacy reassurance", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor, letterSpacing = 1.sp)
                    listOf(
                        "No cloud dictation or telemetry",
                        "Models download once, then run offline",
                        "The floating assistant only appears over focused text fields"
                    ).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(PrimaryColor).padding(top = 5.dp))
                            Text(text = line, fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
                        }
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
            .testTag(testTag)
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            if (isGranted) Color(0xFF10B981).copy(alpha = 0.5f) else SecondaryColor.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                ),
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
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
                                text = "Active",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp,
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
                            text = "Required",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
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

fun isAccessibilityServiceEnabled(
    context: Context,
    service: Class<out AccessibilityService>
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
