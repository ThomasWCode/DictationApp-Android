package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.Correction
import com.thomaswcode.dictationapp.core.dictionary.CorrectionDiffer
import com.thomaswcode.dictationapp.core.dictionary.DictionaryTerm
import com.thomaswcode.dictationapp.core.dictionary.KeytermsSelector
import com.thomaswcode.dictationapp.core.history.CostEstimator
import com.thomaswcode.dictationapp.core.history.RetentionPolicy
import com.thomaswcode.dictationapp.core.insertion.ForegroundContext
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.insertion.InsertionTextFormatter
import com.thomaswcode.dictationapp.core.rules.AppRule
import com.thomaswcode.dictationapp.core.rules.AppRulesResolver
import com.thomaswcode.dictationapp.core.rules.LegacyAppRules
import com.thomaswcode.dictationapp.core.transcription.BeginMessage
import com.thomaswcode.dictationapp.core.transcription.ErrorMessage
import com.thomaswcode.dictationapp.core.transcription.SessionOptions
import com.thomaswcode.dictationapp.core.transcription.StreamingMessageParser
import com.thomaswcode.dictationapp.core.transcription.TerminationMessage
import com.thomaswcode.dictationapp.core.transcription.TranscriptAssembler
import com.thomaswcode.dictationapp.core.transcription.TurnMessage
import com.thomaswcode.dictationapp.core.transcription.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAssemblerTest {
    private fun turn(order: Int, text: String, end: Boolean, formatted: Boolean = false, utterance: String? = null) =
        TurnMessage(turnOrder = order, transcript = text, utterance = utterance ?: text, endOfTurn = end, turnIsFormatted = formatted)

    @Test
    fun pauseMarkedTextLeavesEachPauseForTheLlmToDecide() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "Dictating into the chat box.", true, true))
        a.ingest(turn(1, "Still adds a space.", true, true))
        assertEquals("Dictating into the chat box. Still adds a space.", a.finalText)
        assertEquals("Dictating into the chat box [pause] still adds a space.", a.pauseMarkedText)
    }

    @Test
    fun joinAtPauses() {
        fun j(vararg turns: String) = TranscriptAssembler.joinAtPauses(turns.toList())
        assertEquals("Is it ready? [pause] yes.", j("Is it ready?", "Yes.")) // questions keep their mark
        assertEquals("I think [pause] I know.", j("I think.", "I know.")) // "I" stays capital
        assertEquals("Um [pause] WhatsApp is odd.", j("Um.", "WhatsApp is odd.")) // mixed-case names stay
        assertEquals("The SDK [pause] NASA said so.", j("The SDK.", "NASA said so.")) // acronyms stay
        assertEquals("Wait... [pause] no.", j("Wait...", "No.")) // an ellipsis is kept
        assertEquals("One turn only.", j("One turn only."))
        assertEquals("First [pause] second.", j("First.", "", "Second.")) // empty turns are skipped
    }

    @Test
    fun pauseMarkersLeftByTheModelAreRemoved() {
        assertEquals("Hello there.", TranscriptAssembler.removePauseMarkers("Hello [pause] there."))
        assertEquals("The end.", TranscriptAssembler.removePauseMarkers("The end [pause]."))
        assertEquals("One.\nTwo.", TranscriptAssembler.removePauseMarkers("One.\n[pause] Two."))
        assertEquals("No markers here.", TranscriptAssembler.removePauseMarkers("No markers here."))
    }

    @Test
    fun joinsFinalTurnsInTurnOrderRegardlessOfArrival() {
        val a = TranscriptAssembler()
        a.ingest(turn(2, "third.", true, true))
        a.ingest(turn(0, "First.", true, true))
        a.ingest(turn(1, "second.", true, true))
        assertEquals("First. second. third.", a.finalText)
        assertEquals(3, a.turnCount)
    }

    @Test
    fun formattedFinalTurnWinsOverUnformattedDuplicate() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "hello world", true, formatted = false))
        a.ingest(turn(0, "Hello, world.", true, formatted = true))
        a.ingest(turn(0, "hello world", true, formatted = false))
        assertEquals("Hello, world.", a.finalText)
    }

    @Test
    fun partialNeverOverwritesClosedTurn() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "Done.", true, true))
        assertFalse(a.ingest(turn(0, "Do", false)))
        assertEquals("Done.", a.finalText)
    }

    @Test
    fun liveTextIncludesOpenPartialAndFinalTextIncludesTrailingPartial() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "First sentence.", true, true))
        a.ingest(turn(1, "and then", false))
        assertTrue(a.hasOpenTurn)
        assertEquals("First sentence. and then", a.liveText)
        assertEquals("First sentence. and then", a.finalText)
        assertEquals("First sentence.", a.closedText)
    }

    @Test
    fun utterancePreferredOverTranscriptThenWords() {
        assertEquals("from words", TurnMessage(words = listOf(WordInfo(text = "from"), WordInfo(text = "words"))).bestText)
        assertEquals("from transcript", TurnMessage(transcript = "from transcript", words = listOf(WordInfo(text = "x"))).bestText)
        assertEquals("from utterance", TurnMessage(transcript = "t", utterance = "from utterance").bestText)
    }

    @Test
    fun emptyTurnsAreSkippedInJoin() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "", true, true, utterance = ""))
        a.ingest(turn(1, "Only.", true, true))
        assertEquals("Only.", a.finalText)
    }

    @Test
    fun partialUpdatesReplacePreviousPartialForSameTurn() {
        val a = TranscriptAssembler()
        a.ingest(turn(0, "Please", false))
        a.ingest(turn(0, "Please send", false))
        a.ingest(turn(0, "Please send the report.", true, true))
        assertEquals("Please send the report.", a.finalText)
        assertFalse(a.hasOpenTurn)
    }
}

