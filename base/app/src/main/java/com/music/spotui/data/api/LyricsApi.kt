package com.music.spotui.data.api

import android.util.Log
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.entity.LyricsColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics resolver, in the order of the spec (SPEC-V1 §5), with the provider map of
 * LiriQo / Syncanto (MIT, AlFarrizi-Studio / alfzcore) as reference:
 *
 * 1. Spotify's own color-lyrics, by track id: exactly what the official app shows,
 *    with its colours and credits.
 * 2. LRCLIB (lrclib.net, open API for third-party apps), line-synced or plain.
 * 3. Opt-in ("sources supplémentaires"): NetEase Cloud Music (line-synced LRC) and
 *    Genius (plain text). Off by default.
 *
 * Fallback results are only kept when title, artist and length match the track.
 * LRCLIB asks clients to send a descriptive User-Agent; we oblige.
 */
object LyricsApi {
    private const val LRCLIB = "https://lrclib.net/api"
    private const val UA = "Myspoti (https://github.com/)"

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

    /** Set by the settings: also ask NetEase and Genius when nothing better is found. */
    @Volatile var extraSources: Boolean = false


    fun cached(track: Track): Lyrics? = cache[track.key] as? Lyrics

    /** Warm the cache in the background (current track and the next one). */
    fun prefetch(track: Track) {
        if (track.title.isBlank() && track.trackId.isBlank()) return
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
        val result = fromSpotify(track) ?: coroutineScope {
            val title = cleanTitle(track.title)
            val artist = track.artist.substringBefore(",").trim()
            val durationSec = (track.durationMs / 1000).toInt()
            val netease = if (extraSources) async { fromNetEase(title, artist, durationSec) } else null
            val genius = if (extraSources) async { fromGenius(title, artist) } else null
            val exact = async { lrclibGet(title, artist, track.album, durationSec) }
            val fuzzy = async { lrclibSearch("$LRCLIB/search?track_name=${enc(title)}&artist_name=${enc(artist)}", title, artist, durationSec) }
            val lrclib = exact.await() ?: fuzzy.await()
                ?: lrclibSearch("$LRCLIB/search?track_name=${enc(title)}", title, artist, durationSec)
            val ne = netease?.await()
            // Synced beats plain.
            when {
                lrclib != null && lrclib.synced -> lrclib
                ne != null && ne.synced -> ne
                else -> lrclib ?: ne ?: genius?.await()
            }
        }
        cache[track.key] = result ?: Miss()
        Log.d("LyricsApi", "${track.title}: ${result?.let { "${it.lines.size} lines from ${it.provider}, synced=${it.synced}, words=${it.wordSynced}" } ?: "none"}")
        return result
    }

    // ── 1. Spotify color-lyrics ──
    private suspend fun fromSpotify(track: Track): Lyrics? {
        if (track.trackId.isBlank()) return null
        val sp = runCatching { app.myspoti.host.android.MyspotiRuntime.api.extras.lyrics("spotify:track:${track.trackId}") }
            .onFailure { Log.d("LyricsApi", "spotify lyrics miss for ${track.trackId}: ${it.message}") }
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

    // ── 4. NetEase Cloud Music (line-synced LRC) ──
    private fun fromNetEase(title: String, artist: String, durationSec: Int): Lyrics? {
        val search = httpGet("https://music.163.com/api/search/get?s=${enc("$title $artist")}&type=1&limit=6", timeoutMs = 6000)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val songArtist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            if (!isPlausibleMatch(song.optString("name"), songArtist, (song.optLong("duration") / 1000).toInt(), title, artist, durationSec)) continue
            val o = httpGet("https://music.163.com/api/song/lyric?id=${song.optLong("id")}&lv=1&kv=1&tv=-1", timeoutMs = 6000)
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val lrc = o.optJSONObject("lrc")?.optString("lyric").orEmpty()
            // Credit lines ("作词 : …") come first in NetEase LRC.
            val lines = parseLrc(lrc).filter { it.text.isNotBlank() && !it.text.contains(" : ") }
            if (lines.isEmpty()) continue
            return Lyrics(lines = lines, synced = lines.any { it.timeMs > 0 }, provider = "NetEase Cloud Music")
        }
        return null
    }

