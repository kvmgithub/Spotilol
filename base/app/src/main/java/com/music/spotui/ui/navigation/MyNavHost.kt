package com.music.spotui.ui.navigation

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import com.music.spotui.ui.theme.AppBackground
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.music.spotui.ui.screens.AlbumByIdScreen
import com.music.spotui.ui.screens.ContextScreen
import com.music.spotui.ui.screens.ArtistReleasesScreen
import com.music.spotui.ui.screens.ArtistScreen
import com.music.spotui.ui.screens.CategoryScreen
import com.music.spotui.ui.screens.HomeScreen
import com.music.spotui.ui.screens.LibraryScreen
import com.music.spotui.ui.screens.PlayerScreen
import com.music.spotui.ui.screens.ShowScreen
import com.music.spotui.ui.screens.QueueScreen
import com.music.spotui.ui.screens.SearchScreen
import com.music.spotui.ui.viewmodel.PlayerViewModel

@Composable
fun MyNavHost(
    navHostController: NavHostController,
    bottomBarState: MutableState<Boolean>,
    bottomBarPlayerState: MutableState<Boolean>
) {

    val playerViewModel : PlayerViewModel = viewModel()
    val playerState by playerViewModel.currentSongTitle

//    val context = LocalContext.current
//    var player : ExoPlayer? = null
//    player = ExoPlayer.Builder(context).build()

    val context = LocalContext.current
    val startDestination = Routes.Home.route

    com.music.spotui.ui.components.ArtistChooserHost(navHostController)
    NavHost(
        navController = navHostController,
        startDestination = startDestination,
        // Pages: a short shift with a fade-through, tabs fade in, the player and queue rise
        // from the bottom (see navEnter). The fades put a page in an offscreen layer for
        // their 0.1-0.2 s: the owner's call, a page change read as a hard block without them.
        enterTransition = { navEnter(initialState, targetState) },
        exitTransition = { navExit(initialState, targetState) },
        popEnterTransition = { navPopEnter(initialState, targetState) },
        popExitTransition = { navPopExit(initialState, targetState) },
    ){
        page(Routes.Home.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
                HomeScreen(navHostController)
        }
        page(Routes.Search.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            SearchScreen(navHostController)
        }
        page(Routes.Library.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            LibraryScreen(navHostController)
        }
        // Older links to the player route open the player sheet over the page instead.
        page(Routes.Player.route){
            LaunchedEffect(Unit) {
                navHostController.popBackStack()
                PlayerSheet.show()
            }
        }

        page(Routes.Queue.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = false
                bottomBarPlayerState.value = false
            }
            QueueScreen(navHostController)
        }

        page(Routes.LocalFiles.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.LocalFilesScreen(navHostController)
        }

        page(Routes.NewReleases.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.NewReleasesScreen(navHostController)
        }

        page(Routes.Episodes.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.EpisodesScreen(navHostController)
        }

        page(Routes.Recap.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.RecapScreen(navHostController)
        }

        page(Routes.Friends.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.FriendActivityScreen(navHostController)
        }

        // Spotify's own list for the account (every device), not this phone's counter.
        page(Routes.Recent.route) {
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.RecentlyPlayedScreen(navHostController)
        }

        page(Routes.Liked.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            ContextScreen(navHostController, type = "liked", id = "")
        }

        // Settings: sound quality, data, display, language, diagnostics.
        page(Routes.Settings.route){
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            com.music.spotui.ui.screens.SettingsScreen(navHostController)
        }






        page(
            "${Routes.Category.route}/{genre}?title={title}",
            arguments = listOf(navArgument("title") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val genre = navBackStackEntry.arguments?.getString("genre").orEmpty()
            val title = navBackStackEntry.arguments?.getString("title").orEmpty()
            CategoryScreen(navHostController, genre = genre, title = title.ifBlank { genre })
        }

        page(
            "${Routes.Album.route}/{uString}?artist={artist}&id={albumId}",
            arguments = listOf(
                navArgument("artist") { defaultValue = "" },
                navArgument("albumId") { defaultValue = "" },
            ),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }

            /* Extracting the id from the route */
            val uId = navBackStackEntry.arguments?.getString("uString")
            val artist = navBackStackEntry.arguments?.getString("artist").orEmpty()
            /* We check if it's not null */
            val albumId = navBackStackEntry.arguments?.getString("albumId").orEmpty()
            uId?.let { name ->
                AlbumByIdScreen(navHostController, name = name, artist = artist, id = albumId)
            }
        }

        page(
            "${Routes.Playlist.route}/{pId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val pId = navBackStackEntry.arguments?.getString("pId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            pId?.let { ContextScreen(navHostController, type = "playlist", id = it) }
        }

        page(
            "${Routes.Profile.route}/{uId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uId = navBackStackEntry.arguments?.getString("uId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uId?.let { com.music.spotui.ui.screens.ProfileScreen(navHostController, userId = it, userName = name) }
        }

        page("$LIKED_BY_ARTIST_ROUTE/{key}") { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            navBackStackEntry.arguments?.getString("key")?.let { ContextScreen(navHostController, type = "liked-artist", id = it) }
        }

        page(
            "${com.music.spotui.ui.screens.FOLLOW_LIST_ROUTE}/{uId}/{mode}",
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uId = navBackStackEntry.arguments?.getString("uId")
            val mode = navBackStackEntry.arguments?.getString("mode")
            uId?.let { com.music.spotui.ui.screens.FollowListScreen(navHostController, userId = it, followers = mode == "followers") }
        }

        page(
            "${Routes.Show.route}/{sId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val sId = navBackStackEntry.arguments?.getString("sId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            sId?.let { ShowScreen(navHostController, showId = it, showName = name) }
        }

        page(
            "${Routes.Episode.route}/{eId}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val eId = navBackStackEntry.arguments?.getString("eId")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            eId?.let { com.music.spotui.ui.screens.EpisodeScreen(navHostController, episodeId = it, fallbackName = name) }
        }

        page(
            "${Routes.Folder.route}/{uri}?name={name}",
            arguments = listOf(navArgument("name") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val uri = navBackStackEntry.arguments?.getString("uri")
            val name = navBackStackEntry.arguments?.getString("name").orEmpty()
            uri?.let { com.music.spotui.ui.screens.FolderScreen(navHostController, it, name) }
        }

        page("${Routes.LocalPlaylist.route}/{id}") { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            navBackStackEntry.arguments?.getString("id")?.let { com.music.spotui.ui.screens.LocalPlaylistScreen(navHostController, it) }
        }

        page(
            "${Routes.ArtistReleases.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            aId?.let { ArtistReleasesScreen(navHostController, it, artistId) }
        }

        page(
            "${Routes.Artist.route}/{aString}?id={artistId}",
            arguments = listOf(navArgument("artistId") { defaultValue = "" }),
        ) { navBackStackEntry ->
            LaunchedEffect(playerState) {
                bottomBarState.value = true
                bottomBarPlayerState.value = playerState != ""
            }

            /* Extracting the id from the route */
            val aId = navBackStackEntry.arguments?.getString("aString")
            val artistId = navBackStackEntry.arguments?.getString("artistId").orEmpty()
            /* We check if it's not null */
            aId?.let { aid->
                ArtistScreen(navHostController, aid, artistId)
            }
        }
    }
}

