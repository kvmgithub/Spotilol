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

    // No update or donation prompts in Spotilol UI.

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
