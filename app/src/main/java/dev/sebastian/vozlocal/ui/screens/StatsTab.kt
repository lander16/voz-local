package dev.sebastian.vozlocal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun StatsTab(viewModel: MainViewModel) {
    val stats by viewModel.dictationStats.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    // Base calculations — remembered so they don't recompute on every recomposition
    val totalWords by remember(stats) { derivedStateOf { stats.sumOf { it.wordCount } } }
    val totalSeconds by remember(stats) { derivedStateOf { stats.sumOf { it.durationSec } } }
    val totalMinutes by remember(totalSeconds) { derivedStateOf { totalSeconds / 60f } }

    val averageWpm by remember(stats) {
        derivedStateOf {
            if (stats.isNotEmpty()) stats.map { it.wpm }.average().toFloat() else 0f
        }
    }

    // Today calculations
    val todayMidnight by remember {
        derivedStateOf {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }
    val statsToday by remember(stats, todayMidnight) {
        derivedStateOf { stats.filter { it.timestamp >= todayMidnight } }
    }
    val todayWords by remember(statsToday) { derivedStateOf { statsToday.sumOf { it.wordCount } } }
    val todayAvgWpm by remember(statsToday) {
        derivedStateOf { if (statsToday.isNotEmpty()) statsToday.map { it.wpm }.average().toFloat() else 0f }
    }
    val todayDurationMin by remember(statsToday) {
        derivedStateOf { statsToday.sumOf { it.durationSec } / 60f }
    }

    // Week calculations (last 7 days)
    val last7DaysData by remember(stats) {
        derivedStateOf {
            val sdf = SimpleDateFormat("EEE", Locale.getDefault())
            (0..6).map { offset ->
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
        }
    }

    val maxWordCount by remember(last7DaysData) { derivedStateOf { last7DaysData.maxOfOrNull { it.second } ?: 1 } }
    val displayMax by remember(maxWordCount) { derivedStateOf { if (maxWordCount > 0) maxWordCount else 100 } }

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
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(listOf(PrimaryColor.copy(alpha = 0.4f), Color.Transparent))
                            ),
                            RoundedCornerShape(16.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Today's words", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = todayWords.toString(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor)
                        Text(
                            text = String.format(Locale.US, "%.1f min duration", todayDurationMin),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Card 2: Average WPM
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(listOf(SecondaryColor.copy(alpha = 0.4f), Color.Transparent))
                            ),
                            RoundedCornerShape(16.dp)
                        ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "Verbal speed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = String.format(Locale.US, "%.0f", averageWpm), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = SecondaryColor)
                            Text(text = "WPM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SecondaryColor)
                        }
                        Text(
                            text = if (todayAvgWpm > 0f) String.format(Locale.US, "%.0f WPM today", todayAvgWpm) else "No dictations today",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Bar Chart Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                                        fontSize = 11.sp,
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
                                    fontSize = 11.sp,
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Stat Item 1
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Lifetime words", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(text = totalWords.toString(), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        }

                        // Stat Item 2
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Total speak time", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format(Locale.US, "%.1f min", totalMinutes),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        }

                        // Stat Item 3
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Dictations", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
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
                    onClick = { confirmReset = true },
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

        if (confirmReset) {
            item {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("Reset statistics?") },
                    text = { Text("This clears all stored performance stats from the device.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.clearStats()
                            confirmReset = false
                        }) { Text("Reset", color = TertiaryColor) }
                    },
                    dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}