class SessionOptionsAndParserTest {
    @Test
    fun buildsQueryStringWithAllParameters() {
        val q = SessionOptions(
            keyterms = listOf("LSHTM", "rifapentine"),
            languageCodes = "en,de",
            minTurnSilenceMs = 400,
            maxTurnSilenceMs = 1500,
            vadThreshold = 0.25,
            inactivityTimeoutSeconds = 30,
        ).buildQueryString()
        assertTrue(q, q.startsWith("?sample_rate=16000&speech_model=universal-3-5-pro&encoding=pcm_s16le&format_turns=true"))
        assertTrue(q, q.contains("keyterms_prompt=%5B%22LSHTM%22%2C%22rifapentine%22%5D"))
        assertTrue(q, q.contains("language_codes=en%2Cde"))
        assertTrue(q, q.contains("min_turn_silence=400"))
        assertTrue(q, q.contains("max_turn_silence=1500"))
        assertTrue(q, q.contains("vad_threshold=0.25"))
        assertTrue(q, q.contains("inactivity_timeout=30"))
    }

    @Test
    fun promptIsOnlySentToProAndTruncatedAndSpacesAreNotPlus() {
        val long = "a b" + "x".repeat(2000)
        val pro = SessionOptions(prompt = long).buildQueryString()
        assertTrue(pro.contains("prompt=a%20b"))
        assertFalse(pro.contains("+"))
        assertEquals(SessionOptions.MAX_PROMPT_LENGTH + 2, pro.substringAfter("prompt=").substringBefore('&').length) // one space -> %20
        assertFalse(SessionOptions(speechModel = "universal-streaming", prompt = "hi").buildQueryString().contains("prompt="))
    }

    @Test
    fun parsesBeginTurnTerminationAndError() {
        assertEquals("abc", (StreamingMessageParser.parse("""{"type":"Begin","id":"abc","expires_at":123}""") as BeginMessage).id)
        val turn = StreamingMessageParser.parse(
            """{"type":"Turn","turn_order":2,"turn_is_formatted":true,"end_of_turn":true,"transcript":"Hi.","utterance":"Hi.","words":[{"text":"Hi.","start":0,"end":1,"confidence":0.9,"word_is_final":true}],"extra":1}""",
        ) as TurnMessage
        assertEquals(2, turn.turnOrder)
        assertTrue(turn.endOfTurn && turn.turnIsFormatted)
        assertEquals("Hi.", turn.bestText)
        assertEquals(4.5, (StreamingMessageParser.parse("""{"type":"Termination","audio_duration_seconds":4.5,"session_duration_seconds":6}""") as TerminationMessage).audioDurationSeconds, 0.0)
        assertEquals("bad key", (StreamingMessageParser.parse("""{"error":"bad key"}""") as ErrorMessage).error)
        assertEquals("Invalid API key", (StreamingMessageParser.parse("""{"type":"Error","error":"Invalid API key"}""") as ErrorMessage).error)
        assertTrue((StreamingMessageParser.parse("""{"type":"Error","error":{"code":1008}}""") as ErrorMessage).error!!.contains("1008"))
        assertEquals("unknown error", (StreamingMessageParser.parse("""{"type":"Error"}""") as ErrorMessage).error)
        assertNull(StreamingMessageParser.parse("""{"type":"SpeechStarted"}"""))
    }
}

