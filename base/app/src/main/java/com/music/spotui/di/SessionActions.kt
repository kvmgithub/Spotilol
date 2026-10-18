package com.music.spotui.di

import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Session-wide actions the screens trigger. */
object SessionActions {
    private val _signedOut = MutableStateFlow(false)
    /** True from a logout until the next sign-in: the activity shows the login page, then starts over. */
    val signedOut: StateFlow<Boolean> = _signedOut

    private val _switching = MutableStateFlow(false)
    /** A saved profile is being applied: the activity waits on its loading screen, not the login page. */
    val switching: StateFlow<Boolean> = _switching

    /**
     * Signs out and drops everything tied to the account, so the next account on
     * this phone starts clean: prefs and in-memory caches, the saved screens,
     * the listen history (the Recap), and the Web Player's cookies, Web storage
     * and cache (in the host).
     */
    fun logout(context: android.content.Context) {
        clearAccount(context.applicationContext)
        _signedOut.value = true
        MyspotiRuntime.host.logout()
    }

    /**
     * Spotilol profiles: the same clean slate as [logout], then the saved account's session
     * is restored instead of the login page. [onFailed] runs (main thread) when the profile
     * holds nothing restorable; the activity then shows the login page.
     */
    fun switchProfile(context: android.content.Context, cookies: Map<String, String>, onFailed: () -> Unit) {
        clearAccount(context.applicationContext)
        _switching.value = true
        _signedOut.value = true
        MyspotiRuntime.host.switchSession(cookies) { ok ->
            if (!ok) {
                _switching.value = false
                onFailed()
                MyspotiRuntime.host.openLogin()
            }
        }
    }

    private fun clearAccount(app: android.content.Context) {
        app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("LoggedIn", false).remove("CurrentAccountName").apply()
        // Nothing of the account keeps playing or showing: local songs, the notification.
        LocalPlayer.stop()
        PlaybackService.end()
        runCatching { MyspotiRuntime.scope.launch { runCatching { MyspotiRuntime.api.player.pause() } } }
        com.music.spotui.ui.screens.ContextViewModel.clearCache()
        com.music.spotui.data.api.Api.HomeCache.clear()
        com.music.spotui.data.api.Api.LikedCache.tracks = null
        com.music.spotui.data.api.ProfileCache.clearSession()
        SongPlayer.clearSession()
        listOf("PlaybackState", "LibraryCache", "RecentItems", "LikedSongs",
            "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        com.music.spotui.data.local.ListenHistory.clear()
    }

    /** The new account is signed in. */
    fun signedIn() { _signedOut.value = false; _switching.value = false }
}
