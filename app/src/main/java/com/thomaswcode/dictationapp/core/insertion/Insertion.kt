package com.thomaswcode.dictationapp.core.insertion

import kotlinx.serialization.Serializable
import java.net.URI

/** How text gets into the focused field. */
@Serializable
enum class InsertMethod {
    /** Write the text at the cursor through the accessibility API; paste if the field ignores it. */
    Direct,

    /** Always paste through the clipboard (keeps rich-text formatting in editors like Gmail). */
    Paste,
}

/**
 * Snapshot of where the user was when the dictation started: the Android counterpart of the Windows
 * foreground window. [target] is an opaque platform handle to the focused field (an AccessibilityNodeInfo).
 */
data class ForegroundContext(
    val windowId: Int,
    val packageName: String,
    val appLabel: String,
    val windowTitle: String,
    val url: String?,
    val isEditable: Boolean,
    val isPassword: Boolean,
    val editableReason: String,
    val target: Any? = null,
) {
    /** Host part of [url] in lower case, or null. */
    val urlHost: String?
        get() {
            if (url.isNullOrBlank()) return null
            val candidate = if (url.contains("://")) url.trim() else "https://" + url.trim()
            return runCatching { URI(candidate).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    val appName: String get() = appLabel.ifBlank { packageName }

    companion object {
        val Unknown = ForegroundContext(-1, "", "", "", null, false, false, "unknown")
    }
}

fun interface ForegroundContextProvider {
    suspend fun capture(): ForegroundContext
}

enum class InsertionOutcome { Inserted, CopiedOnly, Failed }

/** [insertedText] is what actually went into the field, including any joining space. */
data class InsertionResult(val outcome: InsertionOutcome, val reason: String? = null, val insertedText: String? = null)

interface TextInserter {
    /**
     * Inserts [text] into the focused field (or the field in [target] if focus moved nowhere). On
     * [InsertionOutcome.CopiedOnly] the text has been left on the clipboard.
     */
    suspend fun insert(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult
}

interface Clipboard {
    suspend fun setText(text: String)
}

enum class ToastKind { Info, Success, Warning, Error }

interface Notifier {
    fun toast(title: String, message: String, kind: ToastKind = ToastKind.Info)

    /** Brings up the app so the user can grant the microphone permission (the Windows privacy-page link). */
    fun requestMicrophonePermission() = Unit
}

/**
 * Pure. Decides the joining whitespace and capitalisation between consecutive insertions. On Android the
 * character before the cursor is usually readable and is passed straight to [apply]; when it is not (web
 * fields, custom editors) the last insertion per field is remembered instead, as on Windows.
 */
class InsertionTextFormatter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val memoryMs: Long = 10 * 60 * 1000L,
) {
    private val lastByField = HashMap<String, Pair<String, Long>>()
    private val lock = Any()

    fun format(text: String, fieldKey: String): String = synchronized(lock) {
        if (text.isEmpty()) return ""
        val now = clock()
        val tail = lastByField[fieldKey]?.takeIf { now - it.second <= memoryMs }?.first
        val result = apply(text, tail)
        remember(fieldKey, result)
        result
    }

    /** Records what went into [fieldKey] so a later unreadable insertion can still be spaced correctly. */
    fun remember(fieldKey: String, inserted: String) = synchronized(lock) {
        lastByField[fieldKey] = (if (inserted.isNotEmpty()) inserted.last().toString() else "") to clock()
    }

    fun reset(fieldKey: String) = synchronized(lock) { lastByField.remove(fieldKey); Unit }

    fun resetAll() = synchronized(lock) { lastByField.clear() }

    companion object {
        /** Pure core: [previousTail] is the character before the cursor (or the last we inserted), or null. */
        fun apply(text: String, previousTail: String?): String {
            if (text.isEmpty()) return ""
            val prev = if (previousTail.isNullOrEmpty()) '\u0000' else previousTail.last()
            var s = text
            val needsSpace = prev != '\u0000' && !prev.isWhitespace() && s[0].isLetterOrDigit() &&
                prev != '(' && prev != '[' && prev != '"' && prev != '\''
            val afterSentenceEnd = prev == '.' || prev == '!' || prev == '?' || prev == '\n'
            if (afterSentenceEnd && s[0].isLetter() && s[0].isLowerCase()) {
                s = s[0].uppercaseChar() + s.substring(1)
            }

            return if (needsSpace) " $s" else s
        }

        /**
         * Whether a direct (SET_TEXT) insertion took effect, judged from the field read back afterwards. The field
         * now holding [expected] is success even when that equals [before] (dictating the same words over a
         * selection of them); otherwise any change counts, since some apps normalise the text they are given.
         * An unchanged field means the app ignored the action and the text should be pasted instead.
         */
        fun directInsertApplied(before: String, expected: String, after: String?): Boolean =
            after != null && (after == expected || after != before)

        /** A space after the insertion when it would otherwise run into the next word. */
        fun trailingFor(inserted: String, nextChar: Char?): String =
            if (nextChar != null && nextChar.isLetterOrDigit() && inserted.isNotEmpty() && !inserted.last().isWhitespace()) " " else ""
    }
}
