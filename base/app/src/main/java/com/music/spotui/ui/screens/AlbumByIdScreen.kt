package com.music.spotui.ui.screens

import app.myspoti.engine.R

import androidx.compose.ui.res.stringResource

import com.music.spotui.ui.components.SkeletonKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.navigation.NavController
import com.music.spotui.di.Graph
import com.music.spotui.ui.components.Loader

/** Opens the album page by Spotify id, resolving it from the name only when needed. */
@Composable
fun AlbumByIdScreen(navController: NavController, name: String, artist: String, id: String) {
    var resolved by remember(name, artist, id) { mutableStateOf(id.ifBlank { null }) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(name, artist, id) {
        if (resolved == null) {
            resolved = Graph.api.resolveAlbumId(name, artist)
            if (resolved == null) failed = true
        }
    }
    val albumId = resolved
    when {
        albumId != null -> ContextScreen(navController, type = "album", id = albumId)
        failed -> Text(stringResource(R.string.album_not_found), color = Color.White)
        else -> Loader(SkeletonKind.Header)
    }
}
