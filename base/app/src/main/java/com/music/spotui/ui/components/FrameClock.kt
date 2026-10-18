package com.music.spotui.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A draw invalidation clock; reads in draw lambdas avoid screen recomposition. */
@Composable
fun rememberFrameClock(running: Boolean, fps: Int = 60): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    val energy by app.myspoti.power.EnergySettings.state.collectAsStateWithLifecycle()
    val requestedFps = fps.coerceIn(1, 120)
    val effectiveFps = minOf(requestedFps, energy.policy.progressFps.coerceIn(1, 120))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running, effectiveFps, lifecycle) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // One frame per tick, then sleep: between ticks nothing asks for a frame, so
            // the LTPO screen can drop its refresh rate (a withFrameNanos loop kept it at max).
            val periodMs = 1000L / effectiveFps
            while (isActive) {
                clock.longValue = withFrameNanos { it }
                delay((periodMs - 4).coerceAtLeast(0))
            }
        }
    }
    return clock
}