private val tabRoutes = setOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)
// Full-height sheets over the current page (the official player / queue).
private val sheetRoutes = setOf(Routes.Player.route, Routes.Queue.route)

private fun isTabSwitch(from: NavBackStackEntry, to: NavBackStackEntry) =
    from.destination.route in tabRoutes && to.destination.route in tabRoutes
private fun NavBackStackEntry.isSheet() = destination.route in sheetRoutes

// Material 3 emphasized curves: quick start, long soft landing (and the reverse to leave).
internal val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
internal val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val PAGE_MS = 300
private const val TAB_MS = 150
internal const val SHEET_IN_MS = 340
internal const val SHEET_OUT_MS = 240

// Pages: Material's shared axis with a fade-through. The page leaving fades out over the first
// 90 ms while it moves a step aside; the page coming in moves the same short step and fades in
// over the rest. Two opaque pages sliding across the whole screen (as before) read as a heavy
// block with a hard edge running over the content; a short shift and a fade at the hand-over
// read as the official apps' quick page change. Tabs: the new one fades in, nothing moves.
private const val SHIFT = 0.08f
private const val FADE_OUT_MS = 90
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun pageIn(fromRight: Boolean): EnterTransition =
    slideInHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (fromRight) 1 else -1 } +
        fadeIn(tween(PAGE_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = Decelerate))

private fun pageOut(toLeft: Boolean): ExitTransition =
    slideOutHorizontally(tween(PAGE_MS, easing = Standard)) { (it * SHIFT).toInt() * if (toLeft) -1 else 1 } +
        fadeOut(tween(FADE_OUT_MS, easing = Accelerate))

private fun navEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    to.isSheet() -> slideInVertically(tween(SHEET_IN_MS, easing = Decelerate)) { it }
    else -> pageIn(fromRight = true)
}

private fun navExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    // The page stays still under the rising sheet until it covers it.
    to.isSheet() -> fadeOut(tween(durationMillis = 1, delayMillis = SHEET_IN_MS))
    else -> pageOut(toLeft = true)
}

private fun navPopEnter(from: NavBackStackEntry, to: NavBackStackEntry): EnterTransition = when {
    isTabSwitch(from, to) -> fadeIn(tween(TAB_MS, easing = Decelerate))
    from.isSheet() -> EnterTransition.None
    else -> pageIn(fromRight = false)
}

private fun navPopExit(from: NavBackStackEntry, to: NavBackStackEntry): ExitTransition = when {
    isTabSwitch(from, to) -> ExitTransition.None
    from.isSheet() -> slideOutVertically(tween(SHEET_OUT_MS, easing = Accelerate)) { it }
    else -> pageOut(toLeft = false)
}

/** A destination on an opaque background, so sliding pages never show through each other. */
private fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments = arguments) { entry ->
    val scope = this
    val settled = androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            scope.transition.currentState == scope.transition.targetState
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPageSettled provides settled) {
        Box(Modifier.fillMaxSize().background(AppBackground)) { scope.content(entry) }
    }
}

/**
 * false while this page is sliding in or out. Heavy views (video players, surfaces)
 * wait for it: built in the first frame, they stalled it and the motion jumped.
 */
val LocalPageSettled = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(true)
}
