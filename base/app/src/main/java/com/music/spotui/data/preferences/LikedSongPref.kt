package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel

fun addLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.putString(songId, songId)
    editor.apply()
}

fun removeLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    val editor = sharedPreferences.edit()
    editor.remove(songId)
    editor.apply()
}

fun isSongLiked(context: Context, songId: String): Boolean {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.contains(songId)
}

fun getLikedSongIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE)
    return sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
}

fun getSongsByIds(songIds: Set<Int>, songs: List<SongsModel>): List<SongsModel> {
    return songs.filter { song -> song.id in songIds }
}
/**
 * Mirrors a Spotify Liked Songs fetch in one write. A [complete] fetch also drops ids
 * an earlier fetch brought in that Spotify no longer has (unliked elsewhere). Likes
 * made only in this app were never in that earlier set, so they stay.
 */
fun syncSpotifyLikedSongIds(context: Context, ids: Set<String>, complete: Boolean) {
    val seed = context.getSharedPreferences("LikedSongsFromSpotify", Context.MODE_PRIVATE)
    val previous = seed.getStringSet("ids", null).orEmpty()
    context.getSharedPreferences("LikedSongs", Context.MODE_PRIVATE).edit().apply {
        if (complete) (previous - ids).forEach { remove(it) }
        ids.forEach { putString(it, it) }
    }.apply()
    if (complete) seed.edit().putStringSet("ids", ids).apply()
}
