package com.music.spotui.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.music.spotui.ui.components.MiniPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import androidx.compose.foundation.layout.navigationBarsPadding

class NoRippleInteractionSource : MutableInteractionSource {

    override val interactions: Flow<Interaction> = emptyFlow()

    override suspend fun emit(interaction: Interaction) {}

    override fun tryEmit(interaction: Interaction) = true
}

@Composable
fun MainBottomNavigation(navController: NavHostController, bottomBarState: MutableState<Boolean>, bottomBarPlayerState : MutableState<Boolean>) {

    val navItems = listOf(
        Routes.Home,
        Routes.Search,
        Routes.Library
    )
    // Visibility follows the screen actually shown (not flags each screen sets,
    // which raced during transitions and left the mini player over the full one).
    val shownRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // The SpotiFLAC verification too: under the bar its retry and skip buttons were out of reach.
    val fullScreen = shownRoute == Routes.Player.route || shownRoute == Routes.Queue.route || shownRoute == Routes.Spotiflac.route
    val hasTrack = com.music.spotui.di.Graph.currentSongState.title.value.isNotBlank()
    // No motion of its own: the bar sits above the pages, so sliding it while the
    // player rises (or collapses behind it) made one gesture look like two.
    AnimatedVisibility(
        visible = !fullScreen,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.ExitTransition.None,
        content = {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    // Settings, Display: the fade over the list (the default), or solid black (from #15).
                    .then(
                        if (com.music.spotui.data.preferences.SolidNavBar.on) Modifier.background(Color.Black)
                        else Modifier.background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black), startY = 0f))
                    )
            ) {

                Column(modifier = Modifier.navigationBarsPadding()) {

                    AnimatedVisibility(
                        visible = hasTrack,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                        content = {
                            MiniPlayer(navController)
                        }
                    )



                    Spacer(modifier = Modifier.height(4.dp))
                    NavigationBar(
                        modifier = Modifier
                            .height(60.dp)
                            .padding(30.dp, 0.dp)
                            .fillMaxWidth(),
                        containerColor = Color.Transparent,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        val navStack by navController.currentBackStackEntryAsState()
                        val currentRoute = navStack?.destination?.route
                        // The tab the user is in, even on one of its sub-pages (album, artist…).
                        var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(Routes.Home.route) }
                        if (currentRoute in navItems.map { it.route }) selectedTab = currentRoute!!

                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = selectedTab == item.route,
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            id = item.icon
                                        ), contentDescription = "home"
                                    )
                                },
                                label = {
                                    if (currentRoute == item.route) {
                                        Text(color = Color.White, text = if (item.labelRes != 0) stringResource(item.labelRes) else item.label, fontSize = 11.sp)
                                    } else {
                                        Text(
                                            color = Color.Gray,
                                            text = if (item.labelRes != 0) stringResource(item.labelRes) else item.label,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                onClick = {
                                    // Standard bottom-tab navigation: each tab keeps its
                                    // state (no reload, scroll kept) when you come back.
                                    if (item.route == selectedTab) {
                                        // Same tab again: back to its root page (Spotify behaviour).
                                        if (currentRoute != item.route && !navController.popBackStack(item.route, inclusive = false)) {
                                            navController.navigate(item.route) { launchSingleTop = true }
                                        }
                                    } else {
                                        selectedTab = item.route
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            // Home always opens Home itself (its scroll kept), not the
                                            // page last left open from it (a playlist, an artist…).
                                            restoreState = item.route != Routes.Home.route
                                        }
                                    }
                                },
                                alwaysShowLabel = true,
                                interactionSource = NoRippleInteractionSource(),
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    unselectedIconColor = Color.Gray,
                                    indicatorColor = Color.Transparent
                                )
                            )

                        }
                    }



                }



            }
        }
    )
}
