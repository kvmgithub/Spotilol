package com.music.spotui.data.api

import android.util.Log
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.LyricsColors
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Lyrics: Spotify's own color-lyrics, by track id, exactly what the official app shows.
 * MySpotilol keeps Spotify as the only source: no LRCLIB, NetEase, Genius or other
 * third-party lookups (they would receive the title and artist of every track).
 */
object LyricsApi {
    /** What the resolver needs to know about a track. */
    data class Track(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String = "",
    ) {
        val key: String get() = trackId.ifBlank { "${title.lowercase()}|${artist.substringBefore(",").trim().lowercase()}" }
    }

    // Cache by track: re-opening the lyrics (card + full screen ask for the same
    // track) is instant. A miss is cached too, but only for MISS_RETRY_MS — a
    // "not found" is often just a timeout, not a fact.
    private class Miss(val at: Long = System.currentTimeMillis())
    private const val MISS_RETRY_MS = 120_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>() // Lyrics | Miss
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Lyrics?>>()
    private val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun cached(track: Track): Lyrics? = cache[track.key] as? Lyrics

    /** Warm the cache in the background (current track and the next one). */
    fun prefetch(track: Track) {
        if (track.trackId.isBlank()) return
        val c = cache[track.key]
        if (c is Lyrics) return
        if (c is Miss && System.currentTimeMillis() - c.at < MISS_RETRY_MS) return
        if (inFlight.containsKey(track.key)) return
        scope.launch { runCatching { fetch(track) } }
    }

    fun forget(track: Track) { cache.remove(track.key) }

    suspend fun fetch(track: Track): Lyrics? {
        when (val c = cache[track.key]) {
            is Lyrics -> return c
            is Miss -> if (System.currentTimeMillis() - c.at < MISS_RETRY_MS) return null
        }
        // One request per track even when the card and the full screen ask together.
        val job = synchronized(inFlight) {
            inFlight[track.key] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                resolve(track)
            }.also { created ->
                inFlight[track.key] = created
                created.invokeOnCompletion { inFlight.remove(track.key, created) }
                created.start()
            }
        }
        // A cancelled card only stops waiting; the shared request owns cleanup.
        return job.await()
    }

    private suspend fun resolve(track: Track): Lyrics? {
        val result = fromSpotify(track)
        cache[track.key] = result ?: Miss()
        Log.d("LyricsApi", "lyrics: ${result?.let { "${it.lines.size} lines from ${it.provider}, synced=${it.synced}, words=${it.wordSynced}" } ?: "none"}")
        return result
    }

    private suspend fun fromSpotify(track: Track): Lyrics? {
        if (track.trackId.isBlank()) return null
        val sp = runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.lyrics("spotify:track:${track.trackId}") }
            .onFailure { Log.d("LyricsApi", "spotify lyrics miss: ${it.message}") }
            .getOrNull() ?: return null
        if (sp.lines.isEmpty()) return null
        val bg = sp.background
        val text = sp.text
        val hi = sp.highlight
        return Lyrics(
            lines = sp.lines.map { l -> LyricLine(l.startMs, l.text, l.words?.map { w -> LyricWord(w.startMs, w.startMs, w.text) }) },
            synced = sp.synced,
            language = sp.language,
            provider = sp.provider ?: "Spotify",
            colors = if (bg != null && text != null && hi != null) LyricsColors(bg, text, hi) else null,
        )
    }
}
