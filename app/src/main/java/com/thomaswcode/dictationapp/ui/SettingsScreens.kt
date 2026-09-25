package com.thomaswcode.dictationapp.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thomaswcode.dictationapp.BuildConfig
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.LlmPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.cleanup.await
import com.thomaswcode.dictationapp.core.history.HistoryStats
import com.thomaswcode.dictationapp.core.history.RetentionPolicy
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.settings.BubbleSide
import com.thomaswcode.dictationapp.platform.audio.InputDevices
import com.thomaswcode.dictationapp.platform.audio.MicrophoneCapture
import com.thomaswcode.dictationapp.platform.overlay.BubbleView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: NavHostController) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        SectionHeader("Dictation")
        Panel {
            NavRow(Icons.Rounded.RadioButtonChecked, "Bubble", "Size ${s.bubbleSizePercent}% · opacity ${s.bubbleOpacityPercent}% · ${s.bubbleSide.name.lowercase()} edge") { nav.navigate(Routes.BUBBLE) }
            NavRow(Icons.Rounded.Style, "Style", "${s.defaultTone} tone · ${s.defaultCleanupLevel} cleanup by default") { nav.navigate(Routes.STYLE) }
            NavRow(Icons.AutoMirrored.Rounded.MenuBook, "Dictionary", "${s.dictionary.size} term${if (s.dictionary.size == 1) "" else "s"}") { nav.navigate(Routes.DICTIONARY) }
            NavRow(Icons.Rounded.Apps, "App rules", s.appRules.count { it.enabled }.let { n -> if (n == 0) "None: every app uses your defaults" else "$n app${if (n == 1) "" else "s"} with their own settings" }) { nav.navigate(Routes.RULES) }
        }

        SectionHeader("Services")
        Panel {
            NavRow(Icons.Rounded.Key, "API & models", listOf(if (s.hasApiKey) "AssemblyAI key stored" else "No AssemblyAI key", if (s.hasGroqKey) "Groq key stored" else "no Groq key").joinToString(" · ")) { nav.navigate(Routes.API) }
            NavRow(Icons.Rounded.GraphicEq, "Audio", "Microphone and level test") { nav.navigate(Routes.AUDIO) }
            NavRow(Icons.Rounded.Tune, "General", "Insertion, dictation limit (${s.maxDictationMinutes} min), language") { nav.navigate(Routes.GENERAL) }
            NavRow(Icons.Rounded.PrivacyTip, "History & privacy", "Keep text ${s.historyRetention.label.lowercase()}, audio ${s.audioRetention.label.lowercase()}") { nav.navigate(Routes.PRIVACY) }
        }

        SectionHeader("App")
        Panel {
            NavRow(Icons.Rounded.Info, "About & diagnostics", "Version ${BuildConfig.VERSION_NAME}, logs, accessibility") { nav.navigate(Routes.ABOUT) }
        }
    }
}

// ---------------------------------------------------------------- Bubble

