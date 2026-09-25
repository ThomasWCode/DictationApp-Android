package com.thomaswcode.dictationapp.core.cleanup

/**
 * Pure regex pass that applies spoken formatting commands. Runs alone when cleanup is None; otherwise the
 * same commands are described to the LLM. Commands are matched as whole words, case-insensitively.
 */
object SpokenCommandNormaliser {
    // Only (?i): Android's ICU regex engine rejects the (?U) flag, and its \w and \b are Unicode-aware already.
    // A private-use character cannot collide with dictated text, unlike a word marker.
    private const val SCRATCH_MARKER = "\uE000"

    private val scratchThat = Regex("""(?i)\bscratch that\b[.,]?""")
    private val newParagraph = Regex("""(?i)\s*[.,]?\s*\bnew paragraph\b[.,]?\s*""")
    private val newLine = Regex("""(?i)\s*[.,]?\s*\b(?:new line|newline)\b[.,]?\s*""")
    private val bulletPoint = Regex("""(?i)\s*[.,]?\s*\bbullet point\b[.,]?\s*""")
    private val punctuation = Regex(
        """(?i)\s*\b(period|full stop|comma|question mark|exclamation mark|exclamation point|colon|semicolon|semi colon|open paren|open parenthesis|open bracket|close paren|close parenthesis|close bracket)\b[.,]?""",
    )
    private val collapseSpacesBeforePunctuation = Regex("""\s+([.,?!:;)])""")
    private val spaceAfterNewline = Regex("""\n[ \t]+""")
    private val multiSpace = Regex("""[ \t]{2,}""")

    fun normalise(text: String): String {
        if (text.isBlank()) {
            return ""
        }

        var s = text.trim()
        s = scratchThat.replace(s) { SCRATCH_MARKER }
        s = newParagraph.replace(s) { "\n\n" }
        s = newLine.replace(s) { "\n" }
        s = bulletPoint.replace(s) { "\n- " }
        s = punctuation.replace(s) { m -> punctuationFor(m.groupValues[1]) }
        s = ListFormatter.format(s)
        s = collapseSpacesBeforePunctuation.replace(s) { m -> m.groupValues[1] }
        s = spaceAfterNewline.replace(s) { "\n" }
        s = multiSpace.replace(s) { " " }
        s = capitaliseAfterSentenceEnd(resolveScratches(s))
        return s.trim()
    }

    private fun punctuationFor(command: String): String = when (command.lowercase()) {
        "period", "full stop" -> "."
        "comma" -> ","
        "question mark" -> "?"
        "exclamation mark", "exclamation point" -> "!"
        "colon" -> ":"
        "semicolon", "semi colon" -> ";"
        "open paren", "open parenthesis", "open bracket" -> " ("
        "close paren", "close parenthesis", "close bracket" -> ")"
        else -> command
    }

    /** Drops the text before each "scratch that" back to the previous sentence boundary or line start. */
    private fun resolveScratches(input: String): String {
        var s = input
        while (s.contains(SCRATCH_MARKER)) {
            val idx = s.indexOf(SCRATCH_MARKER)
            var before = s.substring(0, idx)
            val after = s.substring(idx + SCRATCH_MARKER.length)
            val cut = maxOf(maxOf(before.lastIndexOf('.'), before.lastIndexOf('?')), maxOf(before.lastIndexOf('!'), before.lastIndexOf('\n')))
            before = if (cut >= 0) before.substring(0, cut + 1) else ""
            s = before.trimEnd() + if (after.isNotEmpty()) " " + after.trimStart() else ""
        }

        return s
    }

    private fun capitaliseAfterSentenceEnd(s: String): String {
        val sb = StringBuilder(s.length)
        var capitalise = true
        for (ch in s) {
            if (capitalise && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalise = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') {
                    capitalise = true
                } else if (!ch.isWhitespace() && ch != '-' && ch != '"') {
                    capitalise = false
                }
            }
        }

        return sb.toString()
    }
}
