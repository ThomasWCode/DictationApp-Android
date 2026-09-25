package com.thomaswcode.dictationapp.core.dictionary

import kotlinx.serialization.Serializable

/** A personal-dictionary entry sent to AssemblyAI as a keyterm and to the LLM as a spelling to preserve. */
@Serializable
data class DictionaryTerm(
    val term: String,
    val starred: Boolean = false,
    val useCount: Int = 0,
    /** Epoch milliseconds. */
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
) {
    companion object {
        const val MAX_LENGTH = 50
    }
}

/**
 * Pure. AssemblyAI accepts at most 100 keyterms of at most 50 characters. When the dictionary is larger,
 * starred terms win, then most-used, then most recently used, then most recently added.
 */
object KeytermsSelector {
    const val MAX_TERMS = 100

    fun select(terms: Iterable<DictionaryTerm>, maxTerms: Int = MAX_TERMS, maxLength: Int = DictionaryTerm.MAX_LENGTH): List<String> {
        val seen = HashSet<String>()
        return terms
            .filter { it.term.isNotBlank() }
            .map { it to it.term.trim() }
            .filter { it.second.length <= maxLength }
            .filter { seen.add(it.second.lowercase()) }
            .sortedWith(
                compareByDescending<Pair<DictionaryTerm, String>> { it.first.starred }
                    .thenByDescending { it.first.useCount }
                    .thenByDescending { it.first.lastUsedAt ?: Long.MIN_VALUE }
                    .thenByDescending { it.first.addedAt },
            )
            .take(maxTerms)
            .map { it.second }
    }
}

/** A word (or short run of words) the user changed after insertion. */
data class Correction(val from: String, val to: String)

/**
 * Pure. Word-level longest-common-subsequence diff between the text we inserted and the text after the
 * user edited it. Replaced runs become candidate dictionary terms for "Correct last dictation".
 */
object CorrectionDiffer {
    private val whitespace = Regex("""\s+""")
    private val edgePunctuation = Regex("""\p{P}+(?=\s|$)|(?<=^|\s)\p{P}+""")

    fun diff(inserted: String, edited: String): List<Correction> {
        val a = tokenise(inserted)
        val b = tokenise(edited)
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val result = mutableListOf<Correction>()
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        fun flush() {
            if (removed.isNotEmpty() && added.isNotEmpty()) {
                result.add(Correction(removed.joinToString(" "), added.joinToString(" ")))
            }

            removed.clear()
            added.clear()
        }

        var x = 0
        var y = 0
        while (x < a.size && y < b.size) {
            when {
                a[x] == b[y] -> {
                    flush()
                    x++
                    y++
                }
                lcs[x + 1][y] >= lcs[x][y + 1] -> removed.add(a[x++])
                else -> added.add(b[y++])
            }
        }

        while (x < a.size) removed.add(a[x++])
        while (y < b.size) added.add(b[y++])
        flush()
        return result.filter(::isInteresting)
    }

    /** Candidate dictionary terms: the replacement side of each correction, without punctuation noise. */
    fun suggestTerms(corrections: Iterable<Correction>): List<String> {
        val seen = HashSet<String>()
        return corrections
            .map { stripPunctuation(it.to) }
            .filter { it.length in 2..DictionaryTerm.MAX_LENGTH }
            .filter { seen.add(it.lowercase()) }
    }

    private fun isInteresting(c: Correction): Boolean {
        // Ignore pure punctuation/case-only edits; those are not dictionary material.
        val from = stripPunctuation(c.from)
        val to = stripPunctuation(c.to)
        return to.isNotEmpty() && !from.equals(to, ignoreCase = true)
    }

    private fun stripPunctuation(s: String): String = edgePunctuation.replace(s, "").trim()

    private fun tokenise(s: String): List<String> = if (s.isBlank()) emptyList() else s.trim().split(whitespace)
}
