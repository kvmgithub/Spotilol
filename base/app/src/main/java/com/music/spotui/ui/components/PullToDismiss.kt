package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A full-screen sheet pulled down from the top of its list closes, like the official
 * player: the sheet follows the finger (with some resistance) and closes past
 * [thresholdPx] or on a quick flick down; otherwise it springs back. Attach
 * [connection] to the list's `nestedScroll` and move the sheet by [offset].
 */
class PullToDismissState internal constructor(
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val flingPx: Float,
    private val dismiss: () -> Unit,
) {
    var enabled = true
    private val pull = Animatable(0f)
    private var raw = 0f
    private var dismissed = false

    /** How far the sheet is pulled down, in pixels. */
    val offset: Float get() = pull.value

    private fun follow() {
        scope.launch { pull.snapTo(raw * RESISTANCE) }
    }

    val connection = object : NestedScrollConnection {
        // Pushing back up first undoes the pull, before the list scrolls.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (raw <= 0f || available.y >= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            val used = maxOf(available.y, -raw)
            raw += used
            follow()
            return Offset(0f, used)
        }

        // What the list could not scroll (it is at its top) pulls the sheet down.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled || dismissed || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            raw += available.y
            follow()
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (raw <= 0f) return Velocity.Zero
            if (raw > thresholdPx || available.y > flingPx) {
                dismissed = true
                dismiss()
            } else {
                raw = 0f
                pull.animateTo(0f)
            }
            return available
        }
    }

    private companion object {
        const val RESISTANCE = 0.6f
    }
}

@Composable
fun rememberPullToDismiss(onDismiss: () -> Unit): PullToDismissState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onDismiss)
    return remember(density) {
        with(density) { PullToDismissState(scope, 120.dp.toPx(), 1200.dp.toPx()) { latest.value() } }
    }
}
