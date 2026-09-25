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

        assertTrue(FieldInspector.showsPlaceholder(node, "Ask Meta AI or Search"))
        assertEquals(listOf(21 to 21), moves)
    }

    @Test
    fun `WhatsApp chat box placeholder with the cursor at the start is recognised`() {
        val node = field("Message", cursor = 0, realLength = 1)

        assertTrue(FieldInspector.showsPlaceholder(node, "Message"))
    }

    @Test
    fun `real text keeps its cursor after the check`() {
        val node = field("Hello there", cursor = 3)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello there"))
        assertEquals(listOf(11 to 11, 3 to 3), moves)
    }

    @Test
    fun `real text with no reported cursor is left with the cursor at the end`() {
        val node = field("Hello", cursor = -1)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello"))
        assertEquals(listOf(5 to 5), moves)
    }

    @Test
    fun `a cursor already at the end proves the text is real without moving it`() {
        val node = field("Hello", cursor = 5)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello"))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `fields that cannot move their cursor are trusted rather than cleared`() {
        val node = field("Hello", cursor = 0, realLength = 0, canMoveCursor = false)

        assertFalse(FieldInspector.showsPlaceholder(node, "Hello"))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `long or multi-line text is never taken for a placeholder`() {
        val long = "a".repeat(FieldInspector.MAX_PLACEHOLDER_LENGTH + 1)

        assertFalse(FieldInspector.showsPlaceholder(field(long, cursor = 0, realLength = 0), long))
        assertFalse(FieldInspector.showsPlaceholder(field("Dear Sam,\nThanks", cursor = 0, realLength = 0), "Dear Sam,\nThanks"))
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