class AppRulesResolverTest {
    private fun ctx(pkg: String, url: String? = null) = ForegroundContext(1, pkg, pkg, "title", url, true, false, "test")

    private fun resolve(c: ForegroundContext, rules: List<AppRule> = LegacyAppRules.seed(), insert: InsertMethod = InsertMethod.Direct) =
        AppRulesResolver.resolve(c, rules, Tone.Neutral, CleanupLevel.Light, insert)

    @Test
    fun newSettingsHaveNoRulesSoEveryAppUsesTheDefaults() {
        val settings = com.thomaswcode.dictationapp.core.settings.AppSettings()
        assertTrue(settings.appRules.isEmpty())
        val r = AppRulesResolver.resolve(ctx("com.google.android.gm"), settings.appRules, Tone.Casual, CleanupLevel.High, InsertMethod.Direct)
        assertEquals(Tone.Casual, r.tone)
        assertEquals(CleanupLevel.High, r.level)
        assertEquals("default", r.matchedBy)
    }

    @Test
    fun legacySeededTargetsAreRecognisedWhateverTheirStyle() {
        assertTrue(LegacyAppRules.isSeededTarget(AppRule(packageGlob = "COM.WHATSAPP", tone = Tone.Formal)))
        assertTrue(LegacyAppRules.isSeededTarget(AppRule(urlHost = "mail.google.com")))
        assertFalse(LegacyAppRules.isSeededTarget(AppRule(packageGlob = "com.example.notes")))
    }

    @Test
    fun onlyUnmodifiedSeedsAreTreatedAsSeeds() {
        val gmail = LegacyAppRules.seed().first { it.packageGlob == "com.google.android.gm" }
        assertTrue(LegacyAppRules.isUnmodifiedSeed(gmail))
        assertTrue(LegacyAppRules.isUnmodifiedSeed(gmail.copy(tone = Tone.Casual, level = CleanupLevel.High, label = null)))
        assertFalse(LegacyAppRules.isUnmodifiedSeed(gmail.copy(insertMethod = InsertMethod.Direct)))
        assertFalse(LegacyAppRules.isUnmodifiedSeed(gmail.copy(hint = "Replies to my supervisor.")))
        assertFalse(LegacyAppRules.isUnmodifiedSeed(gmail.copy(enabled = false)))
        assertFalse(LegacyAppRules.isUnmodifiedSeed(AppRule(packageGlob = "com.example.notes")))
    }

    @Test
    fun legacySeedRulesResolveAsBefore() {
        assertEquals(Tone.Formal, resolve(ctx("com.google.android.gm")).tone)
        assertEquals(InsertMethod.Paste, resolve(ctx("com.google.android.gm")).insertMethod)
        assertEquals(Tone.Casual, resolve(ctx("com.whatsapp")).tone)
        assertEquals(Tone.Formal, resolve(ctx("com.android.chrome", "https://mail.google.com/mail/u/0/#inbox")).tone)
        assertEquals(Tone.Formal, resolve(ctx("com.android.chrome", "mail.google.com/mail/u/0")).tone)
        assertEquals(CleanupLevel.None, resolve(ctx("com.termux")).level)
        assertEquals("default", resolve(ctx("com.example.notes")).matchedBy)
    }

    @Test
    fun urlRuleBeatsPackageRuleBeatsDefault() {
        val rules = listOf(
            AppRule(packageGlob = "com.android.chrome", tone = Tone.Casual, level = CleanupLevel.High),
            AppRule(urlHost = "docs.google.com", tone = Tone.Formal),
        )
        val r = resolve(ctx("com.android.chrome", "https://docs.google.com/document/d/1"), rules, InsertMethod.Paste)
        assertEquals(Tone.Formal, r.tone)
        assertEquals(CleanupLevel.High, r.level)
        assertEquals(InsertMethod.Paste, r.insertMethod)
        assertEquals("url:docs.google.com", r.matchedBy)
    }

