package com.thomaswcode.dictationapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.dictationapp.DictationApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-build test hooks, driven from a computer with adb (see README):
 *
 * - SET_KEYS  --es assemblyai KEY --es groq KEY      store API keys without typing them on the phone
 * - SIMULATE  --es path WAV [--ei delay SECONDS]      full dictation with a WAV in place of the microphone,
 *                                                     inserted into whatever field has focus
 * - STREAM_TEST --es path WAV                         stream a WAV and log every turn and the final text
 *
 * WAV files must be readable by the app, e.g. pushed to /sdcard/Android/data/<package>/files/.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as DictationApp).graph
        val log = graph.logger
        when (intent.action) {
            ACTION_SET_KEYS -> {
                val assembly = intent.getStringExtra("assemblyai")?.trim()
                val groq = intent.getStringExtra("groq")?.trim()
                graph.settings.update { s ->
                    s.copy(
                        apiKeyProtected = if (assembly.isNullOrEmpty()) s.apiKeyProtected else graph.secrets.protect(assembly),
                        groqApiKeyProtected = if (groq.isNullOrEmpty()) s.groqApiKeyProtected else graph.secrets.protect(groq),
                    )
                }
                log.info("Debug: keys updated (assemblyai=${!assembly.isNullOrEmpty()}, groq=${!groq.isNullOrEmpty()})")
            }
            ACTION_SIMULATE -> {
                val path = intent.getStringExtra("path") ?: return log.warn("Debug SIMULATE needs --es path")
                val delaySeconds = intent.getIntExtra("delay", 3)
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        log.info("Debug: simulating a dictation from $path in $delaySeconds s")
                        delay(delaySeconds * 1000L)
                        graph.orchestrator.simulateDictationFromWav(path)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_STREAM_TEST -> {
                val path = intent.getStringExtra("path") ?: return log.warn("Debug STREAM_TEST needs --es path")
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        val result = graph.orchestrator.transcribeWav(path, 1.0) { turn ->
                            log.info("Debug turn ${turn.turnOrder} eot=${turn.endOfTurn} fmt=${turn.turnIsFormatted}: ${turn.bestText}")
                        }
                        log.info("Debug stream test: connect ${result.connectLatencyMs} ms, audio ${result.audioDurationMs} ms, wall ${result.wallClockMs} ms, text: ${result.text}")
                    } catch (e: Exception) {
                        log.error("Debug stream test failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SET_KEYS = "com.thomaswcode.dictationapp.debug.SET_KEYS"
        const val ACTION_SIMULATE = "com.thomaswcode.dictationapp.debug.SIMULATE"
        const val ACTION_STREAM_TEST = "com.thomaswcode.dictationapp.debug.STREAM_TEST"
    }
}
