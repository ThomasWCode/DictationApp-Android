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

    private fun join(includeOpenTurns: Boolean): String = synchronized(lock) {
        turns.values
            .filter { it.endOfTurn || includeOpenTurns }
            .map { it.bestText }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}
