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
        // Calls of this account still under way hand nothing on to the next one.
        MyspotiRuntime.spotify.newSession()
        app.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("LoggedIn", false).remove("CurrentAccountName").remove(LAST_ACCOUNT).apply()
        com.music.spotui.data.api.ProfileCache.clearSession()
        clearAccountData(app)
    }

    /** What the app keeps of an account, in memory and on the phone. */
    private fun clearAccountData(app: android.content.Context) {
        // Nothing of the account keeps playing or showing: local songs, the notification.
        LocalPlayer.stop()
        PlaybackService.end(app)
        runCatching { MyspotiRuntime.scope.launch { runCatching { MyspotiRuntime.api.player.pause() } } }
        com.music.spotui.ui.screens.ContextViewModel.clearCache()
        com.music.spotui.data.api.Api.HomeCache.clear()
        com.music.spotui.data.api.LibraryEdits.clear()
        com.music.spotui.data.api.TopStats.clear()
        // Likes kept for later stay this account's, sent when it signs in again; the next account gets none.
        com.music.spotui.data.LibraryOutbox.signedOut()
        SongPlayer.clearSession()
        // The playlists kept for the pickers are this account's (Myspoti 2.4.0).
        com.music.spotui.data.api.EditablePlaylists.clear()
        listOf("PlaybackState", "LibraryCache", "RecentItems", "LikedSongs",
            "LikedSongsFromSpotify", "LikedAlbums", "FollowedArtists").forEach { name ->
            app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        com.music.spotui.data.local.ListenHistory.clear()
        com.music.spotui.data.NewReleaseNotifier.forget(app)
    }

    /** The new account is signed in. */
    fun signedIn() { _signedOut.value = false; _switching.value = false }

    /**
     * The Spotify session was lost without a sign-out (an anonymous token, Spotify's sign-in page
     * back): whose account comes next is not known, maybe another one. The profile is forgotten (read
     * again as soon as the next session is ready) and the likes kept for later stay their account's,
     * sent to it only. Nothing else of a sign-out is done (Myspoti 2.3.0).
     */
    fun sessionLost() {
        app.myspoti.host.android.Logbook.log("session lost without a sign-out: the account is read again at the next session")
        MyspotiRuntime.spotify.newSession()
        com.music.spotui.data.api.ProfileCache.clearSession()
        com.music.spotui.data.LibraryOutbox.signedOut()
    }

    private const val LAST_ACCOUNT = "LastAccountId"

    /**
     * The profile read for a session ([id]). Another account than the last one (a session lost,
     * then someone else signed in): what the app kept of the one before goes, as at a sign-out.
     * The same account keeps it all (its listen history, its caches).
     */
    fun accountSeen(context: android.content.Context, id: String) {
        val prefs = context.getSharedPreferences("myspoti_prefs", android.content.Context.MODE_PRIVATE)
        val last = prefs.getString(LAST_ACCOUNT, null)
        if (last == id) return
        if (last != null) {
            app.myspoti.host.android.Logbook.log("another account than the last one: its data cleared")
            clearAccountData(context)
        }
        prefs.edit().putString(LAST_ACCOUNT, id).apply()
    }
}
