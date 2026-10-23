package com.music.spotui.lossless

/*
 * Ported from Spotui (https://github.com/Spotui/Spotui), GPL-3.0, and adapted:
 * Odesli is gone (public API closed), so ids come from the providers' own
 * backends (search by title+artist, ISRC matched) and Qobuz's public ISRC search.
 */

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Resolves a Spotify track to lossless FLAC — the SpotiFLAC download servers first
 * (Qobuz, with the user's signed session), then the public community backends. Each
 * provider fails gracefully: a down or throttled source just falls through.
 *
 * Priority (most reliable first):
 *   1. Qobuz (found by ISRC in its own catalogue) through the SpotiFLAC (Zarz)
 *      servers and the user's signed session — 16 or 24-bit.
 *   2. Tidal community backends — `/track/?id=`, single-file FLAC (16) or a
 *      DASH manifest to remux into one FLAC file (24).
 *   3. Amazon self-resolve by Spotify id (often dormant).
 *
 * The same recording only: ISRC first (exact), else title+artist normalised with
 * a duration within 3 s — a live take, a remix or a cover is never taken.
 */
object LosslessSource {

    private const val TAG = "LosslessSource"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Lost audio types the resolver hands back. Either [url] or [dashBase64] is set. */
    data class LosslessFile(
        val url: String,
        val dashBase64: String,
        val provider: String,
        /** Bit depth the source claims (the STREAMINFO after a remux tells the truth). */
        val quality: Int,
        /** An AAC file served as it is (Tidal's LOW / HIGH), not a FLAC. */
        val aac: Boolean = false,
    )

    sealed interface Result
    data class Success(val track: LosslessFile, val isrcMatched: Boolean) : Result
    object NotFound : Result
    data class Error(val message: String) : Result

    /** Track details from the play site; the downloader fills them from Spotify. */
    data class TrackInfo(val title: String = "", val artist: String = "", val durationSec: Int = 0, val isrc: String = "")

    /** Backends sometimes serve 30 s previews instead of the song: anything under this is one. */
    const val MIN_FULL_TRACK_SEC = 35

    suspend fun resolve(
        context: Context,
        spotifyId: String,
        info: TrackInfo = TrackInfo(),
        preferHiRes: Boolean = false,
        /** The "smallest" quality: Tidal's own AAC first, as SpotiFLAC asks for LOW / HIGH. */
        small: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        // Qobuz's own catalogue search finds the ISRC quickly and does not depend on the
        // community backends; the Tidal id does (their search), and they are often down.
        fun verified(ext: ZarzSession.Ext) = com.music.spotui.data.preferences.getSpotiflacSession(context, ext.appVersion) != null
        // No verified server at all: only the public ones are left, often down. Say so.
        if (ZarzSession.Ext.entries.none(::verified)) verificationNeeded(context)

        // 1. Tidal through the SpotiFLAC download servers (Zarz), with the user's verified
        //    session, as SpotiFLAC Mobile's tidal-web extension does: the most reliable source.
        //    Tidal's own public API finds the exact ISRC.
        if (verified(ZarzSession.Ext.TIDAL)) {
            tidalIdForIsrc(info.isrc)?.let { id ->
                if (small) zarzTidalAac(context, id)?.let { return@withContext it }
                zarzTidal(context, id, info, preferHiRes)?.let { return@withContext it }
            }
        }
        // 2. Qobuz the same way (qobuz-web), found by ISRC in Qobuz's own catalogue.
        val qobuz = qobuzIdForIsrc(info.isrc)
        if (qobuz != null && verified(ZarzSession.Ext.QOBUZ)) {
            zarzQobuz(context, qobuz, info, preferHiRes)?.let { return@withContext it }
        }

        // 3. Tidal community backends (no account needed).
        val tidal = tidalHit(info)
        if (tidal != null) tidalDirect(tidal, info, preferHiRes)?.let { return@withContext it }

        // 4. Amazon (self-resolves from the Spotify id; usually dormant).
        amazon(spotifyId)?.let { return@withContext it }

        NotFound
    }

    /**
     * Qobuz by its track id, as the qobuz-web extension asks for it: a download ticket for
     * the track's URL, then POST /dl/qbz with it. 24-bit max first when hi-res is wanted.
     */
    private fun zarzQobuz(context: Context, qobuzId: String, info: TrackInfo, preferHiRes: Boolean): Result? {
        val trackUrl = "https://open.qobuz.com/track/$qobuzId"
        val qualities = if (preferHiRes) listOf("hi-res-max", "cd") else listOf("cd")
        for (q in qualities) {
            try {
                val ticket = ZarzSession.ticket(context, ZarzSession.Ext.QOBUZ, "qbz", "track", trackUrl)
                val o = ZarzSession.signedJson(context, ZarzSession.Ext.QOBUZ, "POST", "/dl/qbz", JSONObject()
                    .put("quality", q).put("upload_to_r2", false).put("id", qobuzId)
                    .put("type", "track").put("url", trackUrl), mapOf("X-Zarz-Ticket" to ticket))
                val data = o.optJSONObject("data") ?: JSONObject()
                val url = listOf("download_url", "url", "link")
                    .firstNotNullOfOrNull { k -> o.optString(k).ifBlank { data.optString(k) }.takeIf { it.startsWith("http") } }
                    ?: continue
                val bits = o.optInt("bit_depth", data.optInt("bit_depth", 0)).takeIf { it in 8..32 } ?: if (q == "cd") 16 else 24
                if (!isFullTrack(url, info.durationSec)) continue
                // The Qobuz id came from a search on the ISRC itself, checked against it.
                return Success(LosslessFile(url, "", "Qobuz", bits), isrcMatched = true)
            } catch (e: ZarzSession.VerifyRequired) {
                // The servers want a new verification: forget the session, Settings asks again.
                verificationNeeded(context)
                Log.w(TAG, "zarz qobuz: verification required")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "zarz qobuz: ${e.message}")
            }
        }
        return null
    }

    /**
     * Tidal by its track id, as the tidal-web extension asks for it: a ticket for the
     * id, then POST /dl/tid. The answer carries Tidal's own manifest: one FLAC file
     * (CD), or a DASH manifest remuxed into one FLAC (hi-res).
     */
    private fun zarzTidal(context: Context, tidalId: String, info: TrackInfo, preferHiRes: Boolean): Result? {
        val qualities = if (preferHiRes) listOf("HI_RES_LOSSLESS", "LOSSLESS") else listOf("LOSSLESS")
        for (q in qualities) {
            try {
                val ticket = ZarzSession.ticket(context, ZarzSession.Ext.TIDAL, "tid", "track", tidalId)
                val o = ZarzSession.signedJson(context, ZarzSession.Ext.TIDAL, "POST", "/dl/tid",
                    JSONObject().put("id", tidalId).put("quality", q), mapOf("X-Zarz-Ticket" to ticket))
                val data = o.optJSONObject("data") ?: continue
                if (data.optString("assetPresentation").equals("PREVIEW", true)) continue
                val file = parseTidalTrack(JSONObject().put("data", data).toString(), "https://tidal.zarz") ?: continue
                val named = file.copy(provider = if (file.dashBase64.isNotBlank()) "Tidal hi-res" else "Tidal")
                if (named.url.isNotBlank() && !isFullTrack(named.url, info.durationSec)) continue
                return Success(named, isrcMatched = true)
            } catch (e: ZarzSession.VerifyRequired) {
                verificationNeeded(context)
                Log.w(TAG, "zarz tidal: verification required")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "zarz tidal: ${e.message}")
            }
        }
        return null
    }

    /**
     * Tidal's own AAC (LOW = 96 kbit/s, else HIGH = 320), one file as Tidal serves it:
     * the small download, without a FLAC to fetch and re-encode on the phone.
     */
    private fun zarzTidalAac(context: Context, tidalId: String): Result? {
        for (q in listOf("LOW", "HIGH")) {
            try {
                val ticket = ZarzSession.ticket(context, ZarzSession.Ext.TIDAL, "tid", "track", tidalId)
                val o = ZarzSession.signedJson(context, ZarzSession.Ext.TIDAL, "POST", "/dl/tid",
                    JSONObject().put("id", tidalId).put("quality", q), mapOf("X-Zarz-Ticket" to ticket))
                val data = o.optJSONObject("data") ?: continue
                if (data.optString("assetPresentation").equals("PREVIEW", true)) continue
                val url = aacManifestUrl(data.optString("manifest")) ?: continue
                return Success(LosslessFile(url, "", "Tidal AAC ${if (q == "LOW") 96 else 320}", 16, aac = true), isrcMatched = true)
            } catch (e: ZarzSession.VerifyRequired) {
                verificationNeeded(context)
                return null
            } catch (e: Exception) {
                Log.w(TAG, "zarz tidal aac: ${e.message}")
            }
        }
        return null
    }

    /** The one file of a Tidal AAC manifest (BTS JSON, mimeType audio/mp4); null for anything else. */
    internal fun aacManifestUrl(manifestB64: String): String? = runCatching {
        val m = JSONObject(String(java.util.Base64.getMimeDecoder().decode(manifestB64), Charsets.UTF_8))
        val urls = m.optJSONArray("urls") ?: return null
        if (urls.length() != 1 || !m.optString("mimeType").contains("mp4")) return null
        urls.optString(0).takeIf { it.startsWith("http") }
    }.getOrNull()

    /** Tidal's public API: the track carrying this exact ISRC (the web player's own token). */
    internal fun tidalIdForIsrc(isrc: String): String? {
        if (isrc.isBlank()) return null
        return runCatching {
            val body = get("https://tidal.com/v1/tracks?isrc=${URLEncoder.encode(isrc.trim(), "UTF-8")}&countryCode=US",
                mapOf("x-tidal-token" to TIDAL_PUBLIC_TOKEN))
            val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until items.length()).map { items.getJSONObject(it) }
                // Several releases carry the ISRC: one that streams in full, lossless first.
                .filter { isrcMatches(it.optString("isrc"), isrc.trim()) && it.optBoolean("streamReady", true) }
                .sortedByDescending { if (it.optString("audioQuality") == "HI_RES_LOSSLESS") 2 else if (it.optString("audioQuality") == "LOSSLESS") 1 else 0 }
                .firstOrNull()?.optString("id")?.takeIf(String::isNotBlank)
        }.getOrElse { Log.w(TAG, "tidal isrc lookup: ${it.message}"); null }
    }

    /** The public token Tidal's web player sends (the tidal-web extension's default). */
    private const val TIDAL_PUBLIC_TOKEN = "49YxDN9a2aFV6RTG"

    /** The public Tidal community backends direct: search gave the id already. */
    private fun tidalDirect(tidal: TidalHit, info: TrackInfo, preferHiRes: Boolean): Result? {
        val qualities = if (preferHiRes) listOf("HI_RES", "LOSSLESS") else listOf("LOSSLESS")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            if (endpoint.isBlank()) continue
            for (q in qualities) {
                val body = runCatching { get("$endpoint/track/?id=${tidal.id}&quality=$q", keyHeader()) }
                    .getOrElse { Log.w(TAG, "tidal $endpoint: ${it.message}"); continue }
                val fromResponse = runCatching { parseTidalTrack(body, endpoint) }.getOrElse {
                    Log.w(TAG, "tidal $endpoint parse: ${it.message}"); null
                }
                val file = fromResponse ?: continue
                if (file.url.isBlank()) {
                    // DASH manifest: storage-side remux makes it a real FLAC (24-bit).
                    return Success(file, isrcMatches(tidal.isrc, info.isrc))
                }
                if (!isFullTrack(file.url, info.durationSec)) continue
                return Success(file, isrcMatches(tidal.isrc, info.isrc))
            }
        }
        return null
    }

    /** Extracts the audio reference (url or base64 DASH manifest) from a /track/ response. */
    private fun parseTidalTrack(body: String, endpoint: String): LosslessFile? {
        val obj = JSONObject(body)
        val data = obj.optJSONObject("data") ?: return null
        val bits = data.optInt("bitDepth", 16).let { if (it in 8..32) it else 16 }
        val direct = data.optString("url").takeIf { it.startsWith("http") }
        if (direct != null) {
            if (!direct.contains(".flac", true)) return null
            val host = endpoint.removePrefix("https://").substringBefore('.')
            return LosslessFile(direct, "", "Tidal ($host)", bits)
        }
        val manifest = data.optString("manifest").takeIf { it.isNotBlank() } ?: return null
        val decoded = String(android.util.Base64.decode(manifest, android.util.Base64.DEFAULT), Charsets.UTF_8)
        if (!decoded.contains("<MPD")) {
            val m = JSONObject(decoded)
            val urls = m.optJSONArray("urls") ?: return null
            if (urls.length() != 1) return null
            val url = urls.optString(0).takeIf { it.contains(".flac", true) } ?: return null
            val host = endpoint.removePrefix("https://").substringBefore('.')
            return LosslessFile(url, "", "Tidal ($host)", bits)
        }
        return LosslessFile("", manifest, "Tidal hi-res", 24)
    }

    private fun amazon(spotifyId: String): Result? {
        for (endpoint in LosslessRegistry.amazonEndpoints()) {
            if (endpoint.isBlank()) continue
            val body = runCatching { get("$endpoint/api/resolve/spotify/$spotifyId", keyHeader()) }
                .getOrElse { Log.w(TAG, "amazon $endpoint: ${it.message}"); continue }
            val url = runCatching {
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: json
                if (data.has("decryption_key") || json.has("decryption_key")) return@runCatching null
                listOf("url", "stream_url", "download_url", "streamUrl")
                    .firstNotNullOfOrNull { data.optString(it).takeIf(String::isNotBlank) }
            }.getOrElse { Log.w(TAG, "amazon $endpoint parse: ${it.message}"); null } ?: continue
            if (url.startsWith("http") && url.contains("flac", true)) {
                return Success(LosslessFile(url, "", "Amazon", 16), isrcMatched = false)
            }
        }
        return null
    }

    // ── Provider id discovery ─────────────────────────────────────────────────

    internal data class TidalHit(
        val id: String, val title: String, val artist: String, val durationSec: Int, val isrc: String,
        /** Every credited artist, when the search lists them (the first alone is [artist]). */
        val artists: List<String> = emptyList(),
    )

    /** Spotify id → Tidal track via the Tidal backend's own search, ISRC or title matched. */
    internal suspend fun tidalHit(info: TrackInfo): TidalHit? {
        val queries = searchQueries(info.title, info.artist)
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            if (endpoint.isBlank()) continue
            for (query in queries) {
                val q = URLEncoder.encode(query, "UTF-8")
                val hits = runCatching {
                    val root = JSONObject(get("$endpoint/search/?s=$q", keyHeader()))
                    val items: JSONArray = root.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
                    (0 until items.length()).mapNotNull { i ->
                        val o = items.optJSONObject(i) ?: return@mapNotNull null
                        val names = o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
                        TidalHit(
                            id = o.optString("id"), title = fullTitle(o.optString("title"), o.optString("version")),
                            artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                            durationSec = o.optInt("duration"), isrc = o.optString("isrc"),
                            artists = names,
                        )
                    }
                }.getOrElse { Log.w(TAG, "tidal search $endpoint: ${it.message}"); emptyList() }
                pickHit(hits, info.isrc, info.title, info.artist, info.durationSec)?.let { return it }
            }
        }
        return null
    }

    /**
     * What is typed in the search: the title and the main artist ("A, B" is Spotify's list, Tidal's
     * search wants one name), then the bare title when Spotify's carries a version suffix
     * ("Song - 2011 Remaster", "Song - Slowed") that makes the full phrase match nothing.
     */
    internal fun searchQueries(title: String, artist: String): List<String> {
        val main = artist.substringBefore(",").trim()
        val bare = title.substringBefore(" - ").substringBefore(" (").trim()
        return listOf("$title $main".trim(), "$bare $main".trim()).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Tidal keeps the version of a recording apart from its title ("Song" + "2011 Remaster",
     * "Slowed + Reverb"), where Spotify writes it into the title: the two are put back together.
     */
    internal fun fullTitle(title: String, version: String): String =
        if (version.isBlank() || titleKey(title).containsAll(titleKey(version))) title else "$title ($version)"

    /**
     * The hit is the very recording Spotify plays: both sides have an ISRC and it is the
     * same. A title + artist match (no ISRC, or another one) is not an ISRC match.
     */
    internal fun isrcMatches(hitIsrc: String, spotifyIsrc: String): Boolean =
        spotifyIsrc.isNotBlank() && hitIsrc.equals(spotifyIsrc, ignoreCase = true)

    /**
     * The same recording: ISRC exact if Spotify has one; otherwise same title and
     * artist with a length within 3 s, so a live version or a cover never wins.
     */
    internal fun pickHit(
        hits: List<TidalHit>, isrc: String, title: String, artist: String, durationSec: Int,
    ): TidalHit? {
        hits.firstOrNull { isrc.isNotBlank() && it.isrc.equals(isrc, ignoreCase = true) && it.id.isNotBlank() }
            ?.let { return it }
        val wanted = titleKey(title)
        val artists = artist.split(',').map(::key).filter { it.isNotEmpty() }
        return hits.firstOrNull {
            it.id.isNotBlank() && titleKey(it.title) == wanted && sameArtist(it, artists) &&
                (durationSec <= 0 || kotlin.math.abs(it.durationSec - durationSec) <= 3)
        }
    }

    /**
     * A title as the words it is made of, order aside: "Song - 2011 Remaster" and "Song (Remastered
     * 2011)" are one recording, "Song - Slowed" and "Song" are not (the duration tells the rest).
     */
    internal fun titleKey(title: String): List<String> = title.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it != "version" }
        .map { if (it == "remastered") "remaster" else it }.sorted()

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The hit's main artist, or any of its credited ones, is one of Spotify's: Spotify hands over
     * "A, B", Tidal's search names the first alone, and requiring the whole string made every
     * track with a featured artist unfindable.
     */
    private fun sameArtist(hit: TidalHit, artists: List<String>): Boolean =
        (listOf(hit.artist) + hit.artists).map(::key).any { it.isNotEmpty() && it in artists }

    // Qobuz public search API (app_id/secret are the community's published constants).
    private const val QOBUZ_APP_ID = "712109809"
    private const val QOBUZ_APP_SECRET = "589be88e4538daea11f509d29e4a23b1"
    private const val QOBUZ_API_BASE = "https://www.qobuz.com/api.json/0.2"

    /** Qobuz track id by ISRC (signed search); null when Spotify has no ISRC or Qobuz no match. */
    internal fun qobuzIdForIsrc(isrc: String): String? {
        if (isrc.isBlank()) return null
        return runCatching {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val sigPayload = "tracksearch" + "limit" + "1" + "query" + isrc.trim() + ts + QOBUZ_APP_SECRET
            val sig = MessageDigest.getInstance("MD5").digest(sigPayload.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val resp = get(
                "$QOBUZ_API_BASE/track/search?query=${URLEncoder.encode(isrc.trim(), "UTF-8")}&limit=1" +
                    "&app_id=$QOBUZ_APP_ID&request_ts=$ts&request_sig=$sig",
                emptyMap(),
            )
            val items: JSONArray = JSONObject(resp).optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
            // The search is full text: only a track carrying this very ISRC is the recording.
            (0 until items.length()).firstNotNullOfOrNull { i ->
                items.optJSONObject(i)?.takeIf { isrcMatches(it.optString("isrc"), isrc.trim()) }
                    ?.optString("id")?.takeIf(String::isNotBlank)
            }
        }.getOrElse { Log.w(TAG, "qobuz isrc search: ${it.message}"); null }
    }

    // ── Content checks ────────────────────────────────────────────────────────

    /**
     * The backends sometimes hand out a 30 s preview instead of the song. A FLAC
     * file states its length in STREAMINFO (first 42 bytes), so one tiny range
     * read tells.
     */
    internal fun isFullTrack(url: String, expectedSec: Int): Boolean {
        if (expectedSec <= 0 || url.isBlank()) return true
        val seconds = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-41")
            }
            val head = try {
                if (conn.responseCode !in 200..299) ByteArray(0) else readHead(conn.inputStream, 42)
            } finally {
                conn.disconnect()
            }
            flacDurationSec(head)
        }.getOrNull() ?: return true // not a plain FLAC header: cannot tell here, keep it
        val full = seconds >= expectedSec - 5
        if (!full) Log.w(TAG, "rejected ${seconds}s preview (expected ${expectedSec}s)")
        return full
    }

    /** Reads at most [n] bytes (API-safe: no readNBytes), whatever the stream supplies. */
    private fun readHead(input: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private fun keyHeader() = mapOf("X-API-Key" to LosslessRegistry.API_KEY)

    @Volatile private var verifyToldAt = 0L

    /**
     * A download server asks for its verification again: the user is told (once in a while)
     * instead of downloads quietly falling back to the public servers. The session itself is
     * only dropped when the server says it is invalid (ZarzSession).
     */
    private fun verificationNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - verifyToldAt < 10 * 60_000L) return
        verifyToldAt = now
        Log.w(TAG, "zarz: verification required")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context.applicationContext, app.myspoti.engine.R.string.spotiflac_verify_again, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    internal fun get(urlString: String, headers: Map<String, String>): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(120)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
