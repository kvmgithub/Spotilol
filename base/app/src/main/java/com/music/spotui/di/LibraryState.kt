package com.music.spotui.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Saved state of a library item (track in Liked Songs, saved album, followed artist)
 * as Spotify reports it. Every screen reads the same [SongPlayer.library], so a like
 * made anywhere (here or on another device) shows everywhere.
 */
@Composable
fun rememberSaved(uri: String, fallback: Boolean = false): Boolean {
    LaunchedEffect(uri) { SongPlayer.requestSaved(uri) }
    return SongPlayer.library[uri] ?: fallback
}

@Composable
fun rememberTrackSaved(trackId: String): Boolean =
    if (trackId.isBlank()) false else rememberSaved("spotify:track:$trackId")
