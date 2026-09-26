package com.thomaswcode.dictationapp.platform.access

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.thomaswcode.dictationapp.core.FakeClipboard
import com.thomaswcode.dictationapp.core.NoLogger
import com.thomaswcode.dictationapp.core.insertion.ForegroundContext
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.insertion.InsertionOutcome
import com.thomaswcode.dictationapp.core.insertion.InsertionResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The route dictated text takes into a field: the whole field's text, a splice at the cursor, a paste, or the clipboard
 * alone. The service is off, so the field the dictation started in is used, as when focus went nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AccessibilityBridgeTest {
    private val clipboard = FakeClipboard()
    private val performed = mutableListOf<Int>()

    /**
     * An EditText holding [real] while reporting [reported] (a stand-in when they differ), the way TextView behaves:
     * SET_TEXT replaces the text, the cursor moves only within the real text, and a paste needs focus.
     */
    private fun field(reported: String, real: String = reported, cursor: Int = -1, cursorActions: Boolean = true, focused: Boolean = true, id: String? = null) =
        AccessibilityNodeInfo().apply {
            className = "android.widget.EditText"
            packageName = "com.whatsapp"
            viewIdResourceName = id
            isEditable = true
            isFocused = focused
            text = reported
            setTextSelection(cursor, cursor)
            addAction(AccessibilityAction.ACTION_SET_TEXT)
            addAction(AccessibilityAction.ACTION_PASTE)
            if (cursorActions) addAction(AccessibilityAction.ACTION_SET_SELECTION)
            var realText = real
            shadowOf(this).setOnPerformActionListener { action, args ->
                performed += action
                when (action) {
                    AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                        realText = args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString()
                        text = realText
                        true
                    }
                    AccessibilityNodeInfo.ACTION_SET_SELECTION -> args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT) in 0..realText.length
                    AccessibilityNodeInfo.ACTION_PASTE -> isFocused
                    else -> false
                }
            }
        }

    private fun insert(node: AccessibilityNodeInfo, method: InsertMethod = InsertMethod.Direct): InsertionResult {
        lateinit var result: InsertionResult
        runTest {
            val bridge = AccessibilityBridge(clipboard, NoLogger, StandardTestDispatcher(testScheduler))
            val target = ForegroundContext(node.windowId, "com.whatsapp", "WhatsApp", "Chat", null, true, false, "EditText", target = node)
            result = bridge.insert("Hello there.", target, method)
        }

        return result
    }

    @Test
    fun `WhatsApp's empty chat box gets the dictation as its whole text and the clipboard is left alone`() {
        for (method in InsertMethod.entries) {
            // A paste into it would fail (no cursor), so even an app rule asking for Paste gets the text set.
            performed.clear()
            val node = field("Message", real = "", cursorActions = false, id = FieldInspector.WHATSAPP_CHAT_BOX_ID)

            val result = insert(node, method)

            assertEquals(method.name, InsertionOutcome.Inserted, result.outcome)
            assertEquals("Hello there.", node.text.toString())
            assertFalse(AccessibilityNodeInfo.ACTION_PASTE in performed)
        }

        assertTrue(clipboard.texts.isEmpty())
    }

    @Test
    fun `WhatsApp's search bar placeholder is pasted over, never replaced`() {
        for (cursor in listOf(1, -1)) {
            // Empty, it holds one invisible character and reports its placeholder, with or without a cursor.
            performed.clear()
            val node = field("Ask Meta AI or Search", real = "​", cursor = cursor, id = "com.whatsapp:id/search_input")

            val result = insert(node)

            assertEquals("cursor $cursor", InsertionOutcome.Inserted, result.outcome)
            assertFalse("cursor $cursor", AccessibilityNodeInfo.ACTION_SET_TEXT in performed)
            assertTrue("cursor $cursor", AccessibilityNodeInfo.ACTION_PASTE in performed)
        }

        assertEquals(listOf("Hello there.", "Hello there."), clipboard.texts.toList())
    }

    @Test
    fun `a field that only looks like a stand-in keeps its text and the dictation is also copied`() {
        // A Flutter-style field reporting a real draft without a cursor: not proven empty, so nothing is replaced.
        val node = field("Draft reply", cursorActions = false)

        val result = insert(node)

        assertEquals(InsertionOutcome.Inserted, result.outcome)
        assertTrue(result.leftOnClipboard)
        assertEquals("Draft reply Hello there.", node.text.toString())
        assertEquals(listOf("Hello there."), clipboard.texts.toList())
    }

    @Test
    fun `a field that lost focus is not pasted into`() {
        // Chromium reports a paste into a blurred web field as done although nothing arrives.
        val node = field("Hello", cursor = 5, focused = false)

        val result = insert(node, InsertMethod.Paste)

        assertEquals(InsertionOutcome.CopiedOnly, result.outcome)
        assertEquals("The field lost focus, text copied", result.reason)
        assertFalse(AccessibilityNodeInfo.ACTION_PASTE in performed)
        assertEquals(listOf("Hello there."), clipboard.texts.toList())
    }

    @Test
    fun `a field with real text gets the dictation spliced in at its cursor`() {
        val node = field("Hi Sam", cursor = 6).apply { movementGranularities = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER }

        val result = insert(node)

        assertEquals(InsertionOutcome.Inserted, result.outcome)
        assertFalse(result.leftOnClipboard)
        assertEquals("Hi Sam Hello there.", node.text.toString())
        assertTrue(clipboard.texts.isEmpty())
    }
}