    // ── 5. Genius (plain text from the song page) ──
    private fun fromGenius(title: String, artist: String): Lyrics? {
        val search = httpGet("https://genius.com/api/search/song?per_page=5&q=${enc("$title $artist")}", timeoutMs = 6000)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val hits = search.optJSONObject("response")?.optJSONArray("sections")?.optJSONObject(0)?.optJSONArray("hits") ?: return null
        for (i in 0 until hits.length()) {
            val r = hits.optJSONObject(i)?.optJSONObject("result") ?: continue
            if (!isPlausibleMatch(r.optString("title"), r.optString("artist_names"), 0, title, artist, 0)) continue
            val html = httpGet(r.optString("url"), timeoutMs = 8000) ?: continue
            val text = geniusText(html) ?: continue
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !(it.startsWith("[") && it.endsWith("]")) }
            if (lines.isEmpty()) continue
            return Lyrics(lines = lines.map { LyricLine(0, it) }, synced = false, provider = "Genius")
        }
        return null
    }

    private val geniusContainer = Regex("<div[^>]*data-lyrics-container=\"true\"[^>]*>", RegexOption.IGNORE_CASE)

    /** The lyrics containers of a Genius song page, as plain text (nested divs balanced). */
    internal fun geniusText(html: String): String? {
        val parts = geniusContainer.findAll(html).mapNotNull { m ->
            var depth = 1
            var i = m.range.last + 1
            val start = i
            while (i < html.length && depth > 0) {
                when {
                    html.startsWith("<div", i, ignoreCase = true) -> depth++
                    html.startsWith("</div", i, ignoreCase = true) -> { depth--; if (depth == 0) return@mapNotNull html.substring(start, i) }
                }
                i++
            }
            null
        }.toList()
        if (parts.isEmpty()) return null
        return parts.joinToString("\n") { part ->
            androidx.core.text.HtmlCompat.fromHtml(part.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
                .toString()
        }.trim().takeIf { it.isNotEmpty() }
    }

    // ── 2. LRCLIB ──
    private fun lrclibGet(title: String, artist: String, album: String, durationSec: Int): Lyrics? {
        val url = buildString {
            append("$LRCLIB/get?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val o = httpGet(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!isPlausibleMatch(o.optString("trackName"), o.optString("artistName"), o.optInt("duration"), title, artist, durationSec)) return null
        return parseLrclib(o)
    }

    private fun lrclibSearch(url: String, title: String, artist: String, durationSec: Int): Lyrics? {
        val arr = httpGet(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        // Of the plausible candidates, prefer synced lyrics, then the closest duration.
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dur = o.optInt("duration", 0)
            if (!isPlausibleMatch(o.optString("trackName"), o.optString("artistName"), dur, title, artist, durationSec)) continue
            val hasSynced = o.optString("syncedLyrics").isNotBlank() && !o.isNull("syncedLyrics")
            val diff = if (durationSec > 0 && dur > 0) kotlin.math.abs(dur - durationSec).toLong() else 0L
            val score = diff + (if (hasSynced) 0 else 100_000)
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { parseLrclib(it) }
    }

    private fun parseLrclib(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && !o.isNull("syncedLyrics") }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true, provider = "LRCLIB")
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && !o.isNull("plainLyrics") } ?: return null
        return Lyrics(plain.split("\n").map { LyricLine(0L, it) }, synced = false, provider = "LRCLIB")
    }

    // ── Matching ──
    // Strip the noise Spotify puts in titles that other catalogues don't have:
    // "Song - 2011 Remaster", "Song (feat. X)", "Song [Bonus Track]".
    private val featTag = Regex("""\s*[(\[][^)\]]*(feat\.?|ft\.?|with )[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val bracketTag = Regex("""\s*[(\[][^)\]]*(remaster|remastered|live|version|edit|mono|stereo|deluxe|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private fun cleanTitle(title: String) =
        title.substringBefore(" - ").replace(featTag, "").replace(bracketTag, "").trim()

    private fun matchKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    // Exact normalized identity is conservative: ambiguous fallbacks are rejected.
    private fun overlaps(a: String, b: String) = a.isNotEmpty() && a == b

    /**
     * Fallback hits are only used when they are the same song: same artist, same
     * title (either may carry extras), and a similar length when both are known.
     */
    internal fun isPlausibleMatch(
        candTitle: String, candArtist: String, candDurationSec: Int,
        title: String, artist: String, durationSec: Int,
    ): Boolean {
        if (!overlaps(matchKey(candArtist), matchKey(artist))) return false
        if (!overlaps(matchKey(cleanTitle(candTitle)), matchKey(title))) return false
        return durationSec <= 0 || candDurationSec <= 0 || kotlin.math.abs(candDurationSec - durationSec) <= 3
    }

    // LRC: each line is "[mm:ss.xx] text" (a line may carry multiple timestamps).
    private val lrcTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split("\n")) {
            val tags = lrcTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim().ifEmpty { "♪" }
            for (m in tags) {
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 + ms, text))
            }
        }
        // Collapse runs of empty (instrumental) lines into one ♪.
        val sorted = out.sortedBy { it.timeMs }
        return sorted.filterIndexed { i, l -> !(l.text == "♪" && i > 0 && sorted[i - 1].text == "♪") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(spec: String, timeoutMs: Int = 5000): String? = runCatching {
        com.music.spotui.data.DataSaver.check()
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
        }
        try {
            if (conn.responseCode != 200) {
                Log.d("LyricsApi", "HTTP ${conn.responseCode} for $spec")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Log.d("LyricsApi", "request failed: ${it.message}"); null }
}
