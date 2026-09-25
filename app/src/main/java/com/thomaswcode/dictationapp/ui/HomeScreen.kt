package com.thomaswcode.dictationapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thomaswcode.dictationapp.platform.access.DictationAccessibilityService
import java.util.Calendar

data class SetupState(
    val hasAssemblyKey: Boolean = false,
    val hasGroqKey: Boolean = false,
    val micGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val batteryUnrestricted: Boolean = false,
) {
    val ready: Boolean get() = hasAssemblyKey && micGranted && accessibilityEnabled
}

fun readSetup(context: Context, hasAssemblyKey: Boolean, hasGroqKey: Boolean): SetupState = SetupState(
    hasAssemblyKey = hasAssemblyKey,
    hasGroqKey = hasGroqKey,
    micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
    accessibilityEnabled = DictationAccessibilityService.isEnabled(context),
    batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
)

fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openAppInfo(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun HomeScreen(nav: NavHostController, requestMicrophone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val connected by DictationAccessibilityService.connected.collectAsStateWithLifecycle()
    val snoozedUntil by graph.snoozedUntil.collectAsStateWithLifecycle()
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val setup = remember(resumeTick, settings.hasApiKey, settings.hasGroqKey, connected) { readSetup(context, settings.hasApiKey, settings.hasGroqKey) }
    var today by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(resumeTick) {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val records = graph.history.search(null, 500).filter { it.createdAt >= start }
        today = records.size to records.sumOf { r -> r.displayText.split(Regex("\\s+")).count { it.isNotEmpty() } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Text("DictationApp", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(top = 6.dp))
            val (label, color) = when {
                !setup.ready -> "Finish setting up to start dictating" to Palette.Warning
                !connected -> "Accessibility service is starting…" to Palette.Warning
                else -> "Ready. Tap any text field, then tap the bubble." to Palette.Success
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (snoozedUntil > System.currentTimeMillis()) {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Snooze)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bubble is hidden", style = MaterialTheme.typography.titleMedium)
                        Text("It comes back on its own after 10 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    FilledTonalButton(onClick = { graph.snoozedUntil.value = 0L }) { Text("Show now") }
                }
            }
        }

        if (!setup.ready || !setup.hasGroqKey || !setup.batteryUnrestricted) {
            SectionHeader("Setup")
            Panel {
                SetupRow(Icons.Rounded.Key, "AssemblyAI key", "Needed for transcription.", setup.hasAssemblyKey, "Add key") { nav.navigate(Routes.API) }
                SetupRow(Icons.Rounded.Mic, "Microphone", "Allow DictationApp to hear you.", setup.micGranted, "Allow", onClick = requestMicrophone)
                SetupRow(
                    Icons.Rounded.Accessibility,
                    "Bubble (accessibility service)",
                    "Turn on \"DictationApp bubble\" under Installed apps. If Android says it is a restricted setting, open App info, tap ⋮ and choose \"Allow restricted settings\", then try again.",
                    setup.accessibilityEnabled,
                    "Turn on",
                    secondary = "App info" to { openAppInfo(context) },
                ) { openAccessibilitySettings(context) }
                SetupRow(Icons.Rounded.AutoFixHigh, "Groq key (optional)", "Enables cleanup and tone. Without it the raw transcript is inserted.", setup.hasGroqKey, "Add key") { nav.navigate(Routes.API) }
                SetupRow(
                    Icons.Rounded.BatteryChargingFull,
                    "Keep running (optional)",
                    "Stops battery optimisation from shutting the bubble down.",
                    setup.batteryUnrestricted,
                    "Allow",
                ) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }

        SectionHeader("Try it")
        Panel {
            var sample by remember { mutableStateOf("") }
            OutlinedTextField(
                value = sample,
                onValueChange = { sample = it },
                placeholder = { Text("Tap here, then tap the bubble and speak") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(12.dp),
                shape = RoundedCornerShape(14.dp),
            )
        }

        SectionHeader("How it works")
        Panel {
            HowRow(Icons.Rounded.TouchApp, "Tap the bubble", "Dictate hands-free. Tap ✓ to insert or ✕ to discard.")
            HowRow(Icons.Rounded.Mic, "Hold the bubble", "Push-to-talk: release to insert. Slide your finger away before releasing to discard.")
            HowRow(Icons.Rounded.Snooze, "Drag the bubble", "It snaps to the nearest edge. Drop it on the ✕ at the bottom to hide it for 10 minutes.")
        }

        today?.let { (count, words) ->
            Hint(if (count == 0) "No dictations yet today." else "Today: $count dictation${if (count == 1) "" else "s"}, $words words.", Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun SetupRow(
    icon: ImageVector,
    title: String,
    description: String,
    done: Boolean,
    action: String,
    secondary: Pair<String, () -> Unit>? = null,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        if (done) {
            IconBadge(Icons.Rounded.CheckCircle, tint = Palette.Success, background = Palette.Success.copy(alpha = 0.12f))
        } else {
            IconBadge(icon)
        }

        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold)
            if (!done) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = onClick) { Text(action) }
                    if (secondary != null) TextButton(onClick = secondary.second) { Text(secondary.first) }
                }
            }
        }
    }
}

@Composable
private fun HowRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

