package com.music.spotui.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Official-app press feedback: the control shrinks a little under the finger and
 * springs back on release, no ripple. Animates only while pressed.
 */
fun Modifier.pressable(
    pressedScale: Float = 0.9f,
    enabled: Boolean = true,
    onPressHint: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hinted = if (onPressHint == null) Modifier else Modifier.pressHint(onPressHint)
    hinted.then(pressScale(source, pressedScale)).clickable(source, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
}

/**
 * Calls [onHint] when a finger rests on the element (80 ms without moving past the
 * touch slop) or taps it: the page behind it starts loading ~100 ms before the
 * click. A touch that turns into a scroll asks for nothing.
 */
fun Modifier.pressHint(onHint: () -> Unit): Modifier = pointerInput(onHint) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var moved = false
        withTimeoutOrNull(80) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) { moved = true; break }
                if (!change.pressed) break
            }
        }
        if (!moved) onHint()
    }
}

/** The shrink of [pressable], for a control whose clickable sits further down the chain. */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.9f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}
