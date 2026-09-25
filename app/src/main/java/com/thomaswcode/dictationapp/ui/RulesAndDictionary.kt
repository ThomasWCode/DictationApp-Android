package com.thomaswcode.dictationapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.CorrectionDiffer
import com.thomaswcode.dictationapp.core.dictionary.DictionaryTerm
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.rules.AppRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- Dictionary

@Composable
fun DictionaryScreen(onBack: () -> Unit, onCorrect: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    var newTerm by remember { mutableStateOf("") }
    fun add() {
        val term = newTerm.trim().take(DictionaryTerm.MAX_LENGTH)
        if (term.isEmpty()) return
        graph.settings.update { st -> if (st.dictionary.any { it.term.equals(term, ignoreCase = true) }) st else st.copy(dictionary = st.dictionary + DictionaryTerm(term)) }
        newTerm = ""
    }

    SubScreen("Dictionary", onBack) {
        Hint("Names, acronyms and drug names are sent to AssemblyAI as key terms (up to 100, starred first) and to the cleanup model as spellings to keep.")
        Panel {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTerm,
                    onValueChange = { newTerm = it },
                    placeholder = { Text("Add a term, e.g. LSHTM") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::add, enabled = newTerm.isNotBlank()) { Text("Add") }
            }
        }

        Buttons { FilledTonalButton(onClick = onCorrect) { Text("Correct last dictation…") } }

        if (s.dictionary.isNotEmpty()) {
            SectionHeader("${s.dictionary.size} terms")
            Panel {
                s.dictionary.sortedWith(compareByDescending<DictionaryTerm> { it.starred }.thenBy { it.term.lowercase() }).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { graph.settings.update { st -> st.copy(dictionary = st.dictionary.map { if (it.term == t.term) it.copy(starred = !it.starred) else it }) } }) {
                            Icon(if (t.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = "Star", tint = if (t.starred) Palette.Warning else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(t.term, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Used ${t.useCount}× · added ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t.addedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { graph.settings.update { st -> st.copy(dictionary = st.dictionary.filterNot { it.term == t.term }) } }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Correct last dictation": fix the misheard words in the last inserted text; a word-level diff proposes
 * the replacements as new starred dictionary terms.
 */
@Composable
fun CorrectionScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<DictationRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val candidates = remember { mutableStateListOf<Pair<String, String>>() }
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        record = graph.history.latest()
        record?.let {
            original = it.displayText
            edited = original
        }
        loaded = true
    }

    SubScreen("Correct last dictation", onBack) {
        val r = record
        if (!loaded) return@SubScreen
        if (r == null) {
            Hint("No dictation in history yet.")
            return@SubScreen
        }

        Hint("Last dictation in ${r.appName}. Fix any misheard words, then find corrections.")
        Panel {
            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(12.dp),
                shape = RoundedCornerShape(14.dp),
            )
        }

        Buttons {
            FilledTonalButton(onClick = {
                candidates.clear()
                chosen.clear()
                CorrectionDiffer.diff(original, edited).forEach { c ->
                    val to = CorrectionDiffer.suggestTerms(listOf(c)).firstOrNull() ?: return@forEach
                    candidates += c.from to to
                    chosen += to
                }
                status = if (candidates.isEmpty()) "No word changes found." else "${candidates.size} candidate term${if (candidates.size == 1) "" else "s"}. Untick any you do not want."
            }) { Text("Find corrections") }
        }

        if (status.isNotEmpty()) Hint(status)
        if (candidates.isNotEmpty()) {
            Panel {
                candidates.forEach { (from, to) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (to in chosen) chosen.remove(to) else chosen.add(to) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = to in chosen, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text("\"$from\" → ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(to, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Buttons {
                Button(enabled = chosen.isNotEmpty(), onClick = {
                    val terms = chosen.distinctBy { it.lowercase() }
                    graph.settings.update { st ->
                        val add = terms.filter { t -> st.dictionary.none { it.term.equals(t, ignoreCase = true) } }.map { DictionaryTerm(it, starred = true) }
                        st.copy(dictionary = st.dictionary + add)
                    }
                    scope.launch {
                        if (edited != original) {
                            r.insertedText = edited
                            r.updatedAt = System.currentTimeMillis()
                            graph.history.update(r)
                        }
                        onBack()
                    }
                }) { Text("Add ${chosen.size} to dictionary") }
            }
        }
    }
}

// ---------------------------------------------------------------- App rules

@Composable
fun AppRulesScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }

    /** Adds [rule] (or finds the existing rule for the same app) and opens it in the editor. */
    fun addAndEdit(rule: AppRule) {
        val existing = s.appRules.indexOfFirst { !rule.packageGlob.isNullOrBlank() && it.packageGlob.equals(rule.packageGlob, ignoreCase = true) }
        if (existing >= 0) {
            editing = existing
            return
        }

        graph.settings.update { it.copy(appRules = it.appRules + rule) }
        editing = s.appRules.size
    }

    SubScreen("App rules", onBack) {
        Hint("Every app uses your default style (Settings › Style) and insertion method (Settings › General) unless you add it here with its own settings.")
        Buttons {
            Button(onClick = { pickingApp = true }) { Text("Add app") }
            OutlinedButton(onClick = {
                // A new rule starts from the current defaults, so only what should differ needs changing.
                addAndEdit(AppRule(urlHost = "", tone = s.defaultTone, level = s.defaultCleanupLevel))
            }) { Text("Add website") }
        }

        Panel {
            if (s.appRules.isEmpty()) {
                Text(
                    "No app rules yet, so every app follows your defaults.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            s.appRules.forEachIndexed { index, rule ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = index }.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ruleTitle(context, rule), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (!rule.urlHost.isNullOrBlank()) "website" else rule.packageGlob,
                                rule.tone?.name,
                                rule.level?.let { "$it cleanup" },
                                rule.insertMethod?.let { if (it == InsertMethod.Paste) "paste" else "type" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Switch(checked = rule.enabled, onCheckedChange = { v -> graph.settings.update { st -> st.copy(appRules = st.appRules.mapIndexed { i, r -> if (i == index) r.copy(enabled = v) else r }) } })
                }
            }
        }

        Hint("A website rule (for Chrome, Edge, Firefox, Samsung Internet and other supported browsers) beats an app rule; the first match of each kind wins. Rich-text editors such as Gmail or Word keep their formatting best with Insertion set to Paste.")
        if (s.appRules.isNotEmpty()) {
            Buttons { TextButton(onClick = { confirmRemoveAll = true }) { Text("Remove all", color = Palette.Recording) } }
        }
    }

    if (pickingApp) {
        AppPickerDialog(onDismiss = { pickingApp = false }) { app ->
            pickingApp = false
            addAndEdit(AppRule(packageGlob = app.packageName, label = app.label, tone = s.defaultTone, level = s.defaultCleanupLevel))
        }
    }

    editing?.let { index ->
        val rule = s.appRules.getOrNull(index)
        if (rule == null) {
            editing = null
        } else {
            RuleEditor(
                rule = rule,
                onDismiss = {
                    // Drop a rule that was added but never given a target.
                    graph.settings.update { st -> st.copy(appRules = st.appRules.filterIndexed { i, r -> i != index || !r.packageGlob.isNullOrBlank() || !r.urlHost.isNullOrBlank() }) }
                    editing = null
                },
                onSave = { updated ->
                    graph.settings.update { st -> st.copy(appRules = st.appRules.mapIndexed { i, r -> if (i == index) updated else r }) }
                    editing = null
                },
                onDelete = {
                    graph.settings.update { st -> st.copy(appRules = st.appRules.filterIndexed { i, _ -> i != index }) }
                    editing = null
                },
            )
        }
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all app rules?") },
            text = { Text("Every app will follow your default style and insertion method.") },
            confirmButton = {
                TextButton(onClick = {
                    graph.settings.update { it.copy(appRules = emptyList()) }
                    confirmRemoveAll = false
                }) { Text("Remove all", color = Palette.Recording) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }
}

private fun ruleTitle(context: Context, rule: AppRule): String = when {
    !rule.urlHost.isNullOrBlank() -> rule.urlHost
    !rule.label.isNullOrBlank() -> rule.label
    !rule.packageGlob.isNullOrBlank() -> appLabelOf(context, rule.packageGlob)
    else -> "New rule"
}

@Composable
private fun RuleEditor(rule: AppRule, onDismiss: () -> Unit, onSave: (AppRule) -> Unit, onDelete: () -> Unit) {
    // A new website rule has an empty (not null) host, so it opens in website mode.
    var isWebsite by remember { mutableStateOf(rule.urlHost != null) }
    var pkg by remember { mutableStateOf(rule.packageGlob.orEmpty()) }
    var label by remember { mutableStateOf(rule.label) }
    var host by remember { mutableStateOf(rule.urlHost.orEmpty()) }
    var tone by remember { mutableStateOf(rule.tone) }
    var level by remember { mutableStateOf(rule.level) }
    var method by remember { mutableStateOf(rule.insertMethod) }
    var hint by remember { mutableStateOf(rule.hint.orEmpty()) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !isWebsite, onClick = { isWebsite = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("App") }
                    SegmentedButton(selected = isWebsite, onClick = { isWebsite = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Website") }
                }
                if (isWebsite) {
                    OutlinedTextField(host, { host = it }, label = { Text("Host, e.g. mail.google.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(pkg, { pkg = it; label = null }, label = { Text("Package, e.g. com.whatsapp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { picking = true }) { Text("Pick an app…") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Dropdown("Tone", tone, listOf(null) + Tone.entries, { it?.name ?: "Default" }, { tone = it })
                    Dropdown("Cleanup", level, listOf(null) + CleanupLevel.entries, { it?.name ?: "Default" }, { level = it })
                }
                Dropdown("Insertion", method, listOf(null, InsertMethod.Direct, InsertMethod.Paste), { if (it == null) "Default" else if (it == InsertMethod.Paste) "Paste" else "Type" }, { method = it })
                OutlinedTextField(hint, { hint = it }, label = { Text("Hint for the AI") }, placeholder = { Text("This is a chat message.") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (isWebsite) host.isNotBlank() else pkg.isNotBlank(),
                onClick = {
                    onSave(
                        rule.copy(
                            packageGlob = if (isWebsite) null else pkg.trim(),
                            urlHost = if (isWebsite) host.trim().removePrefix("https://").removePrefix("http://").substringBefore('/') else null,
                            label = if (isWebsite) null else label,
                            tone = tone,
                            level = level,
                            insertMethod = method,
                            hint = hint.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Palette.Recording) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (picking) {
        AppPickerDialog(onDismiss = { picking = false }) { app ->
            pkg = app.packageName
            label = app.label
            picking = false
        }
    }
}

// ---------------------------------------------------------------- App picker

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Text("Loading apps…", modifier = Modifier.padding(16.dp))
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp)) { app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) } }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val size = (36 * context.resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            InstalledApp(
                ri.activityInfo.packageName,
                ri.loadLabel(pm).toString(),
                runCatching { ri.loadIcon(pm).toBitmap(size, size).asImageBitmap() }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun appLabelOf(context: Context, packageName: String): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)
