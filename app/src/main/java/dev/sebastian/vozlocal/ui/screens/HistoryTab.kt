package dev.sebastian.vozlocal.ui.screens

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sebastian.vozlocal.data.model.TranscriptionHistory
import dev.sebastian.vozlocal.ui.historyDateGroupLabel
import dev.sebastian.vozlocal.ui.formatShortDateTime
import dev.sebastian.vozlocal.ui.theme.*
import dev.sebastian.vozlocal.ui.viewmodel.MainViewModel

@Composable
fun HistoryTab(
    viewModel: MainViewModel,
    onReuse: (String) -> Unit = {}
) {
    val history by viewModel.transcriptionHistory.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val filteredHistory = remember(history, query) {
        val q = query.trim().lowercase()
        history.filter { item ->
            q.isBlank() ||
                item.text.lowercase().contains(q) ||
                item.modelUsed.lowercase().contains(q) ||
                (item.fileName?.lowercase()?.contains(q) == true)
        }
    }

    val groupedHistory = remember(filteredHistory) {
        filteredHistory.groupBy { historyDateGroupLabel(it.timestamp) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every saved transcript from local storage.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("Clear", color = TertiaryColor) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Transcription history", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Search, copy, share, or reuse saved dictations.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (history.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmClear = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = TertiaryColor),
                            modifier = Modifier.pressScale()
                        ) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Clear", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search text, file, or model") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedLeadingIconColor = PrimaryColor,
                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (filteredHistory.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (query.isBlank()) Icons.Default.HistoryToggleOff else Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (query.isBlank()) "No transcript history yet." else "No results for \"$query\".",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            groupedHistory.forEach { (groupLabel, groupItems) ->
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = groupLabel, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = PrimaryColor, letterSpacing = 1.sp)
                        Text(text = "${groupItems.size} item${if (groupItems.size == 1) "" else "s"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                items(groupItems, key = { it.id }) { item ->
                    HistoryCard(
                        item = item,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(item.text))
                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        onShare = { shareTranscription(context, item) },
                        onReuse = { onReuse(item.text) },
                        onDelete = { viewModel.deleteHistoryItem(item.id) }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun HistoryCard(
    item: TranscriptionHistory,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onReuse: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(item.timestamp) { formatShortDateTime(item.timestamp) }
    var confirmDelete by remember(item.id) { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transcript?") },
            text = { Text("This transcript will be removed from local history.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("Delete", color = TertiaryColor) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (item.type == "shared_file") Icons.Default.AudioFile else Icons.Default.KeyboardVoice,
                        contentDescription = null,
                        tint = if (item.type == "shared_file") SecondaryColor else PrimaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (item.type == "shared_file") "Shared file" else "Dictation",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (item.type == "shared_file") SecondaryColor else PrimaryColor,
                        letterSpacing = 1.sp
                    )
                }
                Text(text = dateStr, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!item.fileName.isNullOrEmpty()) {
                Text(
                    text = "File: ${item.fileName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(text = item.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 20.sp)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val modelBadge = item.modelUsed.substringBefore(" (")
                    Box(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(
                            text = modelBadge,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.durationSec > 0) {
                        Text(text = "${item.durationSec}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = onReuse, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.Replay, contentDescription = "Reuse transcript", tint = PrimaryColor, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share transcript", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy transcription text", tint = PrimaryColor, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete history log", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun shareTranscription(context: android.content.Context, item: TranscriptionHistory) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, item.fileName ?: "VozLocal transcription")
        putExtra(Intent.EXTRA_TEXT, item.text)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share transcript"))
}
