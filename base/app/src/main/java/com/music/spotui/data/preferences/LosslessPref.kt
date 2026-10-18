package com.music.spotui.data.preferences

/*
 * Lossless (SpotiFLAC) download preferences: availability and quality.
 */

import android.content.Context

private const val PREF = "LosslessPref"
private const val KEY_HI_RES = "hires_24bit"
private const val KEY_ENABLED = "enabled"
private const val KEY_SMALL = "small_aac"
private const val KEY_ASK = "ask_each_time"

/**
 * Hi-res downloads (24-bit when the provider has it: bigger files, ~3× a CD-quality FLAC).
 * Default off: CD-quality FLAC already beats everything the old sources could give.
 */
fun isHiRes(context: Context): Boolean = prefs(context).getBoolean(KEY_HI_RES, false)

fun setHiRes(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_HI_RES, on).apply()

/** Download tracking switches off, "Downloaded" becomes Local Files only (lossless off = no FLAC downloads). */
fun isLosslessEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

fun setLosslessEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

/** Downloads kept small: the checked FLAC re-encoded to AAC on the phone. */
fun isSmallDownloads(context: Context): Boolean = prefs(context).getBoolean(KEY_SMALL, false)

fun setSmallDownloads(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SMALL, on).apply()

/** The quality is asked at each download (once for a whole list). */
fun isAskDownloadQuality(context: Context): Boolean = prefs(context).getBoolean(KEY_ASK, false)

fun setAskDownloadQuality(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ASK, on).apply()

private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
