package com.thomaswcode.dictationapp.platform.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a website in a browser's bar, and how long a bar that scrolled away is remembered. */
class UrlMemoryTest {
    @Test
    fun `only address-shaped text in the bar counts as a website`() {
        for (address in listOf("mail.google.com/mail/u/0/#inbox", "https://docs.google.com/document/d/1", "web.whatsapp.com", "localhost:8080/app", "192.168.1.1", "bücher.de")) {
            assertTrue(address, UrlMemory.looksLikeAddress(address))
        }

        for (other in listOf("", "weather tomorrow", "Search or type URL", "e.g", "gmail", "Inbox (3) - Gmail")) {
            assertFalse(other, UrlMemory.looksLikeAddress(other))
        }
    }

    @Test
    fun `the last address is recalled for a few minutes only`() {
        var now = 0L
        val memory = UrlMemory(clock = { now })
        memory.remember("com.android.chrome", "mail.google.com/mail")

        now = UrlMemory.TTL_MS
        assertEquals("mail.google.com/mail", memory.recall("com.android.chrome"))
        assertNull(memory.recall("org.mozilla.firefox"))

        now = UrlMemory.TTL_MS + 1
        assertNull(memory.recall("com.android.chrome"))
    }
}
