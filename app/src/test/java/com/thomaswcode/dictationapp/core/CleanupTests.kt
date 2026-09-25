package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.ListFormatter
import com.thomaswcode.dictationapp.core.cleanup.OutputValidator
import com.thomaswcode.dictationapp.core.cleanup.PassthroughPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.PostProcessRequest
import com.thomaswcode.dictationapp.core.cleanup.PostProcessorRouter
import com.thomaswcode.dictationapp.core.cleanup.PromptBuilder
import com.thomaswcode.dictationapp.core.cleanup.PromptContext
import com.thomaswcode.dictationapp.core.cleanup.SpokenCommandNormaliser
import com.thomaswcode.dictationapp.core.cleanup.Tone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Windows CleanupTests; same inputs, same expected outputs. */
class SpokenCommandNormaliserTest {
    @Test
    fun appliesSpokenCommands() {
        val cases = listOf(
            "send it by friday period thanks" to "Send it by friday. Thanks",
            "first item new line second item" to "First item\nSecond item",
            "intro new paragraph body" to "Intro\n\nBody",
            "shopping bullet point milk bullet point eggs" to "Shopping\n- Milk\n- Eggs",
            "really question mark" to "Really?",
            "wait comma no" to "Wait, no",
            "one exclamation mark two" to "One! Two",
        )
        for ((input, expected) in cases) assertEquals(input, expected, SpokenCommandNormaliser.normalise(input))
    }

    @Test
    fun scratchThatDropsPrecedingClause() {
        assertEquals("Meet on monday. Actually tuesday", SpokenCommandNormaliser.normalise("meet on monday. we said wednesday scratch that actually tuesday"))
        assertEquals("Tuesday", SpokenCommandNormaliser.normalise("monday scratch that tuesday"))
    }

    @Test
    fun commandsAreCaseInsensitiveAndWholeWords() {
        assertEquals("A periodic table", SpokenCommandNormaliser.normalise("a periodic table"))
        assertEquals("Done.", SpokenCommandNormaliser.normalise("done PERIOD"))
    }

    @Test
    fun emptyInputGivesEmptyOutput() = assertEquals("", SpokenCommandNormaliser.normalise("   "))

    @Test
    fun capitalisesSentenceStarts() = assertEquals("Hello. How are you?", SpokenCommandNormaliser.normalise("hello. how are you?"))
}

class ListFormatterTest {
    @Test
    fun numericMarkersBecomeANumberedList() {
        val input = "Three things to do. 1. Buy milk. 2. Call mum. 3. Write the report."
        assertEquals("Three things to do.\n1. Buy milk.\n2. Call mum.\n3. Write the report.", ListFormatter.format(input))
    }

    @Test
    fun spokenNumberWordsBecomeANumberedListAndJoinersAreDropped() {
        val input = "shopping list number one buy milk and number two call mum, then number three write the report"
        assertEquals("shopping list\n1. buy milk\n2. call mum\n3. write the report", ListFormatter.format(input))
    }

    @Test
    fun proseWithNumbersIsLeftAlone() {
        for (input in listOf("version 1.2 is out and 3.4 follows", "my number one priority is sleep", "I have 2. They have 3.", "point one five percent")) {
            assertEquals(input, ListFormatter.format(input))
        }
    }

    @Test
    fun requiresARunStartingAtOne() = assertEquals("see 2. and 3. below", ListFormatter.format("see 2. and 3. below"))

    @Test
    fun worksThroughTheNormaliserWithCapitalisation() =
        assertEquals("To do.\n1. Buy milk\n2. Call mum", SpokenCommandNormaliser.normalise("to do period number one buy milk number two call mum"))

    @Test
    fun parenthesisAndColonMarkersAreAccepted() {
        assertEquals("Steps\n1. open\n2. close", ListFormatter.format("Steps 1) open 2) close"))
        assertEquals("1. first\n2. second", ListFormatter.format("1: first 2: second"))
    }

    @Test
    fun promptMentionsListFormatting() {
        val prompt = PromptBuilder.buildSystemPrompt(PromptContext(CleanupLevel.Light, Tone.Neutral, emptyList(), "app", null, null))
        assertTrue(prompt.contains("numbered list"))
        assertTrue(prompt.contains("one item per line"))
    }
}

