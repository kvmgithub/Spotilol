package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

// Trimmed from Spotui: stream quality, YouTube and web-playback settings are gone (the Web
// Player engine streams Spotify's own audio). Crossfade is ours: a fade, not an overlap.

private const val PREF = "settings_prefs"
private const val KEY_LIBRARY_GRID = "library_grid_view"
private const val KEY_LYRIC_TRANSLATE_LANG = "lyric_translate_lang"
private const val KEY_CROSSFADE = "crossfade_seconds"


private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

/** The bottom bar: fading into the list (the default), or solid black. Read by the bar as state. */
object SolidNavBar {
    private const val KEY = "solid_nav_bar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, false))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** "When your music ends, keep playing similar songs" (on by default). Read by the player. */
object AutoplayPref {
    private const val KEY = "autoplay_similar"
    var on by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getBoolean(KEY, true))
        private set

    fun set(c: Context, value: Boolean) {
        on = value
        prefs(c).edit().putBoolean(KEY, value).apply()
    }
}

/** Screen rotation: portrait (the default), automatic (follows the system), or landscape. */
object OrientationPref {
    private const val KEY = "screen_orientation"
    const val PORTRAIT = "portrait"
    const val AUTO = "auto"
    const val LANDSCAPE = "landscape"
    var mode by androidx.compose.runtime.mutableStateOf(prefs(com.music.spotui.di.Graph.app).getString(KEY, PORTRAIT) ?: PORTRAIT)
        private set

    fun set(c: Context, value: String) {
        mode = value
        prefs(c).edit().putString(KEY, value).apply()
    }

    /** The [android.content.pm.ActivityInfo] value for the chosen mode. */
    fun requested(): Int = when (mode) {
        AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

/** Library layout: false = rows (default), true = Spotify-style 3-column grid. */
fun isLibraryGridView(c: Context): Boolean = prefs(c).getBoolean(KEY_LIBRARY_GRID, false)
fun setLibraryGridView(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_LIBRARY_GRID, v).apply()

/**
 * Crossfade length in seconds, 0 = off (the default: nothing changes unless asked for).
 *
 * It is a fade, not an overlap: the page plays one media element, so the next song cannot
 * start before this one ends. The song fades out over its last N seconds, the next one fades
 * in. The value is Compose state, so the settings row redraws when it changes.
 */
object CrossfadePref {
    const val MAX = 12
    /** The length the mini player's switch turns on: the last one chosen, 6 s at first. */
    private const val KEY_LAST_ON = "crossfade_last_on"
    var seconds by androidx.compose.runtime.mutableStateOf(
        prefs(com.music.spotui.di.Graph.app).getInt(KEY_CROSSFADE, 0).coerceIn(0, MAX),
    )
        private set

    /** Saves it and hands it to the player: the change applies to the song playing now. */
    fun set(c: Context, value: Int) {
        val v = value.coerceIn(0, MAX)
        seconds = v
        val edit = prefs(c).edit().putInt(KEY_CROSSFADE, v)
        if (v > 0) edit.putInt(KEY_LAST_ON, v)
        edit.apply()
        applyCrossfade(v)
    }

    /** The mini player's switch: off, or back on at the last length chosen. */
    fun toggle(c: Context) = set(c, if (seconds > 0) 0 else prefs(c).getInt(KEY_LAST_ON, 6).coerceIn(1, MAX))
}

fun getCrossfadeSeconds(c: Context): Int =
    prefs(c).getInt(KEY_CROSSFADE, 0).coerceIn(0, CrossfadePref.MAX)

/** Gives the saved length to the player (at start, and at every change); 0 stops a fade at once. */
fun applyCrossfade(seconds: Int) {
    val runtime = app.myspoti.host.android.MyspotiRuntime
    if (!runtime.isStarted) return
    runtime.spotify.player.crossfadeSeconds = seconds
}

/**
 * Volume boost in percent: 100 (off, the default) to [MAX]. Above 100 the page's audio plays
 * through a gain and a limiter, on this phone only (SpotifyPlayer.setBoost). If no sound comes
 * through on this phone, the player turns it off; [silent] then says so in Settings.
 */
object BoostPref {
    const val MAX = 200
    private const val KEY = "volume_boost_percent"
    var percent by androidx.compose.runtime.mutableStateOf(
        prefs(com.music.spotui.di.Graph.app).getInt(KEY, 100).coerceIn(100, MAX),
    )
        private set
    var silent by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun set(c: Context, value: Int) {
        percent = value.coerceIn(100, MAX)
        silent = false
        prefs(c).edit().putInt(KEY, percent).apply()
        applyToPlayer()
    }

    /** Gives the saved level to the player (at start, and at every change). */
    fun applyToPlayer() {
        val runtime = app.myspoti.host.android.MyspotiRuntime
        if (!runtime.isStarted) return
        val level = percent / 100.0
        runtime.scope.launch { runtime.spotify.player.setBoost(level) }
    }

    /** The player heard nothing through the boost and turned it off. */
    fun turnedOff(c: Context) {
        percent = 100
        silent = true
        prefs(c).edit().putInt(KEY, 100).apply()
    }
}

fun getLyricTranslateLang(c: Context): String =
    prefs(c).getString(KEY_LYRIC_TRANSLATE_LANG, "").orEmpty()

fun setLyricTranslateLang(c: Context, code: String) =
    prefs(c).edit().putString(KEY_LYRIC_TRANSLATE_LANG, code.trim()).apply()
