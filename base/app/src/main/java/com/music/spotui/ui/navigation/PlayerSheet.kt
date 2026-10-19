package com.music.spotui.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import com.music.spotui.ui.screens.PlayerScreen
import kotlinx.coroutines.flow.drop

/**
 * The full player, shown over the pages instead of being one of them, like the official
 * app: pulled down, it uncovers the page it was opened from, with the mini player and the
 * tabs already in place, and closing it leaves them there.
 */
object PlayerSheet {
    val open = mutableStateOf(false)

    fun show() { open.value = true }
    fun close() { open.value = false }
}

@Composable
fun PlayerOverlay(navController: NavController) {
    // Any navigation from the player (album, artist, playlist…) lands on that page.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.drop(1).collect { PlayerSheet.close() }
    }
    AnimatedVisibility(
        visible = PlayerSheet.open.value,
        enter = slideInVertically(tween(SHEET_IN_MS, easing = Decelerate)) { it },
        exit = slideOutVertically(tween(SHEET_OUT_MS, easing = Accelerate)) { it },
    ) {
        BackHandler { PlayerSheet.close() }
        val settled = remember { derivedStateOf { transition.currentState == transition.targetState } }
        CompositionLocalProvider(LocalPageSettled provides settled) {
            // A podcast episode gets the podcast player (±15 s, speed, sleep timer, no shuffle);
            // everything else keeps the music player.
            if (com.music.spotui.di.SongPlayer.isEpisode) {
                com.music.spotui.ui.screens.PodcastPlayerScreen(navController)
            } else {
                PlayerScreen(navController)
            }
        }
    }
}
