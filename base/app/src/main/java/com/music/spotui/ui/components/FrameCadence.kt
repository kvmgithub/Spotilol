package com.music.spotui.ui.components

/** Admits regular display frames without accumulating work after a late frame. */
internal class FrameCadence(fps: Int) {
    private val periodNanos = 1_000_000_000L / fps.coerceIn(1, 120)
    // A nominal VSync period can be rounded or jitter by a fraction of a millisecond.
    // Avoid missing an entire display frame because of this small timestamp error.
    private val earlyToleranceNanos = minOf(500_000L, periodNanos / 16)
    private var hasTicked = false
    private var lastTickNanos = 0L

    fun shouldTick(frameTimeNanos: Long): Boolean {
        if (hasTicked && frameTimeNanos >= lastTickNanos &&
            frameTimeNanos - lastTickNanos < periodNanos - earlyToleranceNanos
        ) return false
        // First frame, clock restart, or a late frame: publish once and rebase.
        // A pause never earns extra ticks on the next adjacent display frames.
        hasTicked = true
        lastTickNanos = frameTimeNanos
        return true
    }
}