class PromptBuilderTest {
    private fun ctx(level: CleanupLevel = CleanupLevel.Light, tone: Tone = Tone.Neutral, vararg terms: String) =
        PromptContext(level, tone, terms.toList(), "Gmail", "https://mail.google.com/mail/u/0/", "This is an email.")

    @Test
    fun promptAsksToRejoinSentencesSplitAtPauses() {
        assertTrue(PromptBuilder.buildSystemPrompt(ctx()).contains("cut where the speaker paused"))
    }

    @Test
    fun sessionsWaitForARealPauseBeforeEndingATurn() {
        // AssemblyAI's 100 ms default split sentences at every pause to think.
        val query = com.thomaswcode.dictationapp.core.session.DictationOrchestrator
            .sessionOptions(com.thomaswcode.dictationapp.core.settings.AppSettings()).buildQueryString()
        assertTrue(query, query.contains("min_turn_silence=1000"))
        assertTrue(query, query.contains("max_turn_silence=3600"))
    }

    @Test
    fun promptContainsLevelToneKeytermsAndContext() {
        val prompt = PromptBuilder.buildSystemPrompt(ctx(CleanupLevel.Medium, Tone.Formal, "LSHTM", "isoniazid"))
        listOf(
            PromptBuilder.levelInstruction(CleanupLevel.Medium),
            PromptBuilder.toneInstruction(Tone.Formal),
            "LSHTM, isoniazid",
            "Gmail",
            "mail.google.com",
            "This is an email.",
            "Never add information",
            "Example:",
        ).forEach { assertTrue(it, prompt.contains(it)) }
    }

    @Test
    fun noKeytermsSaysNone() = assertTrue(PromptBuilder.buildSystemPrompt(ctx()).contains("(none)"))

    @Test
    fun everyLevelHasAnInstructionAndExample() {
        for (level in CleanupLevel.entries) {
            val prompt = PromptBuilder.buildSystemPrompt(ctx(level))
            assertTrue(prompt.contains("Cleanup level: "))
            assertTrue(prompt.contains("Input:"))
            assertTrue(prompt.contains("Output:"))
        }
    }
}

class PostProcessorRouterTest {
    @Test
    fun noneNeutralNeedsNoLlm() {
        assertFalse(PostProcessorRouter.needsLlm(CleanupLevel.None, Tone.Neutral))
        assertTrue(PostProcessorRouter.needsLlm(CleanupLevel.None, Tone.Formal))
        assertTrue(PostProcessorRouter.needsLlm(CleanupLevel.Light, Tone.Neutral))
    }

    @Test
    fun passthroughNormalisesCommandsWithoutNetwork() = runBlocking {
        val result = PassthroughPostProcessor().process("hello period new line bye", PostProcessRequest(CleanupLevel.None, Tone.Neutral, emptyList(), "app", null, null))
        assertEquals("Hello.\nBye", result.text)
        assertFalse(result.applied)
        assertNull(result.model)
    }
}

class OutputValidatorTest {
    private val input = "so um I think we should ship on tuesday what do you think"

    @Test
    fun acceptsCleanOutput() = assertTrue(OutputValidator.validate(input, "I think we should ship on Tuesday. What do you think?").isValid)

    @Test
    fun rejectsBannedPrefixesAndEmpty() {
        for (output in listOf("Here is the cleaned text: I think we should ship.", "Sure! I think we should ship on Tuesday.", "", "   ")) {
            assertFalse(output, OutputValidator.validate(input, output).isValid)
        }
    }

    @Test
    fun rejectsNull() = assertFalse(OutputValidator.validate(input, null).isValid)

    @Test
    fun stripsFencesAndQuotes() {
        assertEquals("Ship on Tuesday.", OutputValidator.strip("```text\nShip on Tuesday.\n```"))
        assertEquals("Ship on Tuesday.", OutputValidator.strip("\"Ship on Tuesday.\""))
        assertEquals("Ship on Tuesday.", OutputValidator.strip("“Ship on Tuesday.”"))
    }

    @Test
    fun rejectsRunawayLengthAndTruncation() {
        assertFalse(OutputValidator.validate(input, List(80) { "word" }.joinToString(" ")).isValid)
        assertFalse(OutputValidator.validate(input, "Ship.").isValid)
    }

    @Test
    fun shortInputsSkipRatioChecks() = assertTrue(OutputValidator.validate("hi", "Hello there, how are you doing today?").isValid)
}
