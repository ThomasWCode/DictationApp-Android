package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.audio.LevelMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** The bubble's level bars: scaled to the speaking volume, still in a quiet room, and hard to throw off. */
class LevelMeterTest {
    @Test
    fun theSameSpeechMovesTheBarsAlikeAtAnyMicrophoneVolume() {
        // The same voice and room as a quiet phone microphone hears them, and 28 dB louder. The old fixed square-root
        // scale showed the quiet syllables at about a seventh of the bars' height.
        val quiet = room(-58f, 10) + speak(-42f, -58f, 4)
        val loud = room(-30f, 10) + speak(-14f, -30f, 4)
        val quietShown = LevelMeter().feed(quiet)
        val loudShown = LevelMeter().feed(loud)

        assertTrue(quietShown.zip(loudShown).all { (a, b) -> abs(a - b) < 0.001f })
        val lastTwoSentences = quiet.size - 2 * SENTENCE_FRAMES
        val syllables = median(syllables(quiet.drop(lastTwoSentences), quietShown.drop(lastTwoSentences), -42f))
        assertTrue("syllables fill most of the bars: $syllables", syllables >= 0.6f)
        assertTrue(quiet.zip(quietShown).filter { it.first <= -55f }.all { it.second == 0f })
    }

    @Test
    fun aQuietRoomLeavesTheBarsStill() {
        val meter = LevelMeter()
        meter.feed(room(-55f, 10) + speak(-30f, -55f, 5))
        assertTrue(meter.feed(room(-55f, 600)).all { it == 0f })
    }

    @Test
    fun theFirstSecondOfSpeechSetsTheScale() {
        val heard = room(-58f, 5) + sentence(-42f, -58f)
        val shown = LevelMeter().feed(heard)
        val syllables = median(syllables(heard, shown, -42f))
        assertTrue("the first sentence already fills the bars: $syllables", syllables >= 0.6f)
    }

    @Test
    fun aTapBeforeTheFirstWordsDoesNotSetTheScale() {
        // Touching the bubble can reach the microphone as a loud click, the first thing the meter hears above the room.
        val heard = room(-58f, 5) + listOf(0f) + speak(-42f, -58f, 2)
        val shown = LevelMeter().feed(heard)
        val syllables = median(syllables(heard.drop(6), shown.drop(6), -42f))
        assertTrue("the first sentences still move the bars: $syllables", syllables >= 0.4f)
    }

    @Test
    fun aLongSentenceWithoutAPauseKeepsTheBarsMoving() {
        // Three seconds of speech without a quiet frame is all the noise reading sees, and it takes the voice for noise.
        val meter = LevelMeter()
        meter.feed(room(-58f, 10) + speak(-42f, -58f, 2))
        val unbroken = List(6) { listOf(3f, 0f, -4f, 5f, -2f, 1f, -3f, 4f, 2f, -1f).map { -42f + it } }.flatten()
        val lowest = meter.feed(unbroken).min()
        assertTrue("six seconds without a pause still show: $lowest", lowest >= 0.3f)
    }

    @Test
    fun aTapOrCoughHardlyChangesTheScale() {
        val tapped = LevelMeter()
        val untouched = LevelMeter()
        val before = room(-58f, 10) + speak(-42f, -58f, 3)
        tapped.feed(before)
        untouched.feed(before)
        tapped.add(1f)
        val next = sentence(-42f, -58f)
        val difference = tapped.feed(next).zip(untouched.feed(next)).maxOf { (a, b) -> abs(a - b) }
        assertTrue("a full-scale tap moved the next sentence's bars by $difference", difference <= 0.08f)
    }

    @Test
    fun aStrayQuietFrameDoesNotLetTheRoomMoveTheBars() {
        val meter = LevelMeter()
        meter.feed(room(-52f, 10) + speak(-30f, -52f, 3))
        meter.feed(listOf(-66f))
        assertTrue(meter.feed(room(-52f, 30)).all { it == 0f })
    }

    @Test
    fun digitalSilenceIsNeitherShownNorLearned() {
        val meter = LevelMeter()
        val twin = LevelMeter()
        val before = room(-55f, 10) + speak(-30f, -55f, 2)
        meter.feed(before)
        twin.feed(before)
        // A microphone starting up, or a Bluetooth route switching, delivers frames of (almost) nothing.
        val silent = List(5) { meter.add(0f) } + List(5) { meter.add(1e-5f) }
        assertTrue(silent.all { it == 0f })
        val next = speak(-30f, -55f, 2)
        assertTrue(meter.feed(next).zip(twin.feed(next)).all { (a, b) -> abs(a - b) < 0.001f })
    }

    @Test
    fun aNoisierRoomIsPickedUpWithinThreeSeconds() {
        val meter = LevelMeter()
        meter.feed(room(-55f, 10) + speak(-30f, -55f, 4))
        // One second of a room 20 dB noisier, then two sentences spoken up over it: the second one's pause shows nothing.
        val noisy = room(-35f, 10) + speak(-15f, -35f, 2)
        val shown = meter.feed(noisy)
        assertTrue(shown.takeLast(ROOM_FRAMES).all { it == 0f })
        val secondSentence = noisy.size - SENTENCE_FRAMES
        val syllables = median(syllables(noisy.drop(secondSentence), shown.drop(secondSentence), -15f))
        assertTrue("speech still fills the bars: $syllables", syllables >= 0.6f)
    }

    @Test
    fun everyHeightIsBetweenEmptyAndFull() {
        val meter = LevelMeter()
        val shown = meter.feed(room(-50f, 10) + speak(-20f, -50f, 2) + listOf(0f, -1f, -79f, -81f, -40f))
        assertTrue(shown.all { it in 0f..1f })
    }

    @Test
    fun theBarsStayDownUntilSpeechIsHeard() {
        // A noisy room before the first word: there is no speaking level yet to measure it against.
        val meter = LevelMeter()
        assertTrue(meter.feed(room(-30f, 50)).all { it == 0f })
        assertEquals(0f, LevelMeter().add(0.5f), 0f)
    }

    private fun peak(db: Float) = 10f.pow(db / 20f)

    private fun LevelMeter.feed(dbs: List<Float>) = dbs.map { add(peak(it)) }

    /** Room noise, with the few dB of flutter a phone's microphone shows in 100 ms peaks. */
    private fun room(db: Float, frames: Int) = List(frames) { db + FLUTTER[it % FLUTTER.size] }

    /** Two seconds of speech: syllables around [speechDb], dips between words, then a short pause in a [roomDb] room. */
    private fun sentence(speechDb: Float, roomDb: Float) = SYLLABLES.map { speechDb + it } + room(roomDb, ROOM_FRAMES)

    private fun speak(speechDb: Float, roomDb: Float, sentences: Int) = List(sentences) { sentence(speechDb, roomDb) }.flatten()

    /** The heights shown for the syllables near or above the speaking level (not the dips between words or the pauses). */
    private fun syllables(heard: List<Float>, shown: List<Float>, speechDb: Float) =
        heard.zip(shown).filter { it.first >= speechDb - 4f }.map { it.second }

    private fun median(values: List<Float>) = values.sorted()[values.size / 2]

    private companion object {
        val SYLLABLES = listOf(3f, 0f, -4f, 5f, -2f, -14f, 1f, -3f, 4f, -12f, 2f, -1f, -5f, 3f, -15f)
        val FLUTTER = listOf(0f, 2f, 1f, 3f, 0f, 1f, 2f)
        const val ROOM_FRAMES = 5
        val SENTENCE_FRAMES = SYLLABLES.size + ROOM_FRAMES
    }
}
