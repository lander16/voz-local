package dev.sebastian.vozlocal.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.R
import dev.sebastian.vozlocal.data.model.DictationModel
import dev.sebastian.vozlocal.ui.formatDownloadProgress
import dev.sebastian.vozlocal.ui.formatEta
import dev.sebastian.vozlocal.ui.modelDownloadStatusLabel
import dev.sebastian.vozlocal.ui.modelPresentation
import dev.sebastian.vozlocal.ui.modelVerificationLabel
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel

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
                    text = stringResource(R.string.models_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.models_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.models_benchmark_notice),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(models, key = { it.id }) { model ->
            ModelCard(
                viewModel = viewModel,
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
    viewModel: MainViewModel,
    model: DictationModel,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRedownload: () -> Unit
) {
    val presentation = modelPresentation(model.id)
    val displayName = stringResource(presentation.nameRes)
    val downloadProgress by viewModel.downloadProgressFor(model.id).collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatusFor(model.id).collectAsStateWithLifecycle()
    var confirmDelete by remember(model.id) { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.model_delete_title)) },
            text = { Text(stringResource(R.string.model_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text(stringResource(R.string.button_delete), color = TertiaryColor) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.button_cancel)) } }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = model.isDownloaded) { onSelect() }
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            if (model.isSelected) PrimaryColor else SecondaryColor.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                ),
                RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (model.isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
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
                        tint = if (model.isSelected) PrimaryColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${model.sizeMb.toInt()} MB",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelMetaChip(text = stringResource(presentation.badgeRes), active = true, accent = PrimaryColor)
                ModelMetaChip(text = "${model.sizeMb.toInt()} MB", active = false)
                val isVerified = downloadStatus?.verificationLabel?.startsWith("Verified") == true
                ModelMetaChip(
                    text = modelVerificationLabel(downloadStatus?.verificationLabel),
                    active = isVerified,
                    accent = if (isVerified) Color(0xFF10B981) else TextMuted
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.model_language_label), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = stringResource(presentation.languageRes), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.model_quantization_label), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = presentation.quantization, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text(
                text = stringResource(presentation.descriptionRes),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                            text = modelDownloadStatusLabel(downloadStatus?.statusLabel),
                            fontSize = 11.sp,
                            color = PrimaryColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "${(downloadProgress * 100).toInt()}%", fontSize = 11.sp, color = PrimaryColor, fontWeight = FontWeight.Bold)
                            Text(text = formatEta(downloadStatus?.etaSeconds) ?: stringResource(R.string.model_download_sizing), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        text = downloadStatus?.let { formatDownloadProgress(it.downloadedMb, it.totalMb) } ?: "0 MB / ${model.sizeMb.toInt()} MB",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress },
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
                                onClick = { confirmDelete = true },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.model_delete_description, displayName),
                                    tint = TertiaryColor
                                )
                            }
                            IconButton(
                                onClick = onRedownload,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.model_redownload_description, displayName),
                                    tint = PrimaryColor
                                )
                            }
                        }

                        Button(
                            onClick = onSelect,
                            enabled = !model.isSelected,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (model.isSelected) Color.Transparent else MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .pressScale()
                        ) {
                            Text(
                                text = stringResource(if (model.isSelected) R.string.model_active else R.string.model_activate),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (model.isSelected) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
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
                                    tint = MaterialTheme.colorScheme.onSecondary
                                )
                                Text(
                                    text = stringResource(R.string.model_download),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelMetaChip(text: String, active: Boolean, accent: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(if (active) accent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(1.dp, if (active) accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
