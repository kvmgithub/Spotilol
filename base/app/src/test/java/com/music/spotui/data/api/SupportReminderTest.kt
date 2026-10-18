package com.music.spotui.data.api

import com.music.spotui.data.api.SupportReminder.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportReminderTest {
    private val day = 24 * 3_600_000L

    @Test fun firstReminderADayAfterFirstUse() {
        assertEquals(1_000L + day, SupportReminder.nextAt(1_000L, lastShown = 0L, level = Level.NONE, supportedAt = 0L))
    }

    @Test fun perksStayOffUntilSupportsCanBeChecked() {
        // Everyone gets the non-supporter cadence and the default green while they are off.
        org.junit.Assert.assertFalse(SupportReminder.PERKS_ENABLED)
        assertEquals(Level.NONE, SupportReminder.level)
    }

    @Test fun thenEveryTwoWeeksAfterTheLastAnswer() {
        assertEquals(64 * day, SupportReminder.nextAt(1_000L, lastShown = 50 * day, level = Level.NONE, supportedAt = 0L))
    }

    @Test fun aOneTimeSupporterHearsAgainAfterThreeMonths() {
        // Counted from the later of the last reminder and the support itself.
        assertEquals(100 * day, SupportReminder.nextAt(1_000L, lastShown = 5 * day, level = Level.ONCE, supportedAt = 10 * day))
        assertEquals(110 * day, SupportReminder.nextAt(1_000L, lastShown = 20 * day, level = Level.ONCE, supportedAt = 10 * day))
    }

    @Test fun aMonthlySupporterIsNeverAsked() {
        assertEquals(Long.MAX_VALUE, SupportReminder.nextAt(1_000L, lastShown = 0L, level = Level.MONTHLY, supportedAt = 1L))
    }
}
