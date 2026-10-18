package com.music.spotui.data.preferences

/*
 * Sound quality: what the Web Player streams, and how downloads are saved.
 */

import android.content.Context
import kotlinx.coroutines.launch

private const val PREF = "settings_prefs"
private const val KEY_STREAMING = "streaming_quality"
private const val KEY_CHOSEN = "quality_chosen"

/**
 * The Web Player's own streaming levels (its "Streaming quality" setting): 24, 96 and
 * 160 kbit/s. Only the levels a free account gets; "very_high" (320) is Premium's.
 */
enum class StreamingQuality(val level: String) {
    AUTO("auto"), LOW("low"), NORMAL("normal"), HIGH("high");

    companion object {
        fun of(level: String?): StreamingQuality = entries.firstOrNull { it.level == level } ?: AUTO
    }
}

/**
 * How a downloaded song is saved: not at all, small (AAC re-encoded from the checked FLAC),
 * FLAC 16-bit (CD), or FLAC 24-bit when it exists.
 */
enum class DownloadQuality {
    OFF, SMALL, CD, HI_RES,
    /** Asked when a download starts (once for a whole playlist or album). */
    ASK;

    companion object {
        /** A quality a file can be saved in ([ASK] and [OFF] are not); null when unknown. */
        fun saved(name: String?): DownloadQuality? = entries.firstOrNull { it.name == name }?.takeIf { it != ASK && it != OFF }
    }
}

fun getStreamingQuality(c: Context): StreamingQuality =
    StreamingQuality.of(prefs(c).getString(KEY_STREAMING, null))

/** Saves the level and hands it to the player (now, and on every later page load). */
fun setStreamingQuality(c: Context, q: StreamingQuality) {
    prefs(c).edit().putString(KEY_STREAMING, q.level).apply()
    applyStreamingQuality(q)
}

/** Gives the saved level to the Web Player; harmless before it is up (it keeps the level). */
fun applyStreamingQuality(q: StreamingQuality) {
    val runtime = app.myspoti.host.android.MyspotiRuntime
    if (!runtime.isStarted) return
    runtime.scope.launch { runtime.spotify.player.setAudioQuality(q.level) }
}

fun getDownloadQuality(c: Context): DownloadQuality = when {
    !isLosslessEnabled(c) -> DownloadQuality.OFF
    isAskDownloadQuality(c) -> DownloadQuality.ASK
    isSmallDownloads(c) -> DownloadQuality.SMALL
    isHiRes(c) -> DownloadQuality.HI_RES
    else -> DownloadQuality.CD
}

fun setDownloadQuality(c: Context, q: DownloadQuality) {
    setLosslessEnabled(c, q != DownloadQuality.OFF)
    setHiRes(c, q == DownloadQuality.HI_RES)
    setSmallDownloads(c, q == DownloadQuality.SMALL)
    setAskDownloadQuality(c, q == DownloadQuality.ASK)
}

/** The quality step of the onboarding was seen (new installs and updates alike). */
fun isQualityChosen(c: Context): Boolean = prefs(c).getBoolean(KEY_CHOSEN, false)
fun setQualityChosen(c: Context) = prefs(c).edit().putBoolean(KEY_CHOSEN, true).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
