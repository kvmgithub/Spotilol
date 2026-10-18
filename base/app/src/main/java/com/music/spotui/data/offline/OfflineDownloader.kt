package com.music.spotui.data.offline

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isLosslessEnabled
import com.music.spotui.di.Graph
import com.music.spotui.lossless.DashFlacRemux
import com.music.spotui.lossless.LosslessSource
import com.music.spotui.video.AudioAlign
import com.music.spotui.video.InterruptibleHttp
import com.music.spotui.video.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads songs for offline listening. Spotify only gives the track (title, artist,
 * duration, ISRC); the audio comes lossless, FlAC, resolved like SpotiFLAC does:
 *
 * 1. the same recording on a lossless service, matched by the exact ISRC, else by
 *    title + artist + duration within 3 s — never a live take, a remix or a cover;
 * 2. the most reliable source first: SpotiFLAC's own gated backend (the signing
 *    session the user verified once), then the public Tidal community backends,
 *    then Amazon's self-resolving endpoint. 24-bit when it exists, else CD;
 * 3. a check against the recording's official 30 s preview (Deezer, looked up by
 *    ISRC): the two audios are aligned, which confirms the right take. Without a
 *    preview, only an exact ISRC match is trusted.
 *
 * Nothing that fails the check is kept: the song is marked unavailable instead.
 */
object OfflineDownloader {
    private const val TAG = "Offline"
    private const val WORKERS = 3
    private const val MIN_SCORE = 0.5

    data class Request(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: String,
        val durationMs: Long,
        val coverUrl: String,
        /** The quality chosen for this download ([DownloadQuality.name]); empty = the setting. */
        val quality: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", trackId).put("title", title).put("artist", artist)
            .put("album", album).put("albumId", albumId).put("duration", durationMs).put("cover", coverUrl)
            .put("quality", quality)

        companion object {
            fun of(s: SongsModel) = Request(
                trackId = s.spotifyTrackId, title = s.title, artist = s.singer, album = s.album,
                albumId = s.albumId, durationMs = s.durationMs.toLong(), coverUrl = s.coverUri,
            )
            fun fromJson(o: JSONObject) = Request(
                o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optString("albumId"), o.optLong("duration"), o.optString("cover"), o.optString("quality"),
            )
        }
    }

    private class Unavailable(message: String) : Exception(message)

    /** Two AAC encodes at once at most: the CPU stays free for the rest of the app. */
    private val encodeSlots = kotlinx.coroutines.sync.Semaphore(2)

    @Volatile private var hinted = false

