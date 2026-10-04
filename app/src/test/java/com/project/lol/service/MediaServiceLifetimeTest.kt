package com.project.lol.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServiceLifetimeTest {
    @Test
    fun removedServiceRejectsFurtherStarts() {
        val lifetime = MediaServiceLifetime()

        lifetime.markTaskRemoved()

        assertFalse(lifetime.acceptStart())
    }

    @Test
    fun newlyCreatedServiceDoesNotInheritPreviousRemoval() {
        val removedLifetime = MediaServiceLifetime().apply { markTaskRemoved() }

        val reopenedLifetime = MediaServiceLifetime()

        assertFalse(removedLifetime.acceptStart())
        assertTrue(reopenedLifetime.acceptStart())
    }
}
