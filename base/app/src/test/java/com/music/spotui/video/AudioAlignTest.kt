package com.music.spotui.video

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Calls production AudioAlign; no codec, Android, network or replicated matcher. */
class AudioAlignTest {
    @Test fun emptyMapIsIdentity() {
        val map = AudioAlign.TimeMap(emptyList(), 0.0)
        for (time in listOf(0L, 1L, 12_345L)) {
            assertEquals(time, map.toVideo(time))
            assertEquals(time, map.toSong(time))
        }
    }

    @Test fun segmentsUseExclusiveEndAndNearestOffsetOutside() {
        val map = AudioAlign.TimeMap(listOf(
            AudioAlign.Segment(1_000, 4_000, 500),
            AudioAlign.Segment(6_000, 9_000, 2_000),
        ), 1.0)
        assertEquals(500L, map.toVideo(0))
        assertEquals(1_500L, map.toVideo(1_000))
        assertEquals(4_499L, map.toVideo(3_999))
        assertEquals(4_500L, map.toVideo(4_000))
        assertEquals(4_501L, map.toVideo(4_001))
        assertEquals(5_500L, map.toVideo(5_000)) // Equal distance chooses the first listed segment.
        assertEquals(7_001L, map.toVideo(5_001))
        assertEquals(8_000L, map.toVideo(6_000))
        assertEquals(11_000L, map.toVideo(9_000))
        assertEquals(12_000L, map.toVideo(10_000))
    }

    @Test fun inverseClampsVideoOnlyIntroGapAndOutro() {
        val map = AudioAlign.TimeMap(listOf(
            AudioAlign.Segment(0, 5_000, 2_000),
            AudioAlign.Segment(5_000, 10_000, 5_000),
        ), 1.0)
        assertEquals(0L, map.toSong(0))
        assertEquals(0L, map.toSong(2_000))
        assertEquals(4_999L, map.toSong(7_500))
        assertEquals(5_000L, map.toSong(9_500))
        assertEquals(5_000L, map.toSong(10_000))
        assertEquals(9_999L, map.toSong(99_999))
        for (time in listOf(0L, 1L, 4_999L, 5_000L, 9_999L)) {
            assertEquals(time, map.toSong(map.toVideo(time)))
        }
    }

    @Test fun infiniteFinalSegmentRoundTripsLargeTimes() {
        val map = AudioAlign.TimeMap(listOf(AudioAlign.Segment(0, Long.MAX_VALUE, 3_000)), 1.0)
        for (time in listOf(0L, 60_000L, 86_400_000L)) {
            assertEquals(time + 3_000, map.toVideo(time))
            assertEquals(time, map.toSong(map.toVideo(time)))
        }
    }

    @Test fun negativeOffsetExposesMissingVideoPrefix() {
        val map = AudioAlign.TimeMap(listOf(AudioAlign.Segment(0, Long.MAX_VALUE, -2_000)), 1.0)
        assertEquals(-2_000L, map.toVideo(0)) // Raw map can be negative; caller must clamp before seek.
        assertEquals(2_000L, map.toSong(0))
    }

    @Test fun pcmShorterThanFftWindowProducesNoFeatures() {
        // 805..1023 used to round (samples - 1024) / 220 towards zero, then read 1024 samples.
        for (samples in listOf(0, 1, 804, 805, 900, 1023)) {
            assertTrue("$samples PCM samples are too short for a complete frame", AudioAlign.features(FloatArray(samples)).isEmpty())
        }
    }

    @Test fun insufficientAlignmentAndSilenceHaveNoCredibleMatch() = runBlocking {
        val short = AudioAlign.align(Array(1) { DoubleArray(32) }, Array(1) { DoubleArray(32) })
        assertTrue(short.segments.isEmpty())
        assertEquals(0.0, short.score, 0.0)
        val silent = AudioAlign.features(FloatArray(SR * 8))
        val map = AudioAlign.align(silent, silent)
        assertTrue(map.score.isFinite())
        assertTrue("Silence must not pass the controller's MIN_SCORE=0.4", map.score < 0.4)
    }

    @Test fun shiftedSyntheticPcmFindsKnownOffset() = runBlocking {
        val song = synthetic(1_050)
        val video = prefix(song, 100)
        val map = AudioAlign.align(AudioAlign.features(song), AudioAlign.features(video))
        assertCredible(map)
        assertOffset(map, 4_000, framesMs(100))
        assertOffset(map, 15_000, framesMs(100))
    }

