package com.thomaswcode.dictationapp.platform.access

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FieldInspectorTest {
    /** Cursor moves the field was asked for. */
    private val moves = mutableListOf<Pair<Int, Int>>()

    /**
     * An editable field reporting [reported] as its text while really holding [realLength] characters, the way
     * WhatsApp's empty chat box reports "Message" over one invisible character. Cursor moves past the real text
     * are refused, as TextView and Compose refuse them.
     */
    private fun field(reported: String, cursor: Int, realLength: Int = reported.length, canMoveCursor: Boolean = true) =
        AccessibilityNodeInfo().apply {
            isEditable = true
            text = reported
            setTextSelection(cursor, cursor)
            addAction(AccessibilityAction.ACTION_SET_TEXT)
            if (canMoveCursor) addAction(AccessibilityAction.ACTION_SET_SELECTION)
            shadowOf(this).setOnPerformActionListener { action, args ->
                if (action != AccessibilityNodeInfo.ACTION_SET_SELECTION) return@setOnPerformActionListener true
                val start = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT)
                val end = args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)
                moves += start to end
                start in 0..realLength && end in 0..realLength
            }
        }

    @Test
    fun `WhatsApp search bar placeholder over an invisible character is recognised`() {
        val node = field("Ask Meta AI or Search", cursor = 1, realLength = 1)

        assertTrue(FieldInspector.showsPlaceholder(node, "Ask Meta AI or Search", splicing = true))
        assertEquals(listOf(21 to 21), moves)
    }

    @Test
    fun `placeholder with the cursor at the start is recognised`() {
        val node = field("Message", cursor = 0, realLength = 1)

        assertTrue(FieldInspector.showsPlaceholder(node, "Message", splicing = true))
    }

    @Test
    fun `WhatsApp chat box, an EditText reporting text but no cursor actions, is recognised without moving anything`() {
        // As seen on the device: text "Message", no hint, cursor -1, no SET_SELECTION, no granularities.
        val node = field("Message", cursor = -1, realLength = 0, canMoveCursor = false).apply { className = "android.widget.EditText" }

        assertTrue(FieldInspector.showsPlaceholder(node, "Message", splicing = false))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `an EditText reporting a cursor holds real text even without cursor actions`() {
        // A custom accessibility delegate may leave the actions out; the cursor alone proves the text is real.
        val node = field("Hi Sam", cursor = 6, canMoveCursor = false).apply { className = "android.widget.EditText" }

        assertFalse(FieldInspector.showsPlaceholder(node, "Hi Sam", splicing = true))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `an EditText with real text offers cursor actions and is not taken for a placeholder`() {
        val node = field("Hi Sam", cursor = 6).apply {
            className = "android.widget.EditText"
            movementGranularities = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
        }

        assertFalse(FieldInspector.showsPlaceholder(node, "Hi Sam", splicing = false))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `real text keeps its cursor after the check`() {
        val node = field("Hello there", cursor = 3)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello there", splicing = false))
        assertEquals(listOf(11 to 11, 3 to 3), moves)
    }

    @Test
    fun `with no reported cursor, only text that is spliced at the end anyway is checked`() {
        val node = field("Hello", cursor = -1)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello", splicing = true))
        assertEquals(listOf(5 to 5), moves)
        assertTrue(FieldInspector.showsPlaceholder(field("Message", cursor = -1, realLength = 1), "Message", splicing = true))
    }

    @Test
    fun `with no reported cursor, a paste keeps the field's own cursor`() {
        val node = field("Hello", cursor = -1)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello", splicing = false))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a cursor already at the end proves the text is real without moving it`() {
        val node = field("Hello", cursor = 5)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello", splicing = true))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `custom fields that cannot move their cursor are trusted`() {
        val node = field("Hello", cursor = 0, realLength = 0, canMoveCursor = false)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello", splicing = true))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `long or multi-line text is never taken for a placeholder`() {
        val long = "a".repeat(FieldInspector.MAX_PLACEHOLDER_LENGTH + 1)

        assertFalse(FieldInspector.showsPlaceholder(field(long, cursor = 0, realLength = 0), long, splicing = true))
        assertFalse(FieldInspector.showsPlaceholder(field("Dear Sam,\nThanks", cursor = 0, realLength = 0), "Dear Sam,\nThanks", splicing = true))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `hints flagged the standard way read as empty`() {
        val node = AccessibilityNodeInfo().apply {
            isEditable = true
            text = "Type a message"
            hintText = "Type a message"
        }

        assertEquals("", FieldInspector.readableText(node))
        node.isShowingHintText = true
        node.hintText = null
        assertEquals("", FieldInspector.readableText(node))
    }
}