@Composable
fun BubbleSettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    var size by remember { mutableFloatStateOf(s.bubbleSizePercent.toFloat()) }
    var opacity by remember { mutableFloatStateOf(s.bubbleOpacityPercent.toFloat()) }
    var showAppPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SubScreen("Bubble", onBack) {
        BubblePreview(size.roundToInt(), opacity.roundToInt(), s.bubbleSide)

        SectionHeader("Appearance")
        Panel {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row {
                    Text("Size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${size.roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { graph.settings.update { it.copy(bubbleSizePercent = size.roundToInt()) } },
                    valueRange = AppSettings.BUBBLE_MIN_PERCENT.toFloat()..AppSettings.BUBBLE_MAX_PERCENT.toFloat(),
                    steps = (AppSettings.BUBBLE_MAX_PERCENT - AppSettings.BUBBLE_MIN_PERCENT) / 10 - 1,
                )
                Row {
                    Text("Opacity when idle", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${opacity.roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { graph.settings.update { it.copy(bubbleOpacityPercent = opacity.roundToInt()) } },
                    valueRange = AppSettings.OPACITY_MIN_PERCENT.toFloat()..100f,
                    steps = (100 - AppSettings.OPACITY_MIN_PERCENT) / 10 - 1,
                )
                Text(
                    "The bubble is always fully visible while you dictate. Size is ${AppSettings.BUBBLE_BASE_DP} dp at 100%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SwitchRow("Shrink when idle", "After five seconds without use the bubble tucks itself into the edge.", s.bubbleShrinkWhenIdle) { v -> graph.settings.update { it.copy(bubbleShrinkWhenIdle = v) } }
        }

        SectionHeader("Position and behaviour")
        Panel {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Screen edge", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BubbleSide.entries.forEachIndexed { i, side ->
                        SegmentedButton(
                            selected = s.bubbleSide == side,
                            onClick = { graph.settings.update { it.copy(bubbleSide = side) } },
                            shape = SegmentedButtonDefaults.itemShape(i, BubbleSide.entries.size),
                        ) { Text(side.name) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { graph.settings.update { it.copy(bubbleSide = BubbleSide.Right, bubbleOffsetDp = 12, bubbleYFraction = 0.62f) } }) { Text("Reset position") }
                Text("You can also drag the bubble anywhere; it snaps to the nearest edge and remembers its height above the keyboard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SwitchRow("Only while the keyboard is open", "Off: show whenever a text field has focus, e.g. with a hardware keyboard.", s.bubbleOnlyWithKeyboard) { v -> graph.settings.update { it.copy(bubbleOnlyWithKeyboard = v) } }
            SwitchRow("Haptic feedback", "A tick when dictation starts and stops.", s.hapticFeedback) { v -> graph.settings.update { it.copy(hapticFeedback = v) } }
        }

        SectionHeader("Hide the bubble in these apps")
        Panel {
            if (s.hiddenInPackages.isEmpty()) {
                Text("None. The bubble never appears in password, PIN or number fields anyway.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }

            s.hiddenInPackages.forEach { pkg ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(appLabelOf(context, pkg), style = MaterialTheme.typography.bodyLarge)
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    IconButton(onClick = { graph.settings.update { it.copy(hiddenInPackages = it.hiddenInPackages - pkg) } }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove")
                    }
                }
            }

            Buttons { FilledTonalButton(onClick = { showAppPicker = true }) { Text("Add app") } }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(onDismiss = { showAppPicker = false }) { app ->
            showAppPicker = false
            graph.settings.update { if (app.packageName in it.hiddenInPackages) it else it.copy(hiddenInPackages = it.hiddenInPackages + app.packageName) }
        }
    }
}

/** A mock text field and keyboard with the real bubble view drawn at the chosen size and opacity. */
@Composable
private fun BubblePreview(sizePercent: Int, opacityPercent: Int, side: BubbleSide) {
    val density = LocalDensity.current
    val diameterPx = with(density) { (AppSettings.BUBBLE_BASE_DP * sizePercent / 100f).dp.toPx() }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(230.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(18.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }

            // Keyboard
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(10) { Box(Modifier.weight(1f).height(20.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))) }
                    }
                }
            }

            AndroidView(
                factory = { BubbleView(it) },
                update = { v ->
                    v.diameter = diameterPx
                    v.alpha = opacityPercent / 100f
                },
                modifier = Modifier
                    .align(if (side == BubbleSide.Right) Alignment.BottomEnd else Alignment.BottomStart)
                    .padding(bottom = 100.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Style

@Composable
fun StyleSettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    SubScreen("Style", onBack) {
        SectionHeader("Default tone")
        Panel {
            Column(Modifier.padding(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Tone.entries.forEachIndexed { i, tone ->
                        SegmentedButton(
                            selected = s.defaultTone == tone,
                            onClick = { graph.settings.update { it.copy(defaultTone = tone) } },
                            shape = SegmentedButtonDefaults.itemShape(i, Tone.entries.size),
                        ) { Text(tone.name) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(toneDescription(s.defaultTone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionHeader("Default cleanup level")
        Panel {
            CleanupLevel.entries.forEach { level ->
                RadioRow(level, s.defaultCleanupLevel, level.name, levelDescription(level)) { v -> graph.settings.update { it.copy(defaultCleanupLevel = v) } }
            }
        }

        Hint("App rules override these per app. None with Neutral never calls the AI. If the AI is unavailable the raw transcript is inserted and the bubble flashes amber (cleanup skipped).")
    }
}

fun toneDescription(tone: Tone): String = when (tone) {
    Tone.Neutral -> "Register unchanged, contractions as spoken."
    Tone.Formal -> "Professional register, expanded contractions, complete sentences."
    Tone.Casual -> "Relaxed, contractions allowed, short sentences."
}

fun levelDescription(level: CleanupLevel): String = when (level) {
    CleanupLevel.None -> "Spoken commands only (\"new line\", \"period\"…), no AI call."
    CleanupLevel.Light -> "Remove fillers and false starts, fix punctuation."
    CleanupLevel.Medium -> "Also fix grammar and split run-on sentences."
    CleanupLevel.High -> "Also tighten wording and add paragraphs."
}

// ---------------------------------------------------------------- Audio

@Composable
fun AudioSettingsScreen(onBack: () -> Unit, requestMicrophone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val devices = remember { InputDevices.list(context) }
    var testing by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var testStatus by remember { mutableStateOf("") }

    DisposableEffect(testing, s.microphoneDevice) {
        var capture: MicrophoneCapture? = null
        if (testing) {
            val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                testing = false
                requestMicrophone()
            } else {
                var peak = 0f
                capture = MicrophoneCapture(context, s.microphoneDevice, graph.logger).also { c ->
                    c.onFrame = { f ->
                        level = f.peak
                        peak = maxOf(peak, f.peak)
                        testStatus = if (peak < 1e-4f) "Listening… no sound yet" else "Peak ${(peak * 100).roundToInt()}%"
                    }
                    c.onFault = { e -> testStatus = "Could not open the microphone: ${e.message}" }
                    c.start()
                }
            }
        }

        onDispose {
            capture?.stop()
            level = 0f
        }
    }

    SubScreen("Audio", onBack) {
        SectionHeader("Microphone")
        Panel {
            RadioRow<String?>(null, s.microphoneDevice, "Default", "The phone picks (usually the built-in microphone).") { v -> graph.settings.update { it.copy(microphoneDevice = v) } }
            devices.forEach { d ->
                RadioRow<String?>(d.key, s.microphoneDevice, d.name) { v -> graph.settings.update { it.copy(microphoneDevice = v) } }
            }
        }

        SectionHeader("Test")
        Panel {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { testing = !testing }) { Text(if (testing) "Stop test" else "Test microphone") }
                    Spacer(Modifier.width(14.dp))
                    LinearProgressIndicator(
                        progress = { if (level <= 0f) 0f else minOf(1f, kotlin.math.sqrt(level) * 1.5f) },
                        modifier = Modifier.weight(1f).height(10.dp),
                        color = if (testing) Palette.Recording else MaterialTheme.colorScheme.primary,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }

                if (testStatus.isNotEmpty() && testing) Text(testStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Hint("Audio is recorded at 16 kHz mono, exactly what AssemblyAI streams, so nothing is converted. A Bluetooth headset is switched to its call microphone while you dictate. If the meter stays flat, another app may be using the microphone.")
    }
}

// ---------------------------------------------------------------- API & models

@Composable
fun ApiSettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var assemblyKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var baseUrl by remember(s.llmBaseUrl) { mutableStateOf(s.llmBaseUrl) }
    var model by remember(s.llmModel) { mutableStateOf(s.llmModel) }
    var fallbacks by remember(s.llmFallbackModels) { mutableStateOf(s.llmFallbackModels.joinToString(", ")) }

    SubScreen("API & models", onBack) {
        SectionHeader("AssemblyAI key (transcription)")
        Panel {
            KeyEditor(
                value = assemblyKey,
                onValue = { assemblyKey = it },
                stored = s.hasApiKey,
                placeholder = "Paste your AssemblyAI API key",
                onSave = {
                    val key = assemblyKey.trim()
                    graph.settings.update { it.copy(apiKeyProtected = graph.secrets.protect(key)) }
                    assemblyKey = ""
                },
                onRemove = { graph.settings.update { it.copy(apiKeyProtected = null) } },
            )
        }
        Hint("Used only for streaming speech-to-text. Stored encrypted with a key held in the Android Keystore.")

        SectionHeader("Groq key (cleanup and tone)")
        Panel {
            KeyEditor(
                value = groqKey,
                onValue = { groqKey = it },
                stored = s.hasGroqKey,
                placeholder = "Paste your Groq API key",
                onSave = {
                    val key = groqKey.trim()
                    graph.settings.update { it.copy(groqApiKeyProtected = graph.secrets.protect(key)) }
                    groqKey = ""
                },
                onRemove = { graph.settings.update { it.copy(groqApiKeyProtected = null) } },
            )
        }
        Hint("A free Groq account is enough: the default models are all on the free tier. Without a Groq key the raw transcript is inserted.")

        Buttons {
            Button(enabled = !testing, onClick = {
                testing = true
                testResult = "Testing…"
                scope.launch {
                    testResult = testKeys(
                        graph,
                        assemblyKey.trim().ifEmpty { graph.keys.assemblyAiKey().orEmpty() },
                        groqKey.trim().ifEmpty { graph.keys.llmKey().orEmpty() },
                        baseUrl,
                        model,
                    )
                    testing = false
                }
            }) { Text("Test keys") }
        }
        if (testResult.isNotEmpty()) Hint(testResult)

        SectionHeader("Speech model")
        Panel {
            RadioRow(AppSettings.SPEECH_MODEL_PRO, s.speechModel, "Universal-3.5 Pro (~$0.45/h)", "Supports the personal dictionary and formatted turns.") { v -> graph.settings.update { it.copy(speechModel = v) } }
            RadioRow(AppSettings.SPEECH_MODEL_STANDARD, s.speechModel, "Universal Streaming (~$0.15/h)", "Cheaper.") { v -> graph.settings.update { it.copy(speechModel = v) } }
        }

        SectionHeader("Cleanup models")
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Cleanup endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Cleanup model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fallbacks, { fallbacks = it }, label = { Text("Fallback models (comma-separated)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        graph.settings.update {
                            it.copy(
                                llmBaseUrl = baseUrl.trim().ifEmpty { AppSettings.DEFAULT_LLM_BASE_URL },
                                llmModel = model.trim().ifEmpty { AppSettings.DEFAULT_LLM_MODEL },
                                llmFallbackModels = fallbacks.split(',').map { f -> f.trim() }.filter { f -> f.isNotEmpty() },
                            )
                        }
                    }) { Text("Save") }
                    TextButton(onClick = {
                        val d = AppSettings()
                        graph.settings.update { it.copy(llmBaseUrl = d.llmBaseUrl, llmModel = d.llmModel, llmFallbackModels = d.llmFallbackModels) }
                    }) { Text("Defaults") }
                }
            }
        }
        Hint("Models are tried in order when one is rate-limited (Groq free tier: about 1000 requests a day and 8000 tokens a minute per model). Defaults: openai/gpt-oss-120b, then qwen/qwen3.8-27b, then openai/gpt-oss-20b. Any OpenAI-compatible endpoint works.")
    }
}

@Composable
private fun KeyEditor(value: String, onValue: (String) -> Unit, stored: Boolean, placeholder: String, onSave: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            placeholder = { Text(if (stored) "A key is stored. Paste a new one to replace it." else placeholder) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSave, enabled = value.isNotBlank()) { Text("Save") }
            if (stored) TextButton(onClick = onRemove) { Text("Remove stored key", color = Palette.Recording) }
        }
    }
}

/** Mirrors Windows' "Test keys": a streaming token proves the AssemblyAI key, a tiny completion proves Groq. */
private suspend fun testKeys(graph: com.thomaswcode.dictationapp.AppGraph, assemblyKey: String, groqKey: String, baseUrl: String, model: String): String {
    if (assemblyKey.isEmpty() && groqKey.isEmpty()) return "Enter at least one key first."
    val parts = mutableListOf<String>()
    try {
        if (assemblyKey.isEmpty()) {
            parts += "AssemblyAI: no key (transcription will not work)."
        } else {
            val req = Request.Builder().url("https://streaming.assemblyai.com/v3/token?expires_in_seconds=60").header("Authorization", assemblyKey).build()
            graph.http.newCall(req).await().use { parts += if (it.isSuccessful) "AssemblyAI: OK." else "AssemblyAI: rejected (HTTP ${it.code})." }
        }

        if (groqKey.isEmpty()) {
            parts += "Groq: no key (cleanup falls back to raw text)."
        } else {
            val m = model.trim().ifEmpty { AppSettings.DEFAULT_LLM_MODEL }
            val base = baseUrl.trim().ifEmpty { AppSettings.DEFAULT_LLM_BASE_URL }.trimEnd('/') + "/"
            val body: JsonObject = buildJsonObject {
                put("model", m)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Reply OK")
                    }
                }
                put("max_tokens", 8)
                LlmPostProcessor.reasoningEffortFor(m)?.let { put("reasoning_effort", JsonPrimitive(it)) }
            }
            val req = Request.Builder().url(base + "chat/completions").header("Authorization", "Bearer $groqKey")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            graph.http.newCall(req).await().use { resp ->
                parts += if (resp.isSuccessful) "Groq: OK ($m)." else "Groq: rejected (HTTP ${resp.code}): ${resp.body?.string().orEmpty().take(160)}"
            }
        }
    } catch (e: Exception) {
        parts += "Test failed: ${e.message}"
    }

    return parts.joinToString(" ")
}

// ---------------------------------------------------------------- General

@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    var minutes by remember(s.maxDictationMinutes) { mutableStateOf(s.maxDictationMinutes.toString()) }
    var languages by remember(s.languageCodes) { mutableStateOf(s.languageCodes.orEmpty()) }
    SubScreen("General", onBack) {
        SectionHeader("Insertion")
        Panel {
            RadioRow(InsertMethod.Direct, s.insertMethod, "Type into the field (recommended)", "Writes at the cursor and leaves your clipboard alone. Falls back to pasting when a field ignores it; web pages always paste.") { v -> graph.settings.update { it.copy(insertMethod = v) } }
            RadioRow(InsertMethod.Paste, s.insertMethod, "Paste through the clipboard", "Keeps rich-text formatting. Android does not allow restoring the previous clipboard afterwards.") { v -> graph.settings.update { it.copy(insertMethod = v) } }
        }
        Hint("App rules can choose a different method for an app, e.g. Paste for Gmail or Word to keep their formatting.")

        SectionHeader("Limits")
        Panel {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { v ->
                        minutes = v.filter { it.isDigit() }.take(3)
                        minutes.toIntOrNull()?.let { m -> graph.settings.update { it.copy(maxDictationMinutes = m.coerceIn(1, 180)) } }
                    },
                    label = { Text("Stop a dictation automatically after (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Hint("AssemblyAI bills per session second and caps sessions at 3 hours. 20 minutes matches the Windows app.")

        SectionHeader("Language")
        Panel {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = languages,
                    onValueChange = { v ->
                        languages = v
                        graph.settings.update { it.copy(languageCodes = v.trim().ifEmpty { null }) }
                    },
                    label = { Text("Language codes") },
                    placeholder = { Text("e.g. en, de") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Hint("Optional comma-separated language codes. Leave empty for automatic.")
    }
}

// ---------------------------------------------------------------- History & privacy

@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<HistoryStats?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { stats = runCatching { graph.history.stats() }.getOrNull() }

    SubScreen("History & privacy", onBack) {
        SectionHeader("Audio")
        Panel {
            SwitchRow("Store the audio of each dictation", "WAV, 16 kHz mono, for playback and Retry.", s.storeAudio) { v -> graph.settings.update { it.copy(storeAudio = v) } }
        }

        SectionHeader("Retention")
        Panel {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Dropdown("Keep text for", s.historyRetention, RetentionPolicy.entries, { it.label }, { v -> graph.settings.update { it.copy(historyRetention = v) } })
                Dropdown("Keep audio for", s.audioRetention, RetentionPolicy.entries, { it.label }, { v -> graph.settings.update { it.copy(audioRetention = v) } })
            }
        }
        Hint("Retention runs when the app starts and every hour.")

        SectionHeader("Data")
        Panel {
            stats?.let {
                Text(
                    "${it.count} dictations, ${it.withAudio} with audio (${"%.1f".format(Locale.ROOT, it.totalAudioBytes / 1_048_576.0)} MB).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }

            Buttons {
                OutlinedButton(onClick = { confirm = true }) { Text("Delete all history…", color = Palette.Recording) }
            }
        }
        Hint("Nothing leaves your phone except the audio sent to AssemblyAI for transcription and the transcript sent to the cleanup service. API keys are encrypted with the Android Keystore.")
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all history?") },
            text = { Text("Every dictation, recording and diagnostic log is removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        graph.retention.deleteAll()
                        graph.logger.deleteAll()
                        refresh++
                    }
                }) { Text("Delete all", color = Palette.Recording) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- About

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    SubScreen("About & diagnostics", onBack) {
        Panel {
            Column(Modifier.padding(16.dp)) {
                Text("DictationApp for Android", style = MaterialTheme.typography.titleMedium)
                Text("Version ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug build" else "release build"})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wispr Flow–style dictation on AssemblyAI streaming with Groq cleanup, ported from the Windows app. Settings and history live in the app's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("Diagnostics")
        Panel {
            Buttons {
                FilledTonalButton(onClick = {
                    val log = graph.logger.currentFile
                    val text = if (log.exists()) log.readText().takeLast(60_000) else "No log for today yet."
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "DictationApp log ${log.name}").putExtra(Intent.EXTRA_TEXT, text),
                            "Share today's log",
                        ),
                    )
                }) { Text("Share today's log") }
                OutlinedButton(onClick = { openAccessibilitySettings(context) }) { Text("Accessibility") }
            }
            Text(
                "Logs: ${graph.logger.currentFile.parent} (kept 14 days)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