    @Test fun insertedSkitsCreateMultipleOffsets() = runBlocking {
        val song = synthetic(1_500)
        val introFrames = 100
        val skitFrames = 150
        val split = HOP * 600
        val video = FloatArray(introFrames * HOP + song.size + skitFrames * HOP)
        song.copyInto(video, introFrames * HOP, 0, split)
        song.copyInto(video, (introFrames + skitFrames) * HOP + split, split)
        val map = AudioAlign.align(AudioAlign.features(song), AudioAlign.features(video))
        assertCredible(map)
        assertTrue("An inserted skit must produce multiple mapping segments: ${map.segments}", map.segments.size >= 2)
        assertOffset(map, 3_000, framesMs(introFrames))
        assertOffset(map, 24_000, framesMs(introFrames + skitFrames))
    }

    @Test fun repeatedChorusKeepsForwardContinuousPath() = runBlocking {
        val song = synthetic(1_500)
        // The same six-second chorus appears twice, with unique material on either side.
        song.copyInto(song, HOP * 900, HOP * 150, HOP * 450)
        val map = AudioAlign.align(AudioAlign.features(song), AudioAlign.features(prefix(song, 75)))
        assertCredible(map)
        for (time in listOf(2_000L, 6_000L, 15_000L, 22_000L, 27_000L)) {
            assertOffset(map, time, framesMs(75))
        }
        assertTrue(map.toVideo(22_000) > map.toVideo(6_000))
    }

    @Test fun addedNoiseKeepsOffsetWithinOneHundredMilliseconds() = runBlocking {
        val song = synthetic(1_050)
        val video = prefix(song, 100)
        val rng = Random(981L)
        for (i in video.indices) video[i] += ((rng.nextDouble() - 0.5) * 0.06).toFloat()
        val map = AudioAlign.align(AudioAlign.features(song), AudioAlign.features(video))
        assertCredible(map)
        assertOffset(map, 4_000, framesMs(100))
        assertOffset(map, 15_000, framesMs(100))
    }

    @Test fun prefixOnlyAlignmentCannotDiscoverLaterSkits() = runBlocking {
        val song = synthetic(1_500)
        val intro = 100 * HOP
        val split = 900 * HOP
        val skit = 150 * HOP
        val video = FloatArray(intro + song.size + skit)
        song.copyInto(video, intro, 0, split)
        song.copyInto(video, intro + split + skit, split)
        // Production first-pass decode limits are 45 s / 100 s; shortened here for a fast JVM repro.
        val prefixSamples = 600 * HOP + N
        val map = AudioAlign.align(AudioAlign.features(song.copyOf(prefixSamples)),
            AudioAlign.features(video.copyOf(intro + prefixSamples)))
        assertCredible(map)
        assertEquals(Long.MAX_VALUE, map.segments.last().songEndMs)
        val expectedFullOffset = framesMs(250)
        assertFalse("A prefix cannot know about a later inserted skit", abs(map.toVideo(26_000) - 26_000 - expectedFullOffset) <= 100)
    }

    private fun synthetic(frames: Int): FloatArray {
        val random = Random(77419L)
        val pitches = IntArray((frames * HOP + N) / 1_100 + 1) { 150 + random.nextInt(3_500) }
        return FloatArray(frames * HOP + N) { i ->
            val frequency = pitches[i / 1_100]
            val phase = 2 * PI * frequency * i / SR
            val envelope = 0.3 + 0.7 * ((i % 1_100) / 1_100.0)
            (envelope * (0.3 * sin(phase) + 0.15 * sin(phase * 1.5)) + 0.08 * (random.nextDouble() - 0.5)).toFloat()
        }
    }

    private fun prefix(song: FloatArray, frames: Int) = FloatArray(frames * HOP + song.size).also {
        song.copyInto(it, frames * HOP)
    }
    private fun assertCredible(map: AudioAlign.TimeMap) {
        assertTrue("Expected a credible match, score=${map.score}, segments=${map.segments}", map.score >= 0.4)
        assertTrue(map.segments.isNotEmpty())
    }
    private fun assertOffset(map: AudioAlign.TimeMap, songMs: Long, expected: Long) {
        val actual = map.toVideo(songMs) - songMs
        assertTrue("At song=$songMs expected offset=$expected ±100 ms, got $actual; ${map.segments}", abs(actual - expected) <= 100)
    }
    private fun framesMs(frames: Int) = (frames * HOP.toDouble() / SR * 1_000).toLong()
    private companion object { const val SR = 11_025; const val HOP = 220; const val N = 1_024 }
}
