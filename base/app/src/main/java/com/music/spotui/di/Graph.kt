package com.music.spotui.di

import android.app.Application
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.launch
import com.music.spotui.data.api.Api
import com.music.spotui.ui.repository.AppRepository

/** Manual replacement for Spotui's Hilt graph: app-wide singletons. */
object Graph {
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
        // The data saver first: on a metered network with it on, nothing may go out, not even the page.
        com.music.spotui.data.DataSaver.init(application)
        // The Spotify session: hidden Web Player + API (operation hashes refresh themselves).
        MyspotiRuntime.start(application, loadPage = !com.music.spotui.data.DataSaver.active)
        // The streaming quality the user chose, set on the player at every page load.
        com.music.spotui.data.preferences.applyStreamingQuality(com.music.spotui.data.preferences.getStreamingQuality(application))
        // The crossfade length (0 = off), in the player from the first song on.
        com.music.spotui.data.preferences.applyCrossfade(com.music.spotui.data.preferences.getCrossfadeSeconds(application))
        // The volume boost (100 % = off), and back to 100 % when no sound comes through it.
        com.music.spotui.data.preferences.BoostPref.applyToPlayer()
        if (MyspotiRuntime.isStarted) MyspotiRuntime.scope.launch {
            MyspotiRuntime.spotify.player.boostSilent.collect { com.music.spotui.data.preferences.BoostPref.turnedOff(application) }
        }
        com.music.spotui.data.local.ListenHistory.init(application)
        // Supporter accent colour state before any screen draws (no prompts in this variant).
        com.music.spotui.data.api.SupportReminder.init(application)
        // The (always empty) download index: downloads are removed, the index stays inert.
        com.music.spotui.data.offline.OfflineStore.init(application)
        com.music.spotui.data.local.LocalPlaylists.init(application)
        com.music.spotui.data.local.LibraryOrder.init(application)
        runCatching { com.music.spotui.data.DataSaverGlide.install(application) }
    }

    val api: Api by lazy { Api(app) }
    val repository: AppRepository by lazy { AppRepository(api) }
    val currentSongState: CurrentSongState by lazy { CurrentSongState() }
}
