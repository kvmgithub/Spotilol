package com.music.spotui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import com.music.spotui.ui.components.pressable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.music.spotui.ui.navigation.MainBottomNavigation
import com.music.spotui.ui.navigation.MyNavHost
import com.music.spotui.ui.navigation.Routes


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App() {
    val bottomBarState = rememberSaveable { (mutableStateOf(true)) }
    val bottomBarPlayerState = rememberSaveable { (mutableStateOf(true)) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()

    // Control TopBar and BottomBar: on a route change (writing state during composition
    // forces an extra recomposition pass).
    val route = navBackStackEntry?.destination?.route
    LaunchedEffect(route) {
        when (route) {
            Routes.Home.route, Routes.Search.route, Routes.Library.route -> bottomBarState.value = true
            Routes.Album.route -> bottomBarState.value = false
        }
    }

    // No update or donation prompts in MySpotilol.
    // The playlists at the top of the library, fetched ahead on Wi-Fi (see PagePrefetch).
    LaunchedEffect(Unit) { com.music.spotui.data.api.PagePrefetch.start() }

    // A collaborative-playlist invitation was opened (see InviteLinks): its playlist page offers "Join".
    val invite by com.music.spotui.data.api.InviteLinks.pending.collectAsState()
    LaunchedEffect(invite) {
        val i = invite?.takeIf { !it.opened } ?: return@LaunchedEffect
        app.myspoti.host.android.MyspotiRuntime.api.ready.first { it }
        i.opened = true
        navController.navigate(com.music.spotui.ui.navigation.playlistRoute(i.playlistId))
    }

    // A Blend invitation was opened (see InviteLinks): who invites, and "Join", over the app.
    val blendLink by com.music.spotui.data.api.InviteLinks.blend.collectAsState()
    blendLink?.let { link ->
        com.music.spotui.ui.screens.BlendInviteSheet(link, navController) {
            if (com.music.spotui.data.api.InviteLinks.blend.value === link) com.music.spotui.data.api.InviteLinks.blend.value = null
        }
    }

    // A shared link to a list / artist / podcast: its page, over whatever was open.
    val pageLink by com.music.spotui.data.api.InviteLinks.page.collectAsState()
    LaunchedEffect(pageLink) {
        val uri = pageLink ?: return@LaunchedEffect
        com.music.spotui.data.api.InviteLinks.page.value = null
        val id = uri.substringAfterLast(':')
        com.music.spotui.ui.navigation.PlayerSheet.close()
        runCatching {
            navController.navigate(when (uri.split(':')[1]) {
                "playlist" -> com.music.spotui.ui.navigation.playlistRoute(id)
                "album" -> com.music.spotui.ui.navigation.albumRoute("", "", id)
                "artist" -> com.music.spotui.ui.navigation.artistRoute("artist", id)
                else -> com.music.spotui.ui.navigation.showRoute(id)
            })
        }
    }
    val shareTarget by com.music.spotui.ui.components.SpotifyCode.request.collectAsState()
    shareTarget?.let { t -> com.music.spotui.ui.components.SpotifyCodeSheet(t) { com.music.spotui.ui.components.SpotifyCode.request.value = null } }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier,
            bottomBar = {
                MainBottomNavigation(navController = navController, bottomBarState = bottomBarState, bottomBarPlayerState)
            }
        ) {
            MyNavHost(navHostController = navController, bottomBarState = bottomBarState, bottomBarPlayerState)
        }
        // Over the pages and the bottom bar: pulling it down shows them, already in place.
        com.music.spotui.ui.navigation.PlayerOverlay(navController)
    }
}
