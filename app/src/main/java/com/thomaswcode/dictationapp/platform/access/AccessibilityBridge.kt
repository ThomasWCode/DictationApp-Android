package com.thomaswcode.dictationapp.platform.access

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.insertion.Clipboard
import com.thomaswcode.dictationapp.core.insertion.ForegroundContext
import com.thomaswcode.dictationapp.core.insertion.ForegroundContextProvider
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.insertion.InsertionOutcome
import com.thomaswcode.dictationapp.core.insertion.InsertionResult
import com.thomaswcode.dictationapp.core.insertion.InsertionTextFormatter
import com.thomaswcode.dictationapp.core.insertion.TextInserter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The orchestrator's view of the screen: captures the focused field and inserts text into it through
 * whichever accessibility service instance is currently connected. When the service is off, capture
 * reports "no text field" and insertion falls back to the clipboard, so History and Retry still work.
 */
class AccessibilityBridge(
    private val clipboard: Clipboard,
    private val logger: Logger,
) : ForegroundContextProvider, TextInserter {
    @Volatile var service: AccessibilityService? = null

    private val formatter = InsertionTextFormatter()
    private val labels = ConcurrentHashMap<String, String>()
    private val lastUrl = ConcurrentHashMap<String, String>()

    val isConnected: Boolean get() = service != null

    override suspend fun capture(): ForegroundContext = withContext(Dispatchers.Main) {
        val svc = service ?: return@withContext ForegroundContext.Unknown
        val focus = focusedField(svc)
        val pkg = focus?.packageName?.toString() ?: svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        val label = appLabel(svc, pkg)
        val title = focus?.window?.title?.toString()?.takeIf { it.isNotBlank() } ?: label
        val editable = focus != null && FieldInspector.isEditableField(focus)
        ForegroundContext(
            windowId = focus?.windowId ?: -1,
            packageName = pkg,
            appLabel = label,
            windowTitle = title,
            url = browserUrl(svc, pkg),
            isEditable = editable,
            isPassword = focus != null && FieldInspector.isSecret(focus),
            editableReason = when {
                focus == null -> "no-input-focus"
                editable -> focus.className?.toString() ?: "editable"
                else -> "not-editable:${focus.className}"
            },
            target = focus,
        )
    }

    override suspend fun insert(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult = withContext(Dispatchers.Main) {
        val svc = service
        val node = svc?.let { focusedField(it) }?.takeIf { FieldInspector.isEditableField(it) }
            ?: (target.target as? AccessibilityNodeInfo)?.takeIf { it.refresh() && FieldInspector.isEditableField(it) }
        if (node == null) {
            clipboard.setText(text)
            return@withContext InsertionResult(InsertionOutcome.CopiedOnly, "No text field has focus, text copied", text)
        }

        if (FieldInspector.isSecret(node)) {
            clipboard.setText(text)
            return@withContext InsertionResult(InsertionOutcome.CopiedOnly, "Not typing into a password field, text copied", text)
        }

        val key = FieldInspector.fieldKey(node)
        val current = FieldInspector.readableText(node)
        val formatted: String
        var selection = 0 to 0
        if (current != null) {
            selection = FieldInspector.selection(node, current)
            val prev = if (selection.first > 0) current[selection.first - 1].toString() else null
            val body = InsertionTextFormatter.apply(text, prev)
            formatted = body + InsertionTextFormatter.trailingFor(body, current.getOrNull(selection.second))
        } else {
            formatted = formatter.format(text, key)
        }

        // Direct: splice the text in at the cursor. Verified by reading the field back; a field that ignores
        // SET_TEXT (custom editors) is left untouched and gets a paste instead.
        if (method == InsertMethod.Direct && current != null && FieldInspector.supportsSetText(node) && !FieldInspector.inWebView(node)) {
            val newText = current.substring(0, selection.first) + formatted + current.substring(selection.second)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                val cursor = selection.first + formatted.length
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    },
                )
                delay(80)
                node.refresh()
                if (InsertionTextFormatter.directInsertApplied(current, newText, FieldInspector.readableText(node))) {
                    formatter.remember(key, formatted)
                    return@withContext InsertionResult(InsertionOutcome.Inserted, null, formatted)
                }

                logger.info("SET_TEXT was ignored by ${node.className} in ${node.packageName}; pasting instead")
            }
        }

        // Paste: Android does not let a background app read the clipboard, so it cannot be restored afterwards.
        clipboard.setText(formatted)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            formatter.remember(key, formatted)
            InsertionResult(InsertionOutcome.Inserted, null, formatted)
        } else {
            InsertionResult(InsertionOutcome.CopiedOnly, "This field does not accept pasting, text copied", formatted)
        }
    }

    /** The field with input focus, ignoring the keyboard's own search boxes. */
    fun focusedField(svc: AccessibilityService): AccessibilityNodeInfo? {
        val focus = runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return null
        if (focus.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        if (FieldInspector.isEditableField(focus)) return focus
        // Jetpack Compose (and some custom views) report their host View as input-focused; the real text field
        // is a focused, editable virtual node underneath it.
        return focusedEditableDescendant(focus) ?: focus
    }

    private fun focusedEditableDescendant(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_FOCUS_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node !== root && node.isFocused && FieldInspector.isEditableField(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }

        return null
    }

    fun appLabel(svc: AccessibilityService, packageName: String): String {
        if (packageName.isEmpty()) return ""
        return labels.getOrPut(packageName) {
            runCatching {
                val pm = svc.packageManager
                @Suppress("DEPRECATION") // the flags overload needs API 33; minSdk is 30
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        }
    }

    /**
     * Reads the browser's address bar so URL rules (Gmail, Docs, web WhatsApp) work per tab. The bar can be
     * scrolled away, so the last value seen per browser is remembered.
     */
    fun browserUrl(svc: AccessibilityService, packageName: String): String? {
        val ids = URL_BAR_IDS[packageName] ?: return null
        val windows = runCatching { svc.windows }.getOrDefault(emptyList())
        for (w in windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != packageName) continue
            for (id in ids) {
                val text = root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && !text.contains(' ')) {
                    lastUrl[packageName] = text
                    return text
                }
            }
        }

        return lastUrl[packageName]
    }

    companion object {
        private const val MAX_FOCUS_SEARCH_NODES = 600

        val URL_BAR_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
            "com.chrome.dev" to listOf("com.chrome.dev:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "com.vivaldi.browser" to listOf("com.vivaldi.browser:id/url_bar"),
            "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
            "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "org.mozilla.firefox:id/url_bar_title"),
            "com.sec.android.app.sbrowser" to listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
        )
    }
}
