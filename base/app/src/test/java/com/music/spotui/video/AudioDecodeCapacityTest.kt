package com.music.spotui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecodeCapacityTest {
    @Test fun prefixAllocationUsesLimitInsteadOfFullContainerDuration() {
        assertEquals(46 * 11_025, AudioAlign.decodeCapacity(240_000_000, 45))
        assertEquals(101 * 11_025, AudioAlign.decodeCapacity(240_000_000, 100))
    }
    @Test fun unknownDurationStillRespectsPrefixLimit() {
        assertEquals(46 * 11_025, AudioAlign.decodeCapacity(0, 45))
        assertEquals(46 * 11_025, AudioAlign.decodeCapacity(-1, 45))
    }
    @Test fun shortContainerKeepsItsSmallerCapacity() {
        assertEquals(11 * 11_025, AudioAlign.decodeCapacity(10_000_000, 45))
    }
    @Test fun fullDecodeAndUnknownDurationPreserveExistingSizing() {
        assertEquals(241 * 11_025, AudioAlign.decodeCapacity(240_000_000, 0))
        assertEquals(1 shl 22, AudioAlign.decodeCapacity(0, 0))
    }
    @Test fun extremeDurationAndLimitCannotOverflowNegativeCapacity() {
        assertTrue(AudioAlign.decodeCapacity(Long.MAX_VALUE, 0) > 0)
        assertTrue(AudioAlign.decodeCapacity(Long.MAX_VALUE, Int.MAX_VALUE) > 0)
    }
}
