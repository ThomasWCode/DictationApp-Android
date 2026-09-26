package com.thomaswcode.dictationapp.core.transcription

import java.util.TreeMap

/**
 * Pure. Collects Turn messages keyed by turn_order. Turns are immutable once end_of_turn is true, but the
 * server may send an unformatted final turn followed by a formatted one; the formatted one wins.
 * Out-of-order and duplicate deliveries are tolerated.
 */
class TranscriptAssembler {
    private val turns = TreeMap<Int, TurnMessage>()
    private val lock = Any()

    val turnCount: Int get() = synchronized(lock) { turns.size }

    /** True when the most recent turn has not yet reached end_of_turn. */
    val hasOpenTurn: Boolean get() = synchronized(lock) { turns.isNotEmpty() && !turns.lastEntry()!!.value.endOfTurn }

    val lastTurnOrder: Int? get() = synchronized(lock) { if (turns.isEmpty()) null else turns.lastKey() }

    /** Finals plus the current partial, for live display. */
    val liveText: String get() = join(includeOpenTurns = true)

    /** Joined text of end_of_turn turns, plus a trailing partial if the server never closed it. */
    val finalText: String get() = join(includeOpenTurns = true)

    /** Only turns the server explicitly closed. */
    val closedText: String get() = join(includeOpenTurns = false)

    /**
     * [finalText] prepared by [joinAtPauses], for the cleanup LLM only. Every turn ends at a pause of at least
     * min_turn_silence and is punctuated as a whole sentence, so a full stop at a boundary may only be the speaker
     * stopping to think.
     */
    val pauseMarkedText: String get() = joinAtPauses(parts(includeOpenTurns = true))

    /** Returns true when the turn changed the assembled text. */
    fun ingest(turn: TurnMessage): Boolean = synchronized(lock) {
        val existing = turns[turn.turnOrder]
        if (existing != null) {
            // Never let an unformatted or partial turn overwrite a formatted, closed one.
            if (existing.endOfTurn && !turn.endOfTurn) return false
            if (existing.endOfTurn && existing.turnIsFormatted && !turn.turnIsFormatted) return false
            if (turn.bestText.isEmpty() && existing.bestText.isNotEmpty() && !turn.endOfTurn) return false
        }

        turns[turn.turnOrder] = turn
        true
    }

    fun clear() = synchronized(lock) { turns.clear() }

    private fun join(includeOpenTurns: Boolean): String = parts(includeOpenTurns).joinToString(" ")

    private fun parts(includeOpenTurns: Boolean): List<String> = synchronized(lock) {
        turns.values
            .filter { it.endOfTurn || includeOpenTurns }
            .map { it.bestText }
            .filter { it.isNotEmpty() }
    }

    companion object {
        /** Written between turns in [pauseMarkedText]. */
        const val PAUSE_MARKER = "[pause]"

        private val markerRegex = Regex("[ \\t]*\\[pause\\][ \\t]*", RegexOption.IGNORE_CASE)
        private val spacesRegex = Regex(" {2,}")
        private val strayRegex = Regex(" (?=[.,;:!?\\n])|(?<=\\n) ")

        /**
         * Joins turn texts with [PAUSE_MARKER] and removes the transcriber's guess at each pause: the full stop ending a
         * turn is dropped and the next turn's first word lower-cased (not "I", acronyms or mixed-case names such as
         * "WhatsApp"), so the LLM decides afresh whether a sentence ends there. Kept, the full stop anchored the model
         * and "chat box. [pause] Still appends" stayed split (measured on the Windows app with gpt-oss-120b). Question
         * and exclamation marks stay.
         */
        fun joinAtPauses(turns: List<String>): String {
            val parts = turns.map { it.trim() }.filter { it.isNotEmpty() }
            return buildString {
                parts.forEachIndexed { i, part ->
                    var text = part
                    if (i > 0) {
                        append(' ').append(PAUSE_MARKER).append(' ')
                        text = lowerFirstWord(text)
                    }

                    if (i < parts.size - 1 && endsWithFullStop(text)) text = text.dropLast(1)
                    append(text)
                }
            }
        }

        /** Removes any [PAUSE_MARKER] a model left in its output. */
        fun removePauseMarkers(text: String): String {
            if (!text.contains(PAUSE_MARKER, ignoreCase = true)) return text
            return text.replace(markerRegex, " ").replace(spacesRegex, " ").replace(strayRegex, "").trim()
        }

        /** Sentence-ending full stops, including CJK, Devanagari and Urdu ones; an ASCII ellipsis is kept. */
        private val fullStops = charArrayOf('.', '\u3002', '\uFF61', '\u0964', '\u06D4')

        private fun endsWithFullStop(text: String): Boolean =
            text.isNotEmpty() && text.last() in fullStops && !text.endsWith("..")

        /**
         * Lower-cases the first word only when it is a plain capitalised word ("Still"): the whole word up to the next
         * space is judged, so "I", "U.S.", "R&D", "C#", "WhatsApp" and "NASA" keep their case.
         */
        private fun lowerFirstWord(text: String): String {
            val word = text.substringBefore(' ').trimEnd(',', ';', ':', '.', '!', '?')
            if (word.isEmpty() || !word[0].isUpperCase() || word == "I" || word.startsWith("I'") ||
                !word.all { it.isLetter() || it == '\'' } || word.drop(1).any { it.isUpperCase() }
            ) return text
            return text[0].lowercaseChar() + text.substring(1)
        }
    }
}
