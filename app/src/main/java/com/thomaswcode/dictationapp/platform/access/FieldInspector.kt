package com.thomaswcode.dictationapp.platform.access

import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** Pure-ish helpers over AccessibilityNodeInfo: what kind of field is this, and what does it contain? */
object FieldInspector {
    fun isEditableField(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)

    fun supportsSetText(node: AccessibilityNodeInfo): Boolean = node.actionList.contains(AccessibilityAction.ACTION_SET_TEXT)

    /** Passwords and PINs: never dictated into, and the bubble stays hidden (Wispr Flow does the same). */
    fun isSecret(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val type = node.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** Number, phone and date fields: dictation makes no sense there, so the bubble hides. */
    fun isNumeric(node: AccessibilityNodeInfo): Boolean {
        val cls = node.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
    }

    /**
     * The field's real text, or "" when it is showing its hint (an empty field reports the hint as its text).
     * Text equal to the hint counts as the hint only when the field shows no sign of holding real text: a draft that
     * happens to read "Meeting notes" in a field hinted "Meeting notes" is real. Returns null when the text cannot be
     * trusted (masked).
     */
    fun readableText(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        if (node.isShowingHintText) return ""
        val text = node.text?.toString() ?: return ""
        val hint = node.hintText?.toString()
        if (hint != null && hint.isNotEmpty() && text == hint && !showsRealText(node)) return ""
        return text
    }

    /**
     * Signs that a field holds real text, which TextView gives whenever it does: a reported cursor, the cursor actions,
     * movement granularities, or the character-location extra.
     */
    fun showsRealText(node: AccessibilityNodeInfo): Boolean =
        (node.textSelectionStart >= 0 && node.textSelectionEnd >= 0) ||
            node.actionList.contains(AccessibilityAction.ACTION_SET_SELECTION) ||
            node.movementGranularities != 0 ||
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY in node.availableExtraData

    /**
     * True when Android itself says the field's real text is empty: TextView (Android 10+) offers only the
     * rendering-info extra while it holds no text and adds the character-location extra as soon as it holds any.
     * WhatsApp's chat box, whose empty state reports "Message" as its text, is a TextView like this; a custom field
     * (Flutter, a hand-written delegate) with a real draft is not.
     */
    fun platformReportsEmpty(node: AccessibilityNodeInfo): Boolean {
        val extras = node.availableExtraData
        return AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY in extras && AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY !in extras
    }

    /** Long or multi-line text is never taken for a placeholder. */
    private fun placeholderSized(text: String): Boolean =
        text.isNotEmpty() && text.length <= MAX_PLACEHOLDER_LENGTH && '\n' !in text

    /**
     * True when an EditText reports short [text] but no cursor and none of the cursor actions TextView adds whenever it
     * holds real text (ACTION_SET_SELECTION and movement granularities). WhatsApp's empty chat box reports "Message"
     * this way, with no hint text; so could a custom field holding a real draft, which is why this alone never counts
     * as proof that the field is empty (see [reportsTextWithoutCursor]).
     */
    fun looksLikeStandIn(node: AccessibilityNodeInfo, text: String): Boolean =
        placeholderSized(text) && node.className?.toString() == EDIT_TEXT_CLASS &&
            node.textSelectionStart < 0 && node.textSelectionEnd < 0 &&
            !node.actionList.contains(AccessibilityAction.ACTION_SET_SELECTION) && node.movementGranularities == 0

    /**
     * True when [looksLikeStandIn] and the field is proven empty: Android says so ([platformReportsEmpty]), or it is
     * WhatsApp's chat box, whose empty state was checked on a device. Only then may the whole field be replaced by the
     * dictation; missing accessibility metadata alone never authorises that, since it would destroy a real draft.
     */
    fun reportsTextWithoutCursor(node: AccessibilityNodeInfo, text: String): Boolean =
        looksLikeStandIn(node, text) && (platformReportsEmpty(node) || node.viewIdResourceName == WHATSAPP_CHAT_BOX_ID)

    /**
     * True when [text] could be a placeholder the app did not flag as a hint, and a cursor move can tell. WhatsApp's
     * search bar holds one invisible character when empty and reports "Ask Meta AI or Search" as its text. A cursor
     * already at the end of the reported text proves it is real, and a field that cannot move its cursor cannot be
     * checked this way.
     */
    fun mayBePlaceholder(node: AccessibilityNodeInfo, text: String): Boolean =
        placeholderSized(text) &&
            node.actionList.contains(AccessibilityAction.ACTION_SET_SELECTION) &&
            maxOf(node.textSelectionStart, node.textSelectionEnd) < text.length

    /**
     * Tells a placeholder reported as text from real text: either [reportsTextWithoutCursor], or a cursor check.
     * A field only moves its cursor within its real text, so moving it to the end of [text] is refused for a
     * stand-in and accepted for real text, which then gets its cursor back. Moves the cursor only when
     * [mayBePlaceholder].
     *
     * An unreported cursor cannot be put back, so such a field is only checked when [splicing]: the text is then
     * set around the end of the reported text, where a missing cursor already counts as being. A paste goes to
     * the field's own cursor, which must not move.
     */
    fun showsPlaceholder(node: AccessibilityNodeInfo, text: String, splicing: Boolean): Boolean {
        if (reportsTextWithoutCursor(node, text)) return true
        if (!mayBePlaceholder(node, text)) return false
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        val cursorReported = start >= 0 && end >= 0
        if (!cursorReported && !splicing) return false
        if (!setSelection(node, text.length, text.length)) return true
        if (cursorReported) setSelection(node, start, end)
        return false
    }

    fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )

    /** Selection clamped to the text; a missing selection means "at the end". */
    fun selection(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) return text.length to text.length
        return minOf(start, end) to maxOf(start, end)
    }

    /**
     * Web content (Chrome, WebView apps) exposes inputs and contenteditable areas that may not honour
     * ACTION_SET_TEXT as a real edit, so those are pasted into instead.
     */
    fun inWebView(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 40) {
            if (current.className?.toString() == "android.webkit.WebView") return true
            current = current.parent
            depth++
        }

        return false
    }

    /** Identifies a field across dictations for the spacing memory. */
    fun fieldKey(node: AccessibilityNodeInfo): String =
        "${node.packageName}/${node.viewIdResourceName ?: node.className}/${node.windowId}"

    /** Longer than any placeholder; also keeps the cursor still while dictating into the middle of a document. */
    const val MAX_PLACEHOLDER_LENGTH = 80

    private const val EDIT_TEXT_CLASS = "android.widget.EditText"

    /** WhatsApp's chat box: an empty one reports "Message" as its text (verified with DUMP_FOCUS on a Galaxy S24). */
    const val WHATSAPP_CHAT_BOX_ID = "com.whatsapp:id/entry"
}
