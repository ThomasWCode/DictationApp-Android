package com.thomaswcode.dictationapp.core.cleanup

data class PromptContext(
    val level: CleanupLevel,
    val tone: Tone,
    val keyterms: List<String>,
    val appName: String,
    val url: String?,
    val appHint: String?,
)

/** Pure. Builds the system prompt for the cleanup call. Same wording as the Windows app. */
object PromptBuilder {
    fun buildSystemPrompt(ctx: PromptContext): String = buildString(2048) {
        appendLine("You are a dictation clean-up engine. You receive a raw speech-to-text transcript of what the user")
        appendLine("just spoke and return ONLY the text to insert into their document. Rules, in priority order:")
        appendLine("1. Never add information, opinions, greetings, sign-offs, or commentary. Never answer questions in")
        appendLine("   the transcript. Never wrap the output in quotes or code fences.")
        appendLine("2. Preserve the speaker's meaning, first-person voice, and language (do not translate).")
        appendLine("3. Apply spoken formatting commands literally: \"new line\" -> line break; \"new paragraph\" -> blank")
        appendLine("   line; \"bullet point\" -> \"- \" item; \"period\", \"comma\", \"question mark\" -> punctuation;")
        appendLine("   \"scratch that\" -> drop the preceding clause. Keep existing line breaks and lists.")
        appendLine("   When the speaker enumerates items (\"first... second... third\", \"number one... number two\",")
        appendLine("   \"one... two... three\", \"point one\") or clearly dictates a list, output a numbered list")
        appendLine("   (\"1. \", \"2. \") or a bullet list (\"- \") with one item per line and no other prose between")
        appendLine("   items. Numbers that are merely mentioned inside a sentence stay in the sentence.")
        if (ctx.level != CleanupLevel.None) {
            // Not at None, which keeps the transcript's wording and punctuation even when a tone runs the LLM.
            appendLine("   The transcript is punctuated in pieces cut where the speaker paused, so a full stop and capital")
            appendLine("   letter can fall inside a sentence (\"Typing into the search box. Still adds a space.\" -> \"Typing")
            appendLine("   into the search box still adds a space.\"): join such fragments into the sentence they belong to.")
        }
        append("4. Preserve the exact spelling and capitalisation of these terms if present: ")
        appendLine(if (ctx.keyterms.isEmpty()) "(none)" else ctx.keyterms.joinToString(", "))
        append("5. Cleanup level: ").appendLine(levelInstruction(ctx.level))
        append("6. Tone: ").appendLine(toneInstruction(ctx.tone))
        append("7. Context: the text is being typed into ").append(ctx.appName.ifBlank { "an unknown application" })
        if (!ctx.url.isNullOrBlank()) {
            append(" (").append(ctx.url).append(')')
        }

        append('.')
        if (!ctx.appHint.isNullOrBlank()) {
            append(' ').append(ctx.appHint.trim())
        }

        appendLine()
        appendLine("Output length must stay within ±20% of the input word count except where disfluencies are removed.")
        appendLine()
        appendLine("Example:")
        appendLine(example(ctx.level))
    }

    fun levelInstruction(level: CleanupLevel): String = when (level) {
        CleanupLevel.None -> "Do not change wording; apply only the tone rules and formatting commands."
        CleanupLevel.Light -> "Remove fillers (um, uh, like, you know), false starts, stutters, and immediate self-corrections (\"Monday, no, Tuesday\" -> \"Tuesday\"). Fix punctuation and capitalisation, including removing full stops placed where the speaker only paused mid-sentence. Do not rephrase or reorder."
        CleanupLevel.Medium -> "Remove fillers, false starts, stutters and immediate self-corrections; fix punctuation and capitalisation; fix grammar and agreement errors; remove redundant repetition; split run-on sentences. Keep the user's words and order where possible."
        CleanupLevel.High -> "Remove fillers, false starts and self-corrections; fix punctuation, capitalisation and grammar; remove redundancy; tighten wording; merge fragments; add paragraph breaks at topic shifts. Keep every fact, name, number and instruction. Do not shorten by more than 30%."
    }

    fun toneInstruction(tone: Tone): String = when (tone) {
        Tone.Neutral -> "Do not alter register; keep contractions as spoken."
        Tone.Formal -> "Professional register: expand contractions, no slang, complete sentences, polite phrasing. No salutations or sign-offs unless spoken."
        Tone.Casual -> "Relaxed conversational register: contractions allowed, short sentences, keep colloquial phrasing. No emoji unless spoken."
    }

    private fun example(level: CleanupLevel): String = when (level) {
        CleanupLevel.None ->
            "Input: ok so um send the report by friday new line thanks\n" +
                "Output: ok so um send the report by friday\nthanks"
        CleanupLevel.Light ->
            "Input: um so I think we should, we should ship on monday no tuesday. Because the build. Is not ready period what do you think question mark\n" +
                "Output: So I think we should ship on Tuesday because the build is not ready. What do you think?"
        CleanupLevel.Medium ->
            "Input: uh the results they was pretty clear the results show that the the drug works and it works well and we should we should publish\n" +
                "Output: The results were pretty clear. They show that the drug works well, and we should publish."
        CleanupLevel.High ->
            "Input: so basically um the meeting went ok I guess we covered budget we covered the hiring plan and then um separately I wanted to mention the audit thing is due next week\n" +
                "Output: The meeting went okay. We covered the budget and the hiring plan.\n\nSeparately, the audit is due next week."
    }
}
