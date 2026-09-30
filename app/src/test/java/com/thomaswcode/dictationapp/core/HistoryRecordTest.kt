package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.history.DictationRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** What History copies: never the joining space a dictation got after existing text (as on Windows). */
class HistoryRecordTest {
    @Test
    fun copy_leaves_out_the_joining_space_the_text_was_inserted_with() {
        val appended = DictationRecord(rawTranscript = "second part", insertedText = " Second part.")

        assertEquals(" Second part.", appended.displayText) // History shows what went into the field
        assertEquals("Second part.", appended.copyText) // but may be pasted into an empty field
    }

    @Test
    fun copy_falls_back_to_the_raw_transcript_when_nothing_was_inserted() {
        assertEquals("heard words", DictationRecord(rawTranscript = "heard words").copyText)
        assertEquals("", DictationRecord().copyText)
    }
}
