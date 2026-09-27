package com.thomaswcode.dictationapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.thomaswcode.dictationapp.DictationApp
import com.thomaswcode.dictationapp.platform.access.FieldInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-build test hooks, driven from a computer with adb (see README):
 *
 * - SET_KEYS  --es assemblyai KEY --es groq KEY      store API keys without typing them on the phone
 * - SIMULATE  --es path WAV [--ei delay SECONDS]      full dictation with a WAV in place of the microphone,
 *                                                     inserted into whatever field has focus
 * - STREAM_TEST --es path WAV                         stream a WAV and log every turn and the final text
 * - DUMP_FOCUS [--es clickId VIEW_ID] [--ez clear true] [--es click TEXT] [--ez text true] [--eia probe 1,21]
 *                                                     log the focused field's properties to logcat (its text
 *                                                     only with text=true); click first focuses the node
 *                                                     showing TEXT, e.g. a search bar's placeholder; probe
 *                                                     moves the cursor to each position and back; clear
 *                                                     empties the focused field (tidying up after a test)
 * - DUMP_WINDOWS                                      log every window the service can see, with how much of its
 *                                                     tree it exposes, and the raw input focus (an app that hides
 *                                                     its views from non-tool services shows few or no nodes)
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
                        // Transcript text goes to logcat only, never to the app's log files.
                        val result = graph.orchestrator.transcribeWav(path, 1.0) { turn ->
                            Log.i(TAG, "Debug turn ${turn.turnOrder} eot=${turn.endOfTurn} fmt=${turn.turnIsFormatted}: ${turn.bestText}")
                        }
                        log.info("Debug stream test: connect ${result.connectLatencyMs} ms, audio ${result.audioDurationMs} ms, wall ${result.wallClockMs} ms, ${result.text.length} chars")
                        Log.i(TAG, "Debug stream test text: ${result.text}")
                    } catch (e: Exception) {
                        log.error("Debug stream test failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DUMP_FOCUS -> {
                val pending = goAsync()
                graph.scope.launch(Dispatchers.Main) {
                    try {
                        val svc = graph.bridge.service ?: return@launch log.warn("Debug DUMP_FOCUS: accessibility service not connected")
                        intent.getStringExtra("clickId")?.let { id ->
                            val target = svc.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
                            Log.i(TAG, "Debug focus: click #$id found=${target != null} clicked=${target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)} focused=${target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)}")
                            delay(800)
                        }

                        if (intent.getBooleanExtra("clear", false)) {
                            val focused = graph.bridge.focusedField(svc)
                            val cleared = focused?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                            })
                            Log.i(TAG, "Debug focus: clear=$cleared")
                            delay(300)
                        }

                        intent.getStringExtra("click")?.let { label ->
                            val target = svc.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
                            var clickable = target
                            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                            Log.i(TAG, "Debug focus: click '$label' found=${target != null} clicked=${clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
                            delay(1200)
                        }

                        val node = graph.bridge.focusedField(svc)
                        Log.i(TAG, if (node == null) "Debug focus: nothing has input focus" else describe(node, intent.getBooleanExtra("text", false)))
                        if (node != null) intent.getIntArrayExtra("probe")?.forEach { Log.i(TAG, "Debug focus: " + probe(node, it)) }
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DUMP_WINDOWS -> {
                val pending = goAsync()
                graph.scope.launch(Dispatchers.Main) {
                    try {
                        val svc = graph.bridge.service ?: return@launch log.warn("Debug DUMP_WINDOWS: accessibility service not connected")
                        val input = runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                        Log.i(TAG, "Debug windows: input focus=${input?.let { "${it.packageName}/${it.className} window=${it.windowId}" }} active root=${svc.rootInActiveWindow?.let { "${it.packageName}/${it.className} window=${it.windowId}" }}")
                        runCatching { svc.windows }.getOrDefault(emptyList()).forEach { w ->
                            val root = w.root
                            val (nodes, editable) = root?.let(::census) ?: (0 to 0)
                            Log.i(TAG, "Debug windows: #${w.id} ${windowType(w.type)} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} title=${w.title} root=${root?.packageName}/${root?.className} children=${root?.childCount} nodes=$nodes editable=$editable")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /** Nodes and editable nodes in a window's tree (bounded). */
    private fun census(root: AccessibilityNodeInfo): Pair<Int, Int> {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var nodes = 0
        var editable = 0
        while (queue.isNotEmpty() && nodes < 3000) {
            val n = queue.removeFirst()
            nodes++
            if (n.isEditable) editable++
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }

        return nodes to editable
    }

    private fun windowType(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
        else -> "type$type"
    }

    /** Structure only by default: the field's text is printed only when asked for. */
    private fun describe(node: AccessibilityNodeInfo, withText: Boolean): String {
        val text = node.text?.toString()
        val hint = node.hintText?.toString()
        val actions = node.actionList.joinToString(",") { it.label?.toString() ?: actionName(it.id) }
        return buildString {
            append("Debug focus: ${node.className} pkg=${node.packageName} id=${node.viewIdResourceName}")
            append(" editable=${node.isEditable} focused=${node.isFocused} multiLine=${node.isMultiLine} inputType=0x${Integer.toHexString(node.inputType)}")
            append(" textLen=${text?.length} hintLen=${hint?.length} textEqualsHint=${text != null && text == hint} showingHint=${node.isShowingHintText}")
            append(" selection=${node.textSelectionStart}..${node.textSelectionEnd} granularities=${node.movementGranularities} children=${node.childCount}")
            append(" readable=${FieldInspector.readableText(node)?.length} placeholderSuspect=${FieldInspector.mayBePlaceholder(node, text.orEmpty())}")
            append(" standInShape=${FieldInspector.looksLikeStandIn(node, text.orEmpty())} provenEmpty=${FieldInspector.reportsTextWithoutCursor(node, text.orEmpty())}")
            append(" platformEmpty=${FieldInspector.platformReportsEmpty(node)} extras=${node.availableExtraData}")
            append(" actions=[$actions]")
            if (withText) append(" text='${text?.let(::visible)}' hint='$hint' contentDescription='${node.contentDescription}'")
        }
    }

    private fun visible(text: String): String = text.map { c -> if (c.code < 32 || c.code in 0x200B..0x200F || c.code == 0xFEFF || c.code == 0xFFFC) "\\u%04x".format(c.code) else c.toString() }.joinToString("")

    /** Moves the cursor to [position] and back, logging whether the field accepted it. */
    private fun probe(node: AccessibilityNodeInfo, position: Int): String {
        val before = node.textSelectionStart to node.textSelectionEnd
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
        })
        node.refresh()
        val after = node.textSelectionStart to node.textSelectionEnd
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, before.first)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.second)
        })
        return "probe($position) accepted=$accepted selection $before -> $after"
    }

    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "CLEAR_FOCUS"
        AccessibilityNodeInfo.ACTION_SELECT -> "SELECT"
        AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "LONG_CLICK"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "A11Y_FOCUS"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "CLEAR_A11Y_FOCUS"
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "NEXT_GRANULARITY"
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "PREV_GRANULARITY"
        AccessibilityNodeInfo.ACTION_COPY -> "COPY"
        AccessibilityNodeInfo.ACTION_PASTE -> "PASTE"
        AccessibilityNodeInfo.ACTION_CUT -> "CUT"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "SET_SELECTION"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "SCROLL_BACKWARD"
        else -> "0x${Integer.toHexString(id)}"
    }

    companion object {
        private const val TAG = "DictationApp"
        const val ACTION_SET_KEYS = "com.thomaswcode.dictationapp.debug.SET_KEYS"
        const val ACTION_SIMULATE = "com.thomaswcode.dictationapp.debug.SIMULATE"
        const val ACTION_STREAM_TEST = "com.thomaswcode.dictationapp.debug.STREAM_TEST"
        const val ACTION_DUMP_FOCUS = "com.thomaswcode.dictationapp.debug.DUMP_FOCUS"
        const val ACTION_DUMP_WINDOWS = "com.thomaswcode.dictationapp.debug.DUMP_WINDOWS"
    }
}
