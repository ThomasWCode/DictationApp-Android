package com.thomaswcode.dictationapp.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.HistoryStats
import com.thomaswcode.dictationapp.core.history.RecordStatus
import com.thomaswcode.dictationapp.core.insertion.ToastKind
import com.thomaswcode.dictationapp.core.session.DictationOrchestrator
import com.thomaswcode.dictationapp.platform.audio.WavPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val whenFormat = SimpleDateFormat("dd MMM HH:mm", Locale.UK)

@Composable
fun statusColor(status: RecordStatus): Color = when (status) {
    RecordStatus.Inserted -> Palette.Success
    RecordStatus.CopiedOnly -> MaterialTheme.colorScheme.primary
    RecordStatus.Failed -> Palette.Recording
    RecordStatus.Pending -> Palette.Warning
}

fun statusLabel(status: RecordStatus): String = when (status) {
    RecordStatus.Inserted -> "Inserted"
    RecordStatus.CopiedOnly -> "Copied"
    RecordStatus.Failed -> "Failed"
    RecordStatus.Pending -> "Pending"
}

@Composable
fun HistoryScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    var query by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<DictationRecord>>(emptyList()) }
    var stats by remember { mutableStateOf<HistoryStats?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(query, tick) {
        delay(150) // debounce typing
        records = runCatching { graph.history.search(query.ifBlank { null }, 500) }.getOrDefault(emptyList())
        stats = runCatching { graph.history.stats() }.getOrNull()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search dictations") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (records.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "Your dictations will appear here." else "Nothing matches \"$query\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(records, key = { it.id }) { r -> HistoryRow(r) { nav.navigate(Routes.historyDetail(r.id)) } }
            }
        }

        stats?.let { s ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "${s.count} dictation${if (s.count == 1) "" else "s"} · ${s.withAudio} with audio · estimated cost $${"%.2f".format(Locale.ROOT, s.totalCost)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(r: DictationRecord, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(whenFormat.format(Date(r.createdAt)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(r.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StatusChip(statusLabel(r.status), statusColor(r.status))
        }

        Spacer(Modifier.padding(top = 4.dp))
        Text(
            r.displayText.ifBlank { r.failureReason ?: "(empty)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryDetailScreen(id: Long, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<DictationRecord?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val player = remember { WavPlayer() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        player.onStopped = { playing = false }
        onDispose { player.stop() }
    }
    LaunchedEffect(id, reload) { record = graph.history.get(id) }

    SubScreen("Dictation", onBack) {
        val r = record ?: return@SubScreen
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${whenFormat.format(Date(r.createdAt))} · ${r.appName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            StatusChip(statusLabel(r.status), statusColor(r.status))
        }

        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val canPlay = r.hasAudio && File(r.audioPath!!).exists()
            ActionButton(if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, if (playing) "Stop" else "Play", enabled = canPlay) {
                if (playing) {
                    player.stop()
                } else {
                    runCatching {
                        player.play(r.audioPath!!)
                        playing = true
                    }.onFailure { status = "Playback failed: ${it.message}" }
                }
            }
            ActionButton(Icons.Rounded.ContentCopy, "Copy") {
                scope.launch {
                    graph.clipboard.setText(r.displayText)
                    status = "Copied to the clipboard."
                }
            }
            ActionButton(Icons.AutoMirrored.Rounded.Input, "Insert") {
                // Like Windows' Re-insert: step out of the way, then type into the field that has focus.
                (context as? Activity)?.moveTaskToBack(true)
                graph.scope.launch {
                    delay(700)
                    val target = graph.bridge.capture()
                    if (target.isEditable && !target.isPassword) {
                        // The destination app's rule decides how the text goes in (e.g. Paste for a rich editor).
                        graph.bridge.insert(r.displayText, target, DictationOrchestrator.insertMethodFor(target, graph.settings.current))
                    } else {
                        graph.clipboard.setText(r.displayText)
                        graph.notifier.toast("Copied to clipboard", "No text field has focus.", ToastKind.Info)
                    }
                }
            }
            ActionButton(Icons.Rounded.Refresh, "Retry", enabled = !busy && r.hasAudio && (r.status == RecordStatus.Failed || r.status == RecordStatus.Pending)) {
                busy = true
                status = "Re-streaming audio…"
                scope.launch {
                    val ok = graph.orchestrator.retry(r.id)
                    status = if (ok) "Retry complete; text copied to the clipboard." else "Retry did not produce text."
                    busy = false
                    reload++
                }
            }
            ActionButton(Icons.AutoMirrored.Rounded.Undo, "Undo AI edit", enabled = r.canUndoAiEdit) {
                scope.launch {
                    graph.clipboard.setText(r.rawTranscript)
                    r.insertedText = r.rawTranscript
                    r.aiEditUndone = true
                    r.updatedAt = System.currentTimeMillis()
                    graph.history.update(r)
                    status = "Raw transcript copied; paste it over the AI version."
                    reload++
                }
            }
            ActionButton(Icons.Rounded.Delete, "Delete", tint = Palette.Recording) { confirmDelete = true }
        }

        if (status.isNotEmpty()) Hint(status)

        SectionHeader("Inserted text")
        Panel {
            SelectionContainer {
                Text(r.insertedText.ifBlank { "(nothing inserted)" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            }
        }

        SectionHeader("Raw transcript")
        Panel {
            SelectionContainer {
                Text(r.rawTranscript.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }

        SectionHeader("Details")
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Detail("App", listOfNotNull(r.appLabel, r.packageName).distinct().joinToString(" · ").ifBlank { "unknown" })
                r.url?.let { Detail("Address", it) }
                Detail("Tone", r.tone.name)
                Detail("Cleanup", r.level.name)
                Detail("Model", r.llmModel ?: "none")
                Detail("Audio", "${"%.1f".format(Locale.ROOT, r.durationMs / 1000.0)} s")
                Detail("Estimated cost", "$${"%.4f".format(Locale.ROOT, r.costEstimate ?: 0.0)}")
                r.failureReason?.let { Detail("Note", it, Palette.Recording) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this dictation?") },
            text = { Text("The text and its recording are removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val r = record ?: return@TextButton
                    player.stop()
                    scope.launch {
                        graph.history.delete(r.id)
                        r.audioPath?.let { File(it).delete() }
                        onBack()
                    }
                }) { Text("Delete", color = Palette.Recording) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    if (tint == null) {
        FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 6.dp))
            Text(label, color = tint)
        }
    }
}

@Composable
private fun Detail(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
