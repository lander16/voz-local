package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.data.model.DictationModel
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

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
