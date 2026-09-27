package com.thomaswcode.dictationapp.core.audio

import kotlin.math.log10

/**
 * Turns each frame's peak into the height of the bubble's level bars (0..1), scaled to how loudly the user speaks: a
 * quiet voice on the phone's own microphone moves the bars as much as a loud one, and a quiet room leaves them still.
 * A fixed scale could not do both: the same speech peaks 30 dB apart from one microphone, room or distance to the next.
 * Levels are compared in decibels:
 *
 * - The background noise is the third-quietest of the last [NOISE_FRAMES] frames (three seconds): a pause shows it, a
 *   stray quiet frame does not, and a noisier room is picked up within three seconds.
 * - A frame [SPEECH_ABOVE_NOISE_DB] above the noise is speech, and moves the average speaking level. The first second
 *   of speech sets it outright; after that each frame moves it a little, and a tap or a cough hardly more than a
 *   syllable does.
 * - The bars are full [HEADROOM_DB] above the average speaking level, and empty just above the noise but always 12 to
 *   25 dB below that level: ordinary speech fills most of them, a quiet room leaves them still, and a long sentence
 *   without a pause (which the noise reading catches up with) keeps them moving.
 *
 * Until it has heard speech the meter has nothing to measure against, and keeps the bars down. The orchestrator keeps
 * one meter for the microphone, so each dictation starts where the last left off.
 */
class LevelMeter {
    private val recent = FloatArray(NOISE_FRAMES)
    private val sorted = FloatArray(NOISE_FRAMES)
    private var count = 0
    private var next = 0
    private var speech = 0f
    private var speechFrames = 0

    /** Takes the peak (0..1) of the next 100 ms frame and returns the height of the bars for it (0..1). */
    @Synchronized
    fun add(peak: Float): Float {
        val level = if (peak > 0f) 20f * log10(peak) else Float.NEGATIVE_INFINITY
        // Digital silence (a microphone starting up, a route switching) is not the room: nothing to show or learn.
        if (level <= SILENCE_DB) return 0f

        recent[next] = level
        next = (next + 1) % NOISE_FRAMES
        if (count < NOISE_FRAMES) count++
        recent.copyInto(sorted, 0, 0, count)
        sorted.sort(0, count)
        val noise = sorted[minOf(NOISE_RANK, count - 1)]

        if (level > noise + SPEECH_ABOVE_NOISE_DB) {
            if (speechFrames < WARMUP_FRAMES) {
                speechFrames++
                speech += (level - speech) / speechFrames
            } else {
                speech += (level - speech).coerceIn(-OUTLIER_DB, OUTLIER_DB) * SPEECH_RATE
            }
        }

        if (speechFrames == 0) return 0f
        val top = speech + HEADROOM_DB
        val bottom = (noise + NOISE_MARGIN_DB).coerceIn(speech - EMPTY_MAX_BELOW_SPEECH_DB, speech - EMPTY_MIN_BELOW_SPEECH_DB)
        return ((level - bottom) / (top - bottom)).coerceIn(0f, 1f)
    }

    companion object {
        /** A peak of about three steps of the 16-bit scale or less: no real microphone in a real room is this quiet. */
        const val SILENCE_DB = -80f

        /** Frames the noise is read from: three seconds. */
        const val NOISE_FRAMES = 30

        /** The noise is the (this + 1)-th quietest frame, so one or two stray quiet frames cannot pull it down. */
        const val NOISE_RANK = 2

        const val SPEECH_ABOVE_NOISE_DB = 10f

        /** Speech frames averaged plainly before the moving average takes over: the first second of speech. */
        const val WARMUP_FRAMES = 10

        /** How far each speech frame moves the average towards itself afterwards, weighting the last second or two. */
        const val SPEECH_RATE = 0.08f

        /** A speech frame moves the average as if it were at most this far from it (a tap, a cough, a pop). */
        const val OUTLIER_DB = 10f

        /** Full bars this far above the average speaking level. */
        const val HEADROOM_DB = 5f

        /** Empty bars this far above the noise, so a quiet room leaves them still... */
        const val NOISE_MARGIN_DB = 6f

        /** ...but at least this far below the average speaking level, so speech the noise reading has caught up with shows. */
        const val EMPTY_MIN_BELOW_SPEECH_DB = 12f

        /** ...and at most this far below it, so the quieter syllables of a voice in a silent room do not fill the bars. */
        const val EMPTY_MAX_BELOW_SPEECH_DB = 25f
    }
}
