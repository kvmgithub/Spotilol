package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTest {
    private fun emittedSlots(fps: Int, displayHz: Int, seconds: Int = 5): List<Int> {
        val cadence = FrameCadence(fps)
        return (0 until displayHz * seconds).filter { frame ->
            cadence.shouldTick(frame.toLong() * 1_000_000_000L / displayHz)
        }
    }

    @Test fun firstFrameIsPublishedImmediately() {
        for (fps in listOf(20, 30)) {
            val cadence = FrameCadence(fps)
            assertTrue(cadence.shouldTick(1_987_654_321L))
            assertFalse(cadence.shouldTick(1_987_654_321L))
        }
    }

    @Test fun normalProgressUsesEverySecondOrFourthDisplayFrame() {
        assertEquals((0 until 300 step 2).toList(), emittedSlots(30, 60))
        assertEquals((0 until 600 step 4).toList(), emittedSlots(30, 120))
    }

    @Test fun saverProgressUsesEveryThirdOrSixthDisplayFrame() {
        assertEquals((0 until 300 step 3).toList(), emittedSlots(20, 60))
        assertEquals((0 until 600 step 6).toList(), emittedSlots(20, 120))
    }

    @Test fun slowDisplayCannotCauseMultipleTicksInOneFrame() {
        assertEquals((0 until 120).toList(), emittedSlots(30, 24))
        val cadence = FrameCadence(30)
        val frame = 41_666_666L
        assertTrue(cadence.shouldTick(frame))
        assertFalse(cadence.shouldTick(frame))
    }

    @Test fun missedFrameRebasesRatherThanCatchingUpOnTheAdjacentFrame() {
        val cadence = FrameCadence(30)
        assertTrue(cadence.shouldTick(0L))
        assertFalse(cadence.shouldTick(16_666_666L))
        // The expected frame at33ms was missed; the next frame is at50ms.
        assertTrue(cadence.shouldTick(50_000_000L))
        assertFalse(cadence.shouldTick(66_666_666L))
        assertTrue(cadence.shouldTick(83_333_333L))
    }

    @Test fun longPausePublishesOnceThenRestartsRegularSlots() {
        for (fps in listOf(20, 30)) {
            val cadence = FrameCadence(fps)
            val resumed = 600_000_000_000L
            assertTrue(cadence.shouldTick(0L))
            assertTrue(cadence.shouldTick(resumed))
            assertFalse(cadence.shouldTick(resumed))
            assertFalse(cadence.shouldTick(resumed + 16_666_666L))
            assertTrue(cadence.shouldTick(resumed + 1_000_000_000L / fps))
        }
    }

    @Test fun subMillisecondTimestampJitterDoesNotDropNominalSlots() {
        val cadence = FrameCadence(30)
        val frames = listOf(0L, 16_500_000L, 33_200_000L, 50_100_000L, 66_400_000L)
        assertEquals(listOf(true, false, true, false, true), frames.map(cadence::shouldTick))
    }

    @Test fun earlyNonDisplayPeriodDoesNotTriggerATick() {
        val cadence = FrameCadence(30)
        assertTrue(cadence.shouldTick(0L))
        assertFalse(cadence.shouldTick(32_000_000L))
        assertTrue(cadence.shouldTick(33_333_332L))
    }

    @Test fun newLifecycleInstanceHasNoWaitingPeriod() {
        val beforeStop = FrameCadence(30)
        assertTrue(beforeStop.shouldTick(1_000_000_000L))
        val afterStart = FrameCadence(30)
        assertTrue(afterStart.shouldTick(1_000_000_001L))
    }

    @Test fun clockRestartRebasesTheCadence() {
        val cadence = FrameCadence(30)
        assertTrue(cadence.shouldTick(1_000_000_000L))
        assertFalse(cadence.shouldTick(1_016_666_666L))
        assertTrue(cadence.shouldTick(0L))
        assertFalse(cadence.shouldTick(16_666_666L))
        assertTrue(cadence.shouldTick(33_333_333L))
    }

    @Test fun unsupportedRatesAreBounded() {
        val minimum = FrameCadence(0)
        assertTrue(minimum.shouldTick(0L))
        assertFalse(minimum.shouldTick(500_000_000L))
        assertTrue(minimum.shouldTick(1_000_000_000L))
        assertEquals((0 until 600).toList(), emittedSlots(Int.MAX_VALUE, 120))
    }
}