    /**
     * Without the SpotiFLAC verification only the public servers are tried, and they are
     * often down or throttled: say once per run where the reliable source is, instead of
     * leaving a list of "no matching recording".
     */
    private fun hintVerification() {
        if (hinted || com.music.spotui.data.preferences.hasSpotiflacSession(Graph.app)) return
        hinted = true
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_verify_hint, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Songs queued, downloading, or whose download did not succeed (for the Downloaded list). */
    val requests: SnapshotStateMap<String, Request> = mutableStateMapOf()
    private val queue = Channel<Request>(Channel.UNLIMITED)
    /** Requests not finished yet, in order (persisted: a restart resumes them). */
    private val pending = LinkedHashMap<String, Request>()
    private val running = ConcurrentHashMap<String, Job>()
    // One decode + alignment at a time: they are the CPU- and memory-heavy part.
    private val checkLock = Mutex()
    @Volatile private var started = false

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            repeat(WORKERS) { scope.launch { for (r in queue) work(r) } }
        }
    }

    /** Re-queues what was waiting when the app last stopped. */
    fun resumePending() {
        val saved = runCatching {
            val a = JSONArray(OfflineStore.pendingFile.readText())
            (0 until a.length()).map { Request.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) enqueueAll(saved)
    }

    /**
     * Downloads [songs] in [quality] (asked when the setting is "ask each time"), else in
     * the quality chosen in Settings.
     */
    fun enqueue(
        songs: List<SongsModel>,
        quality: com.music.spotui.data.preferences.DownloadQuality? = null,
        verifiedOrSkipped: Boolean = false,
        /** Started by the app (a downloaded list keeping in step), not by a tap. */
        auto: Boolean = false,
    ) {
        // Downloads switched off in Settings: say so, instead of a list of failed songs.
        if (!isLosslessEnabled(Graph.app)) {
            scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.downloads_off_toast, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val verified = com.music.spotui.data.preferences.hasSpotiflacSession(Graph.app)
        if (auto) {
            // Automatic downloads wait for Wi-Fi (never the user's mobile data), and for a
            // verified server: nothing opens by itself, the next sync takes them.
            if (com.music.spotui.data.DataSaver.metered || !OfflineStore.isOnline(Graph.app)) { OfflineCollections.waitForWifi(); return }
            if (!verified) return
        } else if (verifiedOrSkipped || verified) {
            // Asked for by the user on mobile data: it goes ahead, but they are told.
            if (com.music.spotui.data.DataSaver.metered) scope.launch(Dispatchers.Main) {
                android.widget.Toast.makeText(Graph.app, app.myspoti.engine.R.string.download_on_mobile_data, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // No SpotiFLAC server verified (never done, or lapsed): the verification opens first,
        // and these songs download once it is done or skipped (SpotiflacGate.resume).
        if (!auto && !verifiedOrSkipped && !verified) {
            SpotiflacGate.hold(songs, quality)
            return
        }
        val q = quality?.let { com.music.spotui.data.preferences.DownloadQuality.saved(it.name) }?.name.orEmpty()
        enqueueAll(songs.filter { it.spotifyTrackId.isNotBlank() }.map { Request.of(it).copy(quality = q) })
    }

    private fun enqueueAll(list: List<Request>) {
        val added = synchronized(pending) {
            list.filter { r ->
                !OfflineStore.isDownloaded(r.trackId) && pending.putIfAbsent(r.trackId, r) == null
            }.also { if (it.isNotEmpty()) savePending() }
        }
        if (added.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            added.forEach { requests[it.trackId] = it; OfflineStore.status[it.trackId] = OfflineStatus.Queued }
        }
        start()
        added.forEach { queue.trySend(it) }
    }

    /** Stops a queued or running download (and forgets a failed one). */
    fun cancel(trackId: String) {
        synchronized(pending) { if (pending.remove(trackId) != null) savePending() }
        running.remove(trackId)?.cancel()
        OfflineStore.status.remove(trackId)
        requests.remove(trackId)
    }

    /** Tries a failed song again. */
    fun retry(trackId: String) {
        val r = requests[trackId] ?: return
        enqueueAll(listOf(r))
    }

    /** Removes the song: the file if downloaded, else its download. */
    fun remove(trackId: String) {
        cancel(trackId)
        // File deletion on the IO scope, not the (UI) caller's thread.
        scope.launch { OfflineStore.remove(trackId) }
    }

    fun removeAll() {
        val ids = synchronized(pending) { pending.keys.toList().also { pending.clear(); savePending() } }
        ids.forEach { running.remove(it)?.cancel() }
        running.values.forEach { it.cancel() }
        requests.clear()
        // Deleting a large library can take a while: IO scope, not the caller.
        scope.launch { OfflineCollections.removeAll(); OfflineStore.removeAll() }
    }

    private fun savePending() {
        runCatching {
            val a = JSONArray()
            pending.values.forEach { a.put(it.toJson()) }
            OfflineStore.pendingFile.writeText(a.toString())
        }
    }

    private suspend fun setStatus(id: String, s: OfflineStatus?) = withContext(Dispatchers.Main) {
        if (s == null) OfflineStore.status.remove(id) else OfflineStore.status[id] = s
    }

    private suspend fun work(r: Request) {
        // Cancelled while it waited in the queue.
        if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        // Data saver on mobile data: downloads wait for Wi-Fi.
        while (com.music.spotui.data.DataSaver.active) {
            kotlinx.coroutines.delay(5_000)
            if (synchronized(pending) { !pending.containsKey(r.trackId) }) return
        }
        val job = scope.launch {
            val outcome: OfflineStatus? = try {
                setStatus(r.trackId, OfflineStatus.Downloading(-1f))
                val t = download(r)
                // Already on the IO scope: the index rewrite stays off the main thread.
                OfflineStore.add(t)
                withContext(Dispatchers.Main) { requests.remove(r.trackId) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Unavailable) {
                Log.i(TAG, "${r.title}: ${e.message}")
                hintVerification()
                OfflineStatus.Unavailable
            } catch (t: Throwable) {
                Log.w(TAG, "${r.title} failed", t)
                OfflineStatus.Failed
            }
            synchronized(pending) { pending.remove(r.trackId); savePending() }
            if (outcome != null) setStatus(r.trackId, outcome)
        }
        running[r.trackId] = job
        job.join()
        running.remove(r.trackId, job)
    }

    private suspend fun download(r: Request): OfflineTrack {
        if (!isLosslessEnabled(Graph.app)) throw Unavailable("lossless downloads are off")
        val quality = qualityOf(r)
        val tmp = OfflineStore.tmpDir
        val audioTmp = File(tmp, "${r.trackId}.flacpart")
        val previewTmp = File(tmp, "${r.trackId}.preview")
        try {
            val meta = spotifyMeta(r)

            // The official 30 s preview of the recording, for the alignment check.
            val previewLink = previewUrl(meta)
            val preview = previewLink?.let { url ->
                runCatching { httpGet(url, previewTmp); previewTmp }
                    .onFailure { Log.w(TAG, "${meta.title}: preview download failed: $it") }.getOrNull()
            }
            val previewFeatures = preview?.let { f ->
                checkLock.withLock {
                    runCatching { runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(f)) } }
                        .onFailure { Log.w(TAG, "${meta.title}: preview decode failed: $it") }.getOrNull()
                }
            }
            Log.i(TAG, "${meta.title}: isrc=${meta.isrc ?: "-"} preview=${if (previewFeatures != null) "ok" else if (previewLink != null) "unusable" else "none"}")

            // ── Lossless resolve: gated SpotiFLAC first, then the community backends.
            val file = resolveAndFetch(r, meta, audioTmp, quality == com.music.spotui.data.preferences.DownloadQuality.HI_RES,
                small = quality == com.music.spotui.data.preferences.DownloadQuality.SMALL)
                ?: throw Unavailable("no lossless match")
            currentCoroutineContext().ensureActive()

            // ── Verification before the file is kept.
            if (previewFeatures != null) {
                val score = checkLock.withLock {
                    val full = runInterruptible(Dispatchers.Default) { AudioAlign.features(AudioAlign.decode(audioTmp)) }
                    val map = AudioAlign.align(previewFeatures, full)
                    if (map.segments.isEmpty()) 0.0 else map.score
                }
                Log.i(TAG, "${meta.title}: ${file.provider} score ${"%.2f".format(score)}")
                if (score < MIN_SCORE) throw Unavailable("the lossless file did not match the recording's preview")
                file.verified = true
            } else if (!file.isrcMatched) {
                // No preview to compare with: only an exact ISRC match is trusted.
                throw Unavailable("no preview and no ISRC match")
            }

            val out: File
            if (file.aac) {
                // Tidal's own AAC: already small, kept as it was served.
                out = File(OfflineStore.audioDir, "${r.trackId}.m4a")
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            } else if (quality == com.music.spotui.data.preferences.DownloadQuality.SMALL) {
                // "Smallest": the checked FLAC becomes AAC, encoded on the phone.
                out = File(OfflineStore.audioDir, "${r.trackId}.m4a")
                val aacTmp = File(OfflineStore.tmpDir, "${r.trackId}.m4apart")
                try {
                    // Not under checkLock: encoding is long, the other downloads must not queue behind it.
                    encodeSlots.withPermit { runInterruptible(Dispatchers.Default) { AacTranscoder.transcode(audioTmp, aacTmp) } }
                    if (aacTmp.length() < 10_000 || !aacTmp.renameTo(out)) throw IOException("could not encode the small file")
                } finally {
                    aacTmp.delete()
                }
            } else {
                out = File(OfflineStore.audioDir, "${r.trackId}.flac")
                if (!audioTmp.renameTo(out)) throw IOException("could not move the file")
            }
            // Kept encrypted: the file only plays in Myspoti, on this phone.
            runInterruptible(Dispatchers.IO) { OfflineCrypto.encryptInPlace(Graph.app, out) }
            val cover = runCatching {
                val c = File(OfflineStore.coverDir, "${r.trackId}.jpg")
                httpGet(meta.coverUrl, c); c.name
            }.getOrDefault("")
            return OfflineTrack(
                trackId = r.trackId, title = meta.title, artist = meta.artist, album = meta.album,
                albumId = meta.albumId, durationMs = meta.durationMs, coverUrl = meta.coverUrl,
                file = out.name, cover = cover, source = file.provider, verified = file.verified,
                bytes = out.length(), addedAt = System.currentTimeMillis(),
            )
        } finally {
            audioTmp.delete(); previewTmp.delete()
        }
    }

    private class Fetched(val provider: String, val isrcMatched: Boolean, val aac: Boolean = false) {
        var verified = false
    }

    /** This download's quality: the one chosen when it started, else Settings (CD when Settings says "ask"). */
    private fun qualityOf(r: Request): com.music.spotui.data.preferences.DownloadQuality =
        com.music.spotui.data.preferences.DownloadQuality.saved(r.quality)
            ?: com.music.spotui.data.preferences.DownloadQuality.saved(com.music.spotui.data.preferences.getDownloadQuality(Graph.app).name)
            ?: com.music.spotui.data.preferences.DownloadQuality.CD

    /** Runs the resolver and saves the audio into [out]; null when nothing matched. */
    private suspend fun resolveAndFetch(r: Request, meta: Meta, out: File, hiRes: Boolean, small: Boolean = false): Fetched? {
        val resolved = LosslessSource.resolve(
            Graph.app, r.trackId,
            LosslessSource.TrackInfo(meta.title, meta.artist, (meta.durationMs / 1000).toInt(), meta.isrc.orEmpty()),
            preferHiRes = hiRes,
            small = small,
        )
        if (resolved !is LosslessSource.Success) return null
        val track = resolved.track
        setStatus(r.trackId, OfflineStatus.Downloading(-1f))
        val ok = when {
            track.dashBase64.isNotBlank() ->
                runInterruptible(Dispatchers.IO) {
                    DashFlacRemux.download("data:application/dash+xml;base64,${track.dashBase64}", out)
                }
            else -> fetchWithProgress(r.trackId, track.url, out)
        }
        if (!ok || out.length() < 10_000) return null
        if (track.aac) {
            Log.i(TAG, "${meta.title}: saved ${track.provider}")
            return Fetched(track.provider, resolved.isrcMatched, aac = true)
        }
        checkFlacHeader(out, meta, track)
        Log.i(TAG, "${meta.title}: saved lossless FLAC from ${track.provider} (${track.quality}-bit)")
        return Fetched("${track.provider} ${track.quality}-bit", resolved.isrcMatched)
    }

    /**
     * A STREAMINFO read on the saved file: anything that is not a FLAC file (an
     * encrypted or AAC stream some backends hand out) is refused, previews trimmed by
     * the backend are caught (the header states the real length), and a misplaced
     * 24-bit label shows up in the log.
     */
    private fun checkFlacHeader(file: File, meta: Meta, track: com.music.spotui.lossless.LosslessSource.LosslessFile) {
        val seconds = runCatching {
            file.inputStream().use { s ->
                val head = ByteArray(42); var off = 0
                while (off < head.size) { val n = s.read(head, off, head.size - off); if (n < 0) break; off += n }
                LosslessSource.flacDurationSec(head)
            }
        }.getOrNull() ?: throw Unavailable("the lossless source did not send a FLAC file")
        val expected = meta.durationMs / 1000
        if (expected > 0 && seconds < expected - 5) throw Unavailable("backed out: the file is a ${seconds}s preview")
        Log.d(TAG, "${meta.title}: FLAC ${seconds}s header check, labelled ${track.quality}-bit")
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val albumId: String,
        val durationMs: Long, val coverUrl: String, val isrc: String?,
    )

    /** The request, completed by Spotify's track (ISRC, and anything the list lacked). */
    private suspend fun spotifyMeta(r: Request): Meta {
        val api = app.myspoti.host.android.MyspotiRuntime.api
        val uri = "spotify:track:${r.trackId}"
        val t = runCatching { api.catalog.track(uri) }.onFailure { Log.w(TAG, "${r.title}: Spotify track: $it") }.getOrNull()
        val isrc = runCatching { api.catalog.isrc(uri) }.onFailure { Log.w(TAG, "${r.title}: ISRC: $it") }.getOrNull()
        val cover = r.coverUrl.ifBlank { t?.album?.images?.maxByOrNull { it.width ?: 0 }?.url.orEmpty() }
        return Meta(
            title = r.title.ifBlank { t?.name.orEmpty() },
            artist = r.artist.ifBlank { t?.artists?.joinToString(", ") { it.name }.orEmpty() },
            album = r.album.ifBlank { t?.album?.name.orEmpty() },
            albumId = r.albumId.ifBlank { t?.album?.uri?.substringAfterLast(':').orEmpty() },
            durationMs = r.durationMs.takeIf { it > 0 } ?: t?.durationMs ?: 0L,
            coverUrl = cover,
            isrc = isrc?.let(::normalizeIsrc),
        ).also { if (it.title.isBlank() || it.artist.isBlank()) throw Unavailable("no track metadata") }
    }

    /** The official 30 s preview of the recording: by ISRC, else by artist + title + duration. */
    private suspend fun previewUrl(m: Meta): String? = runInterruptible {
        runCatching {
            if (m.isrc != null) {
                val o = JSONObject(httpText("https://api.deezer.com/track/isrc:${m.isrc}"))
                o.optString("preview").takeIf { !o.has("error") && it.startsWith("http") }?.let { return@runCatching it }
            }
            val main = m.artist.substringBefore(",").trim()
            val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "$main ${m.title}").build().toString()
            val data = JSONObject(httpText(url)).optJSONArray("data") ?: JSONArray()
            pickPreview((0 until data.length()).mapNotNull { i ->
                data.optJSONObject(i)?.let {
                    PreviewHit(it.optString("preview"), it.optString("title"), it.optJSONObject("artist")?.optString("name").orEmpty(), it.optLong("duration", -1))
                }
            }, m.title, main, m.durationMs)
        }.getOrNull()
    }

    private fun httpText(url: String): String =
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string().orEmpty()
        }

    private suspend fun httpGet(url: String, out: File) = runInterruptible {
        InterruptibleHttp.read(YouTubeSource.http.newCall(okhttp3.Request.Builder().url(url).build())) { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("empty body")
            out.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * Downloads the FLAC URL into [out] with HTTP **Range** requests in chunks,
     * reporting progress. The FLAC backends hand out signed CDN urls: a plain
     * streaming GET with a browser User-Agent, retried per chunk on resets.
     */
    private suspend fun fetchWithProgress(trackId: String, url: String, out: File): Boolean {
        // A quick HEAD for the size: the progress bar needs it, the CDN allows it.
        val total = runInterruptible(Dispatchers.IO) { contentLength(url) }
        val watcher = if (total > 0) scope.launch {
            while (isActive) {
                setStatus(trackId, OfflineStatus.Downloading((out.length().toFloat() / total).coerceIn(0f, 1f)))
                delay(400)
            }
        } else null
        try {
            return runInterruptible(Dispatchers.IO) { rangedDownload(url, out, total) }
        } finally {
            watcher?.cancel()
            setStatus(trackId, OfflineStatus.Downloading(-1f))
        }
    }

    private fun rangedDownload(url: String, out: File, knownTotal: Long): Boolean {
        val chunk = 4L * 1024 * 1024 // 4 MB: the stream gets its turn between two chunks
        var total = knownTotal
        java.io.RandomAccessFile(out, "rw").use { output ->
            output.setLength(0)
            while (true) {
                // A chunk is [start, end]; a retry resumes where the bytes stopped, never re-appends.
                val end = if (total > 0) minOf(output.length() + chunk - 1, total - 1) else output.length() + chunk - 1
                var done = false
                var attempt = 0
                yieldToPlayer()
                while (true) {
                    attempt++
                    val position = output.length()
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", LosslessUa)
                        setRequestProperty("Range", "bytes=$position-$end")
                    }
                    try {
                        val code = conn.responseCode
                        // Asked past the end of a file whose size was unknown: it is complete.
                        if (code == 416 && total <= 0 && position > 0) { done = true; break }
                        if (code !in 200..299) throw IOException("stream HTTP $code")
                        val full = code != 206 // server ignored Range → the whole body, from byte 0
                        if (full) output.setLength(0)
                        if (total <= 0) total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: -1L
                        output.seek(output.length())
                        val expected = if (full) -1L else end - position + 1
                        var got = 0L
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                got += n
                            }
                        }
                        if (full) done = true
                        // A short chunk with the size still unknown is the end of the stream.
                        else if (total <= 0 && got < expected) done = true
                        break
                    } catch (e: IOException) {
                        if (attempt >= 3) throw e
                        Log.w(TAG, "chunk at ${output.length()} failed (attempt $attempt): ${e.message}")
                    } finally {
                        conn.disconnect()
                    }
                }
                if (done) break
                if (total in 1..output.length()) break
            }
        }
        return true
    }

    /**
     * The song playing here is loading: downloads wait (up to 30 s) so they do not starve
     * it, or the player's stall watchdog would take it for stuck and skip it.
     */
    private fun yieldToPlayer() {
        var waited = 0
        while (waited < 30_000 && playerStarving()) {
            Thread.sleep(500); waited += 500
        }
    }

    private fun playerStarving(): Boolean = runCatching {
        com.music.spotui.di.SongPlayer.isPlaying() && com.music.spotui.di.SongPlayer.isBuffering &&
            com.music.spotui.di.SongPlayer.activeDevice.value == null && !com.music.spotui.di.LocalPlayer.active.value
    }.getOrDefault(false)

    private fun contentLength(url: String): Long =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", LosslessUa)
            }
            try {
                conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1L)

    // ── Pure helpers (unit-tested) ──

    /** The lossless backends expect a browser-ish User-Agent on downloads. */
    internal const val LosslessUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** "bytes 0-99/1234" → 1234; null when absent or "*". */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "US-UM7-17-03861" / "usum71703861" → "USUM71703861"; null when not an ISRC. */
    fun normalizeIsrc(raw: String): String? =
        raw.uppercase().filter { it.isLetterOrDigit() }.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}").matches(it) }

    data class PreviewHit(val url: String, val title: String, val artist: String, val durationSec: Long)

    private fun folded(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").lowercase()

    /** Title without "(feat. …)", "[Remastered]", " - Radio Edit"…, letters and digits only. */
    internal fun titleKey(s: String) = folded(s)
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .replace(Regex("""\s+-\s+.*$"""), " ")
        .replace(Regex("""\b(?:feat|ft)\.?\s.*$"""), " ")
        .filter { it.isLetterOrDigit() }

    /** The search result that is the same song: title, main artist, and duration within 3 s. */
    fun pickPreview(results: List<PreviewHit>, title: String, artist: String, durationMs: Long): String? {
        if (durationMs <= 0) return null
        val t = titleKey(title)
        val a = folded(artist).filter { it.isLetterOrDigit() }
        return results.firstOrNull { h ->
            h.url.startsWith("http") && h.durationSec > 0 &&
                kotlin.math.abs(h.durationSec * 1000 - durationMs) <= 3000 &&
                titleKey(h.title) == t && folded(h.artist).filter { it.isLetterOrDigit() } == a
        }?.url
    }
}
