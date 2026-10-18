package com.music.spotui.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The room a scrolling page leaves at its end, so its last rows can scroll above the mini
 * player, the tabs and the system navigation bar (three buttons or gesture line) instead of
 * staying under them.
 */
@Composable
fun bottomBarsSpace(): Dp = 160.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