    @Test
    fun disabledRulesAreSkippedAndFirstMatchWins() {
        val rules = listOf(
            AppRule(packageGlob = "com.Slack", tone = Tone.Formal, enabled = false),
            AppRule(packageGlob = "com.sl*", tone = Tone.Casual),
            AppRule(packageGlob = "com.Slack", tone = Tone.Formal),
        )
        assertEquals(Tone.Casual, resolve(ctx("com.Slack"), rules).tone)
    }

    @Test
    fun hostMatchingIsSuffixOnLabelBoundary() {
        assertTrue(AppRulesResolver.hostMatches("mail.google.com", "google.com"))
        assertTrue(AppRulesResolver.hostMatches("mail.google.com", "mail.google.com"))
        assertFalse(AppRulesResolver.hostMatches("notgoogle.com", "google.com"))
        assertFalse(AppRulesResolver.hostMatches("google.com", "mail.google.com"))
        assertTrue(AppRulesResolver.hostMatches("MAIL.GOOGLE.COM", ".google.com"))
    }

    @Test
    fun globMatching() {
        assertTrue(AppRulesResolver.globMatches("com.whatsapp", "COM.WHATSAPP"))
        assertTrue(AppRulesResolver.globMatches("com.microsoft.teams", "com.microsoft.*"))
        assertTrue(AppRulesResolver.globMatches("com.whatsapp", "com.whatsap?"))
        assertFalse(AppRulesResolver.globMatches("com.whatsapp.w4b", "com.whatsapp"))
        assertFalse(AppRulesResolver.globMatches("comxwhatsapp", "com.whatsapp")) // the dot is literal
        assertFalse(AppRulesResolver.globMatches("", "com.whatsapp"))
    }

    @Test
    fun urlHostIsExtractedFromContext() {
        assertEquals("mail.google.com", ctx("c", "https://mail.google.com/mail").urlHost)
        assertEquals("teams.microsoft.com", ctx("c", "teams.microsoft.com/chat").urlHost)
        assertNull(ctx("c", "not a url at all").urlHost)
        assertNull(ctx("c").urlHost)
    }
}

class DictionaryTest {
    @Test
    fun keytermsOrderStarredThenUsageThenRecencyAndCap() {
        val now = System.currentTimeMillis()
        val terms = (0 until 150).map { DictionaryTerm("term$it", useCount = it, addedAt = now - it * 86_400_000L) } +
            DictionaryTerm("starred", starred = true, addedAt = now - 365L * 86_400_000L) +
            DictionaryTerm("x".repeat(51), starred = true) +
            DictionaryTerm("  ") +
            DictionaryTerm("TERM149")
        val selected = KeytermsSelector.select(terms)
        assertEquals(100, selected.size)
        assertEquals("starred", selected[0])
        assertEquals("term149", selected[1])
        assertTrue(selected.none { it.length > 50 })
        assertEquals(selected.size, selected.map { it.lowercase() }.distinct().size)
    }

    @Test
    fun recencyBreaksTies() {
        val now = System.currentTimeMillis()
        val terms = listOf(DictionaryTerm("old", useCount = 1, lastUsedAt = now - 2 * 86_400_000L), DictionaryTerm("new", useCount = 1, lastUsedAt = now))
        assertEquals(listOf("new", "old"), KeytermsSelector.select(terms))
    }

    @Test
    fun differFindsReplacedWords() {
        val diff = CorrectionDiffer.diff("please start the ice on ice id today", "please start the isoniazid today")
        assertEquals(1, diff.size)
        assertEquals("ice on ice id", diff[0].from)
        assertEquals("isoniazid", diff[0].to)
    }

    @Test
    fun differIgnoresCaseAndPunctuationOnlyChanges() = assertTrue(CorrectionDiffer.diff("hello world", "Hello, world.").isEmpty())

    @Test
    fun pureInsertionsAndDeletionsAreNotCorrections() {
        assertTrue(CorrectionDiffer.diff("a b c", "a c").isEmpty())
        assertTrue(CorrectionDiffer.diff("a c", "a b c").isEmpty())
    }

