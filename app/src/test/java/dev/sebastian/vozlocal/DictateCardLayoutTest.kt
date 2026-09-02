package dev.sebastian.vozlocal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sebastian.vozlocal.ui.theme.MyApplicationTheme
import dev.sebastian.vozlocal.ui.theme.PrimaryColor
import dev.sebastian.vozlocal.ui.theme.SecondaryColor
import dev.sebastian.vozlocal.ui.theme.TertiaryColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [33])
class DictateCardLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLiveTranscriptionCardDisplay() {
        val liveText = "Vamos a ver que tal funciona esto ahora"
        val isRecording = false

        composeTestRule.setContent {
            MyApplicationTheme(themeMode = "light") {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 190.dp)
                                .testTag("transcription_card"),
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
                                    .padding(18.dp)
                                    .testTag("inner_column"),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Header Status Row
                                Row(
                                    modifier = Modifier.fillMaxWidth().testTag("header_row"),
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

                                    Text(
                                        text = "8 words • 39 chars",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Transcript Output Area WITHOUT weight
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 72.dp)
                                        .testTag("transcript_box")
                                ) {
                                    if (liveText.isNotBlank()) {
                                        Text(
                                            text = liveText,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 24.sp,
                                            modifier = Modifier.fillMaxWidth().testTag("transcribed_text")
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Bottom Action Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth().testTag("action_bar"),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {}) {
                                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy")
                                    }
                                    IconButton(onClick = {}) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                                    }
                                    IconButton(onClick = {}) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("transcription_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("transcribed_text").assertIsDisplayed()
        composeTestRule.onNodeWithText(liveText).assertIsDisplayed()
    }
}
