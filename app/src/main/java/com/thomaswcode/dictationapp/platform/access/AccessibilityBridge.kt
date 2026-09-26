package com.thomaswcode.dictationapp.platform.access

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineDispatcher
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
    /** Accessibility nodes are used on the main thread; replaceable so tests can run the insertion routes directly. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ForegroundContextProvider, TextInserter {
    @Volatile var service: AccessibilityService? = null

    private val formatter = InsertionTextFormatter()
    private val labels = ConcurrentHashMap<String, String>()
    private val urls = UrlMemory(clock = clock)

    val isConnected: Boolean get() = service != null

    override suspend fun capture(): ForegroundContext = withContext(main) {
        val svc = service ?: return@withContext ForegroundContext.Unknown
        val focus = focusedField(svc)
        val pkg = focus?.packageName?.toString() ?: svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        val label = appLabel(svc, pkg)
        val title = focus?.window?.title?.toString()?.takeIf { it.isNotBlank() } ?: label
        val editable = focus != null && FieldInspector.isEditableField(focus)
        val url = browserUrl(svc, pkg)
        ForegroundContext(
            windowId = focus?.windowId ?: -1,
            packageName = pkg,
            appLabel = label,
            windowTitle = title,
            url = url?.address,
            urlSource = when {
                url == null -> "none"
                url.remembered -> "remembered"
                else -> "live"
            },
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

    override suspend fun insert(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult = withContext(main) {
        val svc = service
        val focused = svc?.let { focusedField(it) }?.takeIf { FieldInspector.isEditableField(it) }

        // No field has input focus (focus went nowhere, e.g. the app cleared it when the keyboard closed): the field
        // the dictation started in, if it is still on screen (refresh fails for a window that is gone or covered).
        val node = focused ?: (target.target as? AccessibilityNodeInfo)?.takeIf { it.refresh() && FieldInspector.isEditableField(it) }
        if (node == null) {
            clipboard.setText(text)
            return@withContext InsertionResult(InsertionOutcome.CopiedOnly, "No text field has focus, text copied", text)
        }

        val fromFallback = focused == null
        if (fromFallback) logger.info("No field has input focus; using the field the dictation started in (${node.className} in ${node.packageName})")
        if (FieldInspector.isSecret(node)) {
            clipboard.setText(text)
            return@withContext InsertionResult(InsertionOutcome.CopiedOnly, "Not typing into a password field, text copied", text)
        }

        node.refresh() // past the accessibility cache: the field's text as it is now
        val key = FieldInspector.fieldKey(node)
        val reported = FieldInspector.readableText(node)
        var current = reported
        // Read before the placeholder check, which may move the cursor and put it back.
        var selection = current?.let { FieldInspector.selection(node, it) } ?: (0 to 0)
        val cursorReported = node.textSelectionStart >= 0 && node.textSelectionEnd >= 0
        val direct = method == InsertMethod.Direct && FieldInspector.supportsSetText(node) && !FieldInspector.inWebView(node)
        val placeholder = current != null && FieldInspector.showsPlaceholder(node, current, splicing = direct)

        // Shaped like WhatsApp's empty chat box but not proven empty: its text is kept (it may be a draft) and the
        // dictation goes at its end, and since that may include a placeholder, the text is left on the clipboard too.
        val unsure = !placeholder && current != null && FieldInspector.looksLikeStandIn(node, current)
        if (placeholder) {
            logger.info("${node.className} in ${node.packageName} reports its placeholder as text; the field is empty")
            current = ""
            selection = 0 to 0
        }

        val formatted = if (current != null) {
            val prev = if (selection.first > 0) current[selection.first - 1].toString() else null
            val body = InsertionTextFormatter.apply(text, prev)
            body + InsertionTextFormatter.trailingFor(body, current.getOrNull(selection.second))
        } else {
            formatter.format(text, key)
        }

        // Direct: splice the text in at the cursor. Verified by reading the field back; a field that ignores
        // SET_TEXT (custom editors) is left untouched and gets a paste instead. A field that reports a placeholder
        // as text is not spliced into: its real content is hidden (WhatsApp keeps an invisible character in its
        // search bar), and a paste lands at its own cursor without replacing anything.
        if (direct && current != null && !placeholder) {
            val newText = current.substring(0, selection.first) + formatted + current.substring(selection.second)
            if (setTextVerified(node, newText, selection.first + formatted.length, before = current)) {
                formatter.remember(key, formatted)
                if (unsure) {
                    clipboard.setText(formatted.trimStart()) // for pasting by hand, so without the joining space
                    logger.info("${node.className} in ${node.packageName} may show a placeholder; text kept, dictation added at its end and copied")
                    return@withContext InsertionResult(InsertionOutcome.Inserted, "Added at the end of the field's text and also copied, in case that text was a placeholder", formatted, leftOnClipboard = true)
                }

                return@withContext InsertionResult(InsertionOutcome.Inserted, null, formatted)
            }

            logger.info("SET_TEXT was ignored by ${node.className} in ${node.packageName}; pasting instead")
        }

        // A proven-empty stand-in with no cursor (WhatsApp's empty chat box) refuses a paste, since TextView pastes only
        // at a cursor, so the dictation becomes its text and the clipboard is left alone. This runs even when an app
        // rule says Paste, which such a field cannot take. A placeholder found only by the cursor check is pasted.
        if (placeholder && !cursorReported && FieldInspector.supportsSetText(node) && !FieldInspector.inWebView(node) &&
            FieldInspector.reportsTextWithoutCursor(node, reported.orEmpty()) &&
            setTextVerified(node, formatted, formatted.length, before = reported.orEmpty())
        ) {
            formatter.remember(key, formatted)
            return@withContext InsertionResult(InsertionOutcome.Inserted, null, formatted)
        }

        // A field the user has left (no input focus any more) is not pasted into: Chromium reports a paste into a
        // blurred web field as done although nothing arrives. The text goes to the clipboard instead.
        if (fromFallback && !node.isFocused) {
            val copied = formatted.trimStart()
            clipboard.setText(copied)
            return@withContext InsertionResult(InsertionOutcome.CopiedOnly, "The field lost focus, text copied", copied)
        }

        // Paste: Android does not let a background app read the clipboard, so it cannot be restored afterwards.
        clipboard.setText(formatted)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            formatter.remember(key, formatted)
            InsertionResult(InsertionOutcome.Inserted, null, formatted)
        } else {
            val copied = formatted.trimStart()
            if (copied != formatted) clipboard.setText(copied) // it will be pasted by hand, so without the joining space
            InsertionResult(InsertionOutcome.CopiedOnly, "This field does not accept pasting, text copied", copied)
        }
    }

    /**
     * Sets the field's text, puts the cursor at [cursor] and reads the field back: true when the text changed from
     * [before] (a field may reformat what it is given) or reads as [newText].
     */
    private suspend fun setTextVerified(node: AccessibilityNodeInfo, newText: String, cursor: Int, before: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        FieldInspector.setSelection(node, cursor, cursor)
        delay(80)
        node.refresh()
        return InsertionTextFormatter.directInsertApplied(before, newText, FieldInspector.readableText(node))
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

    data class BrowserUrl(val address: String, val remembered: Boolean)

    /**
     * Reads the browser's address bar so URL rules (Gmail, Docs, web WhatsApp) work per tab. The focused window is read
     * first, and only address-shaped text counts: a search being typed into the bar is not a website. The bar scrolls
     * away with the page, so the last address seen is used for a few minutes after ([UrlMemory]); the service reads
     * the bar at every focus and window change, which keeps that memory on the tab now in front.
     */
    fun browserUrl(svc: AccessibilityService, packageName: String): BrowserUrl? {
        val ids = URL_BAR_IDS[packageName] ?: return null
        val windows = runCatching { svc.windows }.getOrDefault(emptyList())
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.isActive })
        for (w in windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != packageName) continue
            for (id in ids) {
                val bar = root.findAccessibilityNodeInfosByViewId(id).firstOrNull() ?: continue
                val text = bar.text?.toString()?.trim().orEmpty()
                if (UrlMemory.looksLikeAddress(text)) {
                    urls.remember(packageName, text)
                    return BrowserUrl(text, remembered = false)
                }

                // The bar is on screen without an address (a search being typed, a new tab): no website.
                return null
            }
        }

        return urls.recall(packageName)?.let { BrowserUrl(it, remembered = true) }
    }

    companion object {
        private const val MAX_FOCUS_SEARCH_NODES = 600

        fun isBrowser(packageName: String?): Boolean = packageName != null && packageName in URL_BAR_IDS

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

/**
 * The last address seen in each browser's bar, recalled for [ttlMs] after: long enough for the bar to scroll away
 * while writing an email, short enough that a stale site's rule does not follow the user to another tab for long.
 */
class UrlMemory(private val ttlMs: Long = TTL_MS, private val clock: () -> Long) {
    private val seen = ConcurrentHashMap<String, Pair<String, Long>>()

    fun remember(packageName: String, address: String) {
        seen[packageName] = address to clock()
    }

    fun recall(packageName: String): String? = seen[packageName]?.takeIf { clock() - it.second <= ttlMs }?.first

    companion object {
        const val TTL_MS = 3 * 60_000L

        private val ADDRESS = Regex(
            """^(?:[a-z][a-z0-9+.-]*://)?(?:localhost|(?:[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?\.)+(?:\p{L}{2,}|\d{1,3}))(?::\d+)?(?:[/?#]\S*)?$""",
            RegexOption.IGNORE_CASE,
        )

        /** "mail.google.com/mail/u/0", "https://docs.google.com/…", "localhost:8080": yes; "weather tomorrow": no. */
        fun looksLikeAddress(text: String): Boolean = ADDRESS.matches(text)
    }
}
