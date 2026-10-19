package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue drag auto-scroll: direction and ramp of the per-frame scroll step. */
class QueueEdgeScrollTest {
    private val top = 200f
    private val bottom = 1400f // 1200 px viewport, as on a phone in dp-ish units
    private val zone = 72f
    private val maxStep = 14f

    private fun step(pointerY: Float) = queueEdgeScrollStep(pointerY, top, bottom, zone, maxStep)

    @Test fun middleOfTheListDoesNotScroll() {
        assertEquals(0f, step((top + bottom) / 2), 0f)
        assertEquals(0f, step(top + zone), 0f) // inner border of the top zone
        assertEquals(0f, step(bottom - zone), 0f) // inner border of the bottom zone
    }

    @Test fun nearTheTopScrollsUpAndNearTheBottomScrollsDown() {
        assertTrue(step(top + 10f) < 0f)
        assertTrue(step(bottom - 10f) > 0f)
    }

    @Test fun speedGrowsTowardsTheEdge() {
        val shallow = step(top + zone / 2)
        val deep = step(top + 1f)
        assertTrue("deeper in the top zone must scroll faster", deep < shallow)
        assertEquals(-maxStep, step(top), 0f)
        assertEquals(maxStep, step(bottom), 0f)
    }

    @Test fun speedIsCappedOutsideTheViewport() {
        // A row dragged past the ends (already scrolled to the boundary) must not accelerate.
        assertEquals(-maxStep, step(top - 500f), 0f)
        assertEquals(maxStep, step(bottom + 500f), 0f)
    }

    @Test fun zonesAreSymmetric() {
        for (d in listOf(1f, 20f, 50f, 71f)) {
            assertEquals(-step(top + d), step(bottom - d), 0.0001f)
        }
    }

    @Test fun aDegenerateViewportNeverScrolls() {
        assertEquals(0f, queueEdgeScrollStep(top, top, top, zone, maxStep), 0f)
        assertEquals(0f, queueEdgeScrollStep(top, bottom, top, zone, maxStep), 0f)
        assertEquals(0f, queueEdgeScrollStep(top, top, bottom, 0f, maxStep), 0f)
    }

    @Test fun queueBlockOffsetFollowsTheListScroll() {
        val row = 64f
        // At rest: the queue block starts one row below the current track.
        assertEquals(row, queueTopInViewport(2, 0, 0, row), 0f)
        // Current track half scrolled off.
        assertEquals(32f, queueTopInViewport(2, 0, 32, row), 0f)
        // Current track gone: the queue block starts at the viewport top.
        assertEquals(0f, queueTopInViewport(2, 1, 0, row), 0f)
        // Queue block scrolled up by 500 px.
        assertEquals(-500f, queueTopInViewport(2, 1, 500, row), 0f)
        // No current track: the queue block is the list's only item.
        assertEquals(-120f, queueTopInViewport(1, 0, 120, 0f), 0f)
        assertEquals(0f, queueTopInViewport(0, 0, 0, 0f), 0f)
    }

    @Test fun theDraggedRowKeepsItsPlaceOnScreenWhileTheListScrolls() {
        // One auto-scroll step: the list content moves by `scrolled` and the drag offset
        // is compensated by the same amount (QueueList.startAutoScroll), so the row stays
        // under the finger and the drop target is the row the user actually sees.
        val row = 64f
        val itemTopInQueue = 3 * row
        var dragOffset = 0f
        var firstOffset = 300
        val before = queueTopInViewport(2, 1, firstOffset, row) + itemTopInQueue + dragOffset + row / 2
        val scrolled = 14
        firstOffset += scrolled
        dragOffset += scrolled
        val after = queueTopInViewport(2, 1, firstOffset, row) + itemTopInQueue + dragOffset + row / 2
        assertEquals(before, after, 0.001f)
    }
}
