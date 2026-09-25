package com.thomaswcode.dictationapp.platform.access

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
     * Returns null when the text cannot be trusted (masked).
     */
    fun readableText(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        if (node.isShowingHintText) return ""
        val text = node.text?.toString() ?: return ""
        val hint = node.hintText?.toString()
        if (hint != null && hint.isNotEmpty() && text == hint) return ""
        return text
    }

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
}
