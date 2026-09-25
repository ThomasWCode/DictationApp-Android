package com.thomaswcode.dictationapp.core.cleanup

import java.util.Locale

data class ValidationResult(val isValid: Boolean, val text: String, val reason: String?)

/**
 * Pure. Guards against the classic small-model failures: wrapping output in quotes or fences, prefacing
 * with "Here is", answering instead of cleaning, or truncating. Rejected output falls back to raw text.
 */
object OutputValidator {
    const val MAX_LENGTH_RATIO = 2.5
    const val MIN_LENGTH_RATIO = 0.4

    private val bannedPrefixes = listOf(
        "here is", "here's", "here are", "sure", "certainly", "of course", "okay, here", "ok, here",
        "the cleaned", "cleaned text:", "cleaned transcript", "output:", "as an ai", "i'm sorry", "i am sorry",
    )

    private val codeFence = Regex("""^```[a-zA-Z]*\s*\n(?<body>[\s\S]*?)\n?```\s*$""")

    fun validate(rawInput: String, modelOutput: String?): ValidationResult {
        if (modelOutput == null) {
            return ValidationResult(false, "", "empty")
        }

        val text = strip(modelOutput)
        if (text.isEmpty()) {
            return ValidationResult(false, text, "empty")
        }

        val lower = text.lowercase()
        for (prefix in bannedPrefixes) {
            if (lower.startsWith(prefix)) {
                return ValidationResult(false, text, "banned-prefix:$prefix")
            }
        }

        val inputLen = maxOf(1, rawInput.trim().length)
        val ratio = text.length.toDouble() / inputLen
        if (inputLen >= 20 && ratio > MAX_LENGTH_RATIO) {
            return ValidationResult(false, text, "too-long:" + String.format(Locale.ROOT, "%.2f", ratio))
        }

        if (inputLen >= 20 && ratio < MIN_LENGTH_RATIO) {
            return ValidationResult(false, text, "too-short:" + String.format(Locale.ROOT, "%.2f", ratio))
        }

        return ValidationResult(true, text, null)
    }

    /** Removes code fences, surrounding quotes and Windows line endings. */
    fun strip(output: String): String {
        var text = output.trim()
        codeFence.find(text)?.let { text = it.groups["body"]!!.value.trim() }
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            if ((first == '"' && last == '"') || (first == '“' && last == '”') || (first == '\'' && last == '\'')) {
                text = text.substring(1, text.length - 1).trim()
            }
        }

        return text.replace("\r\n", "\n")
    }
}