    @Test
    fun suggestTermsStripsPunctuationAndDedupes() {
        val terms = CorrectionDiffer.suggestTerms(listOf(Correction("lsh tm", "LSHTM,"), Correction("lsh tm", "LSHTM"), Correction("x", "a")))
        assertEquals(listOf("LSHTM"), terms)
    }

    @Test
    fun multipleCorrectionsInOneSentence() {
        val diff = CorrectionDiffer.diff("the rifle pentane dose and eye so nice id", "the rifapentine dose and isoniazid")
        assertEquals(2, diff.size)
        assertEquals("rifapentine", diff[0].to)
        assertEquals("isoniazid", diff[1].to)
    }
}

class InsertionTextFormatterTest {
    @Test
    fun pureApplyRules() {
        val cases = listOf(
            Triple("hello", null, "hello"),
            Triple("hello", "d", " hello"),
            Triple("hello", " ", "hello"),
            Triple("hello", ".", " Hello"),
            Triple("hello", "\n", "Hello"),
            Triple("hello", "?", " Hello"),
            Triple(", and more", "d", ", and more"),
            Triple("hello", "(", "hello"),
            Triple("Hello", ".", " Hello"),
            Triple("", "x", ""),
        )
        for ((text, tail, expected) in cases) assertEquals("$text after $tail", expected, InsertionTextFormatter.apply(text, tail))
    }

    @Test
    fun directInsertVerification() {
        // Replacing a selection with identical words leaves the field unchanged but is still a success (no paste).
        assertTrue(InsertionTextFormatter.directInsertApplied("hello", "hello", "hello"))
        assertTrue(InsertionTextFormatter.directInsertApplied("", "Hi there.", "Hi there."))
        // An app that normalises the text it is given still counts.
        assertTrue(InsertionTextFormatter.directInsertApplied("", "hi there", "Hi there"))
        // Ignored action: unchanged field, different expectation -> paste instead.
        assertFalse(InsertionTextFormatter.directInsertApplied("abc", "abc def", "abc"))
        assertFalse(InsertionTextFormatter.directInsertApplied("abc", "abc def", null))
    }

    @Test
    fun trailingSpaceOnlyWhenRunningIntoAWord() {
        assertEquals(" ", InsertionTextFormatter.trailingFor("big", 'w'))
        assertEquals("", InsertionTextFormatter.trailingFor("big", '.'))
        assertEquals("", InsertionTextFormatter.trailingFor("big ", 'w'))
        assertEquals("", InsertionTextFormatter.trailingFor("big", null))
    }

    @Test
    fun tracksTailPerFieldAndForgetsAfterMemoryWindow() {
        var now = 0L
        val f = InsertionTextFormatter(clock = { now }, memoryMs = 10 * 60_000L)
        assertEquals("first sentence.", f.format("first sentence.", "a"))
        assertEquals(" Second one", f.format("second one", "a"))
        assertEquals("other field", f.format("other field", "b"))
        assertEquals(" continues", f.format("continues", "a"))
        now += 11 * 60_000L
        assertEquals("fresh start", f.format("fresh start", "a"))
    }

    @Test
    fun resetClearsMemory() {
        val f = InsertionTextFormatter()
        f.format("abc", "k")
        f.reset("k")
        assertEquals("def", f.format("def", "k"))
    }
}

class CostAndRetentionTest {
    @Test
    fun estimatesStreamingAndLlmCosts() {
        assertEquals(0.45, CostEstimator.sttCost("universal-3-5-pro", 3600.0), 1e-9)
        assertEquals(0.15, CostEstimator.sttCost("universal-streaming", 3600.0), 1e-9)
        assertEquals(0.000125, CostEstimator.sttCost("universal-3-5-pro", 1.0), 1e-9)
        assertEquals(0.0, CostEstimator.llmCost("openai/gpt-oss-120b", 1000, 100), 0.0)
        assertEquals(0.0, CostEstimator.llmCost("unknown", 1000, 100), 0.0)
    }

    @Test
    fun retentionPoliciesMapToSpans() {
        assertEquals(24L * 3_600_000, RetentionPolicy.Hours24.millis)
        assertEquals(14L * 86_400_000, RetentionPolicy.Days14.millis)
        assertNull(RetentionPolicy.Forever.millis)
    }
}
