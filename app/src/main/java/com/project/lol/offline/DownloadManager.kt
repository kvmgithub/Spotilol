package com.project.lol.offline

import com.project.lol.security.WebSecurityPolicy

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.project.lol.util.Logger
import com.project.lol.innertube.YouTube
import com.project.lol.innertube.models.SongItem
import com.project.lol.offline.audio.M4aEncoder
import com.project.lol.offline.audio.Mp3Encoder
import com.project.lol.offline.audio.Mp4Remux
import com.project.lol.offline.audio.Mp4Tags
import com.project.lol.offline.audio.Tags
import com.project.lol.yt.AudioQuality
import com.project.lol.yt.CandidateScorer
import com.project.lol.yt.CandidateScorer.isAcceptableMatch
import com.project.lol.yt.YTPlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

private data class TrackMeta(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String?,
)

private data class YtMeta(
    val videoId: String?,
    val ytTitle: String,
    val ytArtist: String,
    val ytAlbum: String,
    val ytThumbnail: String?,
    val durationSec: Int?,
    val explicit: Boolean,
    val shareLink: String?,
)

private data class DownloadedTrack(
    val success: Boolean,
    val title: String,
    val artist: String,
    val album: String,
)

private sealed class TrackResult {
    data class Saved(
        val title: String,
        val artist: String,
        val album: String,
        val yt: YtMeta? = null,
    ) : TrackResult()
    data class Failed(val title: String, val artist: String, val album: String) : TrackResult()
    object Aborted : TrackResult()
}

private data class ResolvedStream(
    val container: String,
    val mimeType: String,
    val url: String,
    val chosen: SongItem,
)

private data class FinalAudio(
    val file: File,
    val ext: String,
    val mime: String,
)

private sealed class DownloadJob {
    data class Single(val track: TrackMeta) : DownloadJob()
    data class Collection(
        val name: String,
        val cover: String?,
        val tracks: List<TrackMeta>,
    ) : DownloadJob()
}

object DownloadManager {
    private const val TAG = "Spl-DL"
    private const val BATCH_INTER_TRACK_DELAY_MS = 300L
    private const val PROGRESS_MIN_INTERVAL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var onStatus: ((String) -> Unit)? = null

    @Volatile
    var onProgress: ((Int, String) -> Unit)? = null

    @Volatile
    private var activeTrackId: String? = null

    @Volatile
    private var batchActive = false

    private val jobs = Channel<DownloadJob>(Channel.UNLIMITED)

    @Volatile
    private var consumerStarted = false
    private val consumerLock = Any()

    @Volatile
    var onProgress2: ((Int, String) -> Unit)? = null

    @Volatile
    var lastPct: Int = 0

    @Volatile
    var lastLabel: String = ""

    @Volatile
    var status: DownloadStatus = DownloadStatus()
        private set

    @Volatile
    private var lastProgressAt = 0L

    private val pendingJobs = java.util.concurrent.atomic.AtomicInteger(0)

    fun isWorkPending(): Boolean = pendingJobs.get() > 0

    fun queuedCount(): Int = pendingJobs.get()

    private fun markStatus(
        active: Boolean = status.active,
        batch: Boolean = status.batch,
        collection: String = status.collection,
        title: String = status.title,
        artist: String = status.artist,
        stage: String = status.stage,
        index: Int = status.index,
        total: Int = status.total,
        saved: Int = status.saved,
        failed: Int = status.failed,
        skipped: Int = status.skipped,
        error: Boolean = status.error,
    ) {
        status = status.copy(
            active = active,
            batch = batch,
            collection = collection,
            title = title,
            artist = artist,
            stage = stage,
            index = index,
            total = total,
            saved = saved,
            failed = failed,
            skipped = skipped,
            error = error,
        )
    }

    private fun progress(pct: Int, label: String, stage: String? = null) {
        val nextStage = stage ?: label
        val now = System.currentTimeMillis()
        val terminal = pct <= 0 || pct >= 100
        val staged = nextStage != status.stage
        if (!terminal && !staged && now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS) return
        lastProgressAt = now
        lastPct = pct
        lastLabel = label
        status = status.copy(
            percent = pct.coerceIn(0, 100),
            stage = nextStage,
            error = pct < 0,
        )
        onProgress?.invoke(pct, label)
        onProgress2?.invoke(pct, label)
        if (terminal || staged) Logger.d(TAG, "download $pct% $label")
    }

    private enum class ControlSignal { SKIP, CANCEL }

    @Volatile
    private var signal: ControlSignal? = null

    fun skipCurrent() {
        signal = ControlSignal.SKIP
        Logger.i(TAG, "skipCurrent: skip requested (active=${activeTrackId ?: "none"})")
    }

    fun cancelAll() {
        signal = ControlSignal.CANCEL
        var dropped = 0
        while (jobs.tryReceive().isSuccess) {
            pendingJobs.decrementAndGet()
            dropped++
        }
        Logger.i(TAG, "cancelAll: cancel requested, dropped $dropped queued job(s)")
    }

    private fun consumeSignal(): ControlSignal? {
        val s = signal
        signal = null
        return s
    }

    fun isDownloading(): Boolean = activeTrackId != null

    fun currentTrackId(): String? = activeTrackId

    fun isBatchActive(): Boolean = batchActive

    fun downloadCurrentTrack(
        context: Context,
        payload: String,
    ) {
        val appContext = context.applicationContext
        val parsed = runCatching { JSONObject(payload) }.getOrNull() ?: run {
            onStatus?.invoke("Invalid download request")
            return
        }
        val trackId = parsed.optString("trackId").trim()
        if (!WebSecurityPolicy.isTrackId(trackId)) {
            Logger.e(TAG, "downloadCurrentTrack: empty trackId in payload")
            onStatus?.invoke("Could not identify the current track")
            return
        }
        if (trackId == activeTrackId) {
            Logger.w(TAG, "downloadCurrentTrack: $trackId is already in progress")
            onStatus?.invoke("This track is already downloading")
            return
        }
        val track = TrackMeta(
            trackId = trackId,
            title = parsed.optString("title"),
            artist = parsed.optString("artist"),
            album = parsed.optString("album"),
            cover = parsed.optString("cover").ifBlank { null },
        )
        markStatus(
            active = true,
            batch = false,
            collection = "",
            title = track.title.ifBlank { track.trackId },
            artist = track.artist,
            index = 0,
            total = 1,
            saved = 0,
            failed = 0,
            skipped = 0,
            error = false,
        )
        progress(0, "Resolving audio...")
        enqueue(appContext, DownloadJob.Single(track))
    }

    fun downloadCollection(
        context: Context,
        payload: String,
    ) {
        val appContext = context.applicationContext
        val parsed = runCatching { JSONObject(payload) }.getOrNull() ?: run {
            onStatus?.invoke("Invalid download request")
            return
        }
        val tracksJson = parsed.optJSONArray("tracks") ?: run {
            onStatus?.invoke("No tracks found")
            return
        }
        val seen = HashSet<String>()
        val tracks = ArrayList<TrackMeta>(tracksJson.length())
        for (i in 0 until tracksJson.length()) {
            val o = tracksJson.optJSONObject(i) ?: continue
            val id = o.optString("trackId").trim()
            if (!WebSecurityPolicy.isTrackId(id) || !seen.add(id)) continue
            tracks.add(
                TrackMeta(
                    trackId = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    cover = o.optString("cover").ifBlank { null },
                )
            )
        }
        if (tracks.isEmpty()) {
            onStatus?.invoke("No downloadable tracks found")
            return
        }
        val name = parsed.optString("name").ifBlank { "Collection" }
        val collectionCover = parsed.optString("cover").ifBlank { null }
        val batch = if (collectionCover != null) {
            tracks.map { if (it.cover == null) it.copy(cover = collectionCover) else it }
        } else tracks
        Logger.i(TAG, "downloadCollection: '$name' type=${parsed.optString("type")} tracks=${batch.size}")
        markStatus(
            active = true,
            batch = true,
            collection = name,
            title = "",
            artist = "",
            index = 0,
            total = batch.size,
            saved = 0,
            failed = 0,
            skipped = 0,
            error = false,
        )
        progress(0, "Queued ${batch.size} tracks — $name", "Queued")
        enqueue(appContext, DownloadJob.Collection(name, collectionCover, batch))
    }

    private fun enqueue(appContext: Context, job: DownloadJob) {
        pendingJobs.incrementAndGet()
        jobs.trySend(job)
        synchronized(consumerLock) {
            if (!consumerStarted) {
                consumerStarted = true
                scope.launch { consumeLoop(appContext) }
            }
        }
    }

    private suspend fun consumeLoop(appContext: Context) {
        for (job in jobs) {
            pendingJobs.decrementAndGet()
            signal = null
            runCatching {
                when (job) {
                    is DownloadJob.Single -> runSingle(appContext, job.track)
                    is DownloadJob.Collection -> runCollection(appContext, job)
                }
            }.onFailure { Logger.e(TAG, "consumeLoop: job failed: ${it.message}", it) }
        }
    }

    private suspend fun runSingle(appContext: Context, track: TrackMeta) {
        val trackName = track.title.ifBlank { track.trackId }
        if (OfflineStore.isTrackSaved(appContext, track.trackId)) {
            markStatus(active = false, title = trackName, artist = track.artist, stage = "Already saved", error = false)
            progress(100, "Already saved")
            withContext(Dispatchers.Main) { onStatus?.invoke("Already saved to ${DownloadPrefs.folderLabel(appContext)}") }
            return
        }
        activeTrackId = track.trackId
        markStatus(
            active = true,
            batch = false,
            title = trackName,
            artist = track.artist,
            stage = "Resolving audio",
            index = 0,
            total = 1,
            saved = 0,
            failed = 0,
            skipped = 0,
            error = false,
        )
        try {
            val result = runCatching {
                downloadToFile(appContext, track) { pct, text -> progress(pct, text) }
            }.onFailure { Logger.e(TAG, "runSingle: exception: ${it.message}", it) }
                .getOrElse {
                    if (signal != null) TrackResult.Aborted
                    else TrackResult.Failed(track.title, track.artist, track.album)
                }
            Logger.i(TAG, "runSingle: finished id=${track.trackId} result=${result::class.simpleName}")
            when (result) {
                is TrackResult.Saved -> {
                    OfflineStore.saveMetadata(
                        appContext,
                        track.trackId,
                        result.title,
                        result.artist,
                        result.album,
                        track.cover ?: result.yt?.ytThumbnail,
                        videoId = result.yt?.videoId,
                        ytTitle = result.yt?.ytTitle.orEmpty(),
                        ytArtist = result.yt?.ytArtist.orEmpty(),
                        ytAlbum = result.yt?.ytAlbum.orEmpty(),
                        ytThumbnail = result.yt?.ytThumbnail,
                        durationSec = result.yt?.durationSec,
                        explicit = result.yt?.explicit ?: false,
                        shareLink = result.yt?.shareLink,
                    )
                    progress(100, "Saved to ${DownloadPrefs.folderLabel(appContext)}")
                    withContext(Dispatchers.Main) { onStatus?.invoke("Saved to ${DownloadPrefs.folderLabel(appContext)}") }
                }
                is TrackResult.Failed -> {
                    val msg = "Download failed: ${lastDownloadError ?: "unknown error"}"
                    progress(-1, msg)
                    withContext(Dispatchers.Main) { onStatus?.invoke(msg) }
                }
                // Single track: skip and cancel both simply stop the download.
                TrackResult.Aborted -> {
                    consumeSignal()
                    progress(-1, "Cancelled")
                    withContext(Dispatchers.Main) { onStatus?.invoke("Download cancelled") }
                }
            }
        } finally {
            activeTrackId = null
            markStatus(active = false)
        }
    }

    private suspend fun runCollection(appContext: Context, job: DownloadJob.Collection) {
        val total = job.tracks.size
        var saved = 0
        var failed = 0
        var skipped = 0
        var cancelled = false
        batchActive = true
        markStatus(
            active = true,
            batch = true,
            collection = job.name,
            title = "",
            artist = "",
            stage = "Reading playlist",
            index = 0,
            total = total,
            saved = 0,
            failed = 0,
            skipped = 0,
            error = false,
        )
        Logger.i(TAG, "runCollection: start '${job.name}' total=$total")

        try {
            for ((index, track) in job.tracks.withIndex()) {
                val n = index + 1
                val shortTitle = track.title.ifBlank { track.trackId }
                val completed = saved + failed + skipped

                fun overallPct(trackPct: Int): Int {
                    val frac = if (trackPct in 0..100) trackPct / 100.0 else 0.0
                    return ((completed + frac) * 100.0 / total).toInt().coerceIn(0, 100)
                }

                fun report(pct: Int, text: String) {
                    progress(overallPct(pct), "$n/$total · $shortTitle — $text", text)
                }

                markStatus(
                    active = true,
                    index = n,
                    title = shortTitle,
                    artist = track.artist,
                    saved = saved,
                    failed = failed,
                    skipped = skipped,
                )

                // Skip/cancel tapped between tracks (e.g. during the
                // inter-track delay): consume it before starting this one.
                when (consumeSignal()) {
                    ControlSignal.CANCEL -> {
                        cancelled = true
                        break
                    }
                    ControlSignal.SKIP -> {
                        skipped++
                        Logger.i(TAG, "runCollection: user skipped ${track.trackId}")
                        report(100, "Skipped")
                        continue
                    }
                    null -> {}
                }

                if (OfflineStore.isTrackSaved(appContext, track.trackId)) {
                    skipped++
                    report(100, "Already saved")
                    continue
                }

                activeTrackId = track.trackId
                // Report at track start so the batch flags reach the UI
                // before the (non-interruptible) resolver runs.
                report(0, "Resolving...")
                try {
                    val result = runCatching {
                        downloadToFile(appContext, track) { pct, text -> report(pct, text) }
                    }.onFailure { Logger.e(TAG, "runCollection: ${track.trackId} exception: ${it.message}", it) }
                        .getOrElse {
                            if (signal != null) TrackResult.Aborted
                            else TrackResult.Failed(track.title, track.artist, track.album)
                        }

                    when (result) {
                        is TrackResult.Saved -> {
                            saved++
                            OfflineStore.saveMetadata(
                                appContext,
                                track.trackId,
                                result.title,
                                result.artist,
                                result.album,
                                track.cover ?: result.yt?.ytThumbnail,
                                videoId = result.yt?.videoId,
                                ytTitle = result.yt?.ytTitle.orEmpty(),
                                ytArtist = result.yt?.ytArtist.orEmpty(),
                                ytAlbum = result.yt?.ytAlbum.orEmpty(),
                                ytThumbnail = result.yt?.ytThumbnail,
                                durationSec = result.yt?.durationSec,
                                explicit = result.yt?.explicit ?: false,
                                shareLink = result.yt?.shareLink,
                            )
                            report(100, "Saved")
                        }
                        is TrackResult.Failed -> {
                            failed++
                            Logger.w(TAG, "runCollection: ${track.trackId} failed: $lastDownloadError")
                            report(0, "Failed — skipping")
                        }
                        TrackResult.Aborted -> when (consumeSignal()) {
                            ControlSignal.CANCEL -> cancelled = true
                            // SKIP (or the tail of a cancel): drop this track, batch lives on.
                            else -> {
                                skipped++
                                report(100, "Skipped")
                            }
                        }
                    }
                } finally {
                    activeTrackId = null
                }

                if (cancelled) break
                if (index < job.tracks.lastIndex) {
                    delay(BATCH_INTER_TRACK_DELAY_MS.milliseconds)
                }
            }
        } finally {
            batchActive = false
            markStatus(active = false)
        }

        val processed = saved + failed + skipped
        val summary = buildString {
            append(saved)
            append(if (saved == 1) " track saved" else " tracks saved")
            if (skipped > 0) append(", $skipped skipped")
            if (failed > 0) append(", $failed failed")
            if (cancelled) append(" — cancelled at $processed/$total")
        }
        Logger.i(TAG, "runCollection: done '${job.name}' saved=$saved failed=$failed skipped=$skipped cancelled=$cancelled")
        markStatus(
            batch = false,
            index = processed,
            title = "",
            artist = "",
            saved = saved,
            failed = failed,
            skipped = skipped,
            stage = summary,
        )
        withContext(Dispatchers.Main) { onStatus?.invoke(summary) }
        when {
            cancelled -> progress(-1, summary)
            saved > 0 -> progress(100, "$summary — ${DownloadPrefs.folderLabel(appContext)}")
            failed > 0 -> progress(-1, summary)
        }
    }

    private suspend fun downloadToFile(
        context: Context,
        track: TrackMeta,
        progress: (Int, String) -> Unit,
    ): TrackResult {
        val trackId = track.trackId
        require(WebSecurityPolicy.isTrackId(trackId)) { "Invalid track ID" }
        val title = track.title
        val artist = track.artist
        val album = track.album

        if (signal != null) return TrackResult.Aborted

        val format = DownloadPrefs.format(context)
        val resolved = resolveStream(
            context, trackId, title, artist, album,
            preferredMimeType = if (format == DownloadFormat.M4A) "audio/mp4" else null,
        ) ?: run {
            Logger.w(TAG, "downloadToFile: no stream source for $trackId")
            lastDownloadError = "Download source not available yet"
            return TrackResult.Failed(title, artist, album)
        }
        // Resolution takes a few seconds and is not interruptible - catch
        // aborts requested during it here instead of downloading anyway.
        if (signal != null) return TrackResult.Aborted

        val effectiveTitle = title.ifBlank { resolved.chosen.title }
        val effectiveArtist = artist.ifBlank {
            resolved.chosen.artists.firstOrNull()?.name.orEmpty()
        }
        val effectiveAlbum = album.ifBlank { resolved.chosen.album?.name.orEmpty() }

        val dir = java.io.File(context.filesDir, "downloads").apply { mkdirs() }
        val tmpFile = java.io.File(dir, "$trackId.part")
        progress(1, "Downloading audio...")
        val downloaded = httpDownloadRanged(
            resolved.url,
            tmpFile,
            { pct -> progress(pct, "Downloading audio...") },
            { signal != null },
        )
        if (!downloaded) {
            Logger.e(TAG, "downloadToFile: audio download failed: $lastDownloadError")
            runCatching { tmpFile.delete() }
            if (signal != null) return TrackResult.Aborted
            return TrackResult.Failed(effectiveTitle, effectiveArtist, effectiveAlbum)
        }
        progress(99, "Saving...")

        val writeTags = DownloadPrefs.writeTags(context)

        val sourceContainer = resolved.container
        val sourceMime = resolved.mimeType
        Logger.i(TAG, "downloadToFile: source container=$sourceContainer mime=$sourceMime trackId=$trackId")

        fun renameToAudio(source: File, ext: String): File {
            val target = File(dir, "$trackId.$ext")
            return if (source.renameTo(target)) target else source
        }

        fun m4aFrom(source: File): FinalAudio? {
            val out = File(dir, "$trackId.m4a")
            val ok = M4aEncoder.transcode(source, out, onProgress = { pct ->
                progress(60 + pct * 39 / 100, "Converting to M4A")
            })
            if (!ok) return null
            source.delete()
            return FinalAudio(out, DownloadFormat.M4A.ext, DownloadFormat.M4A.mime)
        }

        fun canonicalM4a(source: File): FinalAudio {
            if (Mp4Tags.isTaggable(source)) {
                return FinalAudio(renameToAudio(source, "m4a"), "m4a", DownloadFormat.M4A.mime)
            }
            progress(60, "Preparing M4A")
            val out = File(dir, "$trackId.canonical.m4a")
            return if (Mp4Remux.remux(source, out)) {
                source.delete()
                FinalAudio(out, DownloadFormat.M4A.ext, DownloadFormat.M4A.mime)
            } else {
                Logger.w(TAG, "downloadToFile: m4a remux failed, keeping original")
                FinalAudio(renameToAudio(source, "m4a"), "m4a", DownloadFormat.M4A.mime)
            }
        }

        val finalAudio: FinalAudio? = when (format) {
            DownloadFormat.M4A -> when {
                sourceContainer == "m4a" -> canonicalM4a(tmpFile)
                M4aEncoder.isTranscodable(sourceMime) -> m4aFrom(tmpFile)
                else -> null
            }
            DownloadFormat.MP3 -> {
                val mp3File = File(dir, "$trackId.mp3")
                val ok = Mp3Encoder.transcode(tmpFile, mp3File) { pct ->
                    progress(60 + pct * 39 / 100, "Converting to MP3")
                }
                if (ok) {
                    tmpFile.delete()
                    FinalAudio(mp3File, DownloadFormat.MP3.ext, DownloadFormat.MP3.mime)
                } else {
                    Logger.w(TAG, "downloadToFile: mp3 transcode failed, falling back to $sourceContainer output")
                    when {
                        sourceContainer == "m4a" -> canonicalM4a(tmpFile)
                        M4aEncoder.isTranscodable(sourceMime) -> m4aFrom(tmpFile)
                        else -> null
                    }
                }
            }
        }

        if (finalAudio == null) {
            Logger.w(TAG, "downloadToFile: could not produce ${format.name} output from $sourceContainer/$sourceMime")
            runCatching { tmpFile.delete() }
            lastDownloadError = "Audio conversion failed"
            return TrackResult.Failed(effectiveTitle, effectiveArtist, effectiveAlbum)
        }

        val finalFile = finalAudio.file

        val tagFormat = when {
            finalAudio.ext == "mp3" -> DownloadFormat.MP3
            finalAudio.ext == "m4a" -> DownloadFormat.M4A
            else -> null
        }

        if (writeTags && tagFormat != null) {
            progress(99, "Writing tags...")
            Tags.writeTags(
                finalFile,
                tagFormat,
                effectiveTitle,
                effectiveArtist,
                effectiveAlbum,
                fetchCoverForTags(context, trackId, track.cover ?: resolved.chosen.thumbnail)
            )
        }

        val uri = saveToDestination(
            context,
            trackId,
            effectiveTitle,
            effectiveArtist,
            finalFile,
            finalAudio.ext,
            finalAudio.mime
        )
        finalFile.delete()
        if (uri != null) {
            return TrackResult.Saved(
                effectiveTitle,
                effectiveArtist,
                effectiveAlbum,
                yt = YtMeta(
                    videoId = resolved.chosen.id,
                    ytTitle = resolved.chosen.title,
                    ytArtist = resolved.chosen.artists.joinToString(", ") { it.name },
                    ytAlbum = resolved.chosen.album?.name.orEmpty(),
                    ytThumbnail = resolved.chosen.thumbnail,
                    durationSec = resolved.chosen.duration,
                    explicit = resolved.chosen.explicit,
                    shareLink = resolved.chosen.shareLink,
                ),
            )
        }
        Logger.w(TAG, "downloadToFile: MediaStore save failed")
        lastDownloadError = "Couldn't save file"
        return TrackResult.Failed(effectiveTitle, effectiveArtist, effectiveAlbum)
    }

    private suspend fun resolveStream(
        context: Context,
        trackId: String,
        title: String,
        artist: String,
        album: String,
        preferredMimeType: String? = null,
    ): ResolvedStream? {
        val searchText = buildString {
            append(title)
            if (artist.isNotBlank()) append(" $artist")
        }

        val searchResult = runCatching {
            YouTube.search(searchText, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        }.onFailure { Logger.e(TAG, "resolveStream: search failed: ${it.message}", it) }
            .getOrNull()
        if (searchResult == null || searchResult.items.isEmpty()) {
            Logger.w(TAG, "resolveStream: no results for '$searchText'")
            return null
        }

        val songItems = searchResult.items.filterIsInstance<SongItem>()
        if (songItems.isEmpty()) {
            Logger.w(TAG, "resolveStream: no song items for '$searchText'")
            return null
        }

        val metadata = CandidateScorer.TrackMatchMetadata(
            title = title,
            artist = artist,
            album = album,
        )
        val scored = songItems.mapNotNull { song ->
            CandidateScorer.ytmusicTransferScore(song, metadata, expectedDurationMs = 0)
                .takeIf { it.isAcceptableMatch() }
        }.sortedByDescending { it.score }

        val chosen = scored.firstOrNull()?.item ?: run {
            Logger.w(TAG, "resolveStream: no acceptable match for '$searchText'")
            return null
        }

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val playback = runCatching {
            YTPlayerUtils.playerResponseForPlayback(
                videoId = chosen.id,
                playlistId = null,
                audioQuality = AudioQuality.HIGH,
                connectivityManager = connectivityManager,
                skipValidation = true,
                preferredMimeType = preferredMimeType,
            ).getOrNull()
        }.onFailure { Logger.e(TAG, "resolveStream: playback resolve failed: ${it.message}", it) }
            .getOrNull()

        val data = playback ?: run {
            Logger.w(TAG, "resolveStream: no playback data for ${chosen.id}")
            return null
        }

        val streamUrl = data.streamUrl
        if (streamUrl.isBlank()) {
            Logger.w(TAG, "resolveStream: empty stream url for ${chosen.id}")
            return null
        }

        val mimeType = data.format.mimeType.substringBefore(';').trim()
        val container = when {
            mimeType.startsWith("audio/mp4") -> "m4a"
            mimeType.startsWith("audio/webm") -> "webm"
            else -> "m4a"
        }
        return ResolvedStream(container, mimeType, streamUrl, chosen)
    }

    @Volatile
    private var lastDownloadError: String? = null

    private fun httpDownloadRanged(url: String, tmpFile: java.io.File, onProgress: ((Int) -> Unit)? = null, shouldAbort: (() -> Boolean)? = null): Boolean {
        val chunk = 8L * 1024 * 1024
        var total = -1L
        var position = 0L
        return try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    if (shouldAbort?.invoke() == true) {
                        Logger.i(TAG, "httpDownloadRanged: aborted at $position bytes")
                        return false
                    }
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        if (shouldAbort?.invoke() == true) return false
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                Logger.e(TAG, "httpDownloadRanged: HTTP $code at $position (attempt $attempt)")
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    if (shouldAbort != null && shouldAbort()) {
                                        Logger.i(TAG, "httpDownloadRanged: aborted mid-chunk at $position")
                                        return false
                                    }
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        onProgress?.invoke(pct)
                                    }
                                }
                            }
                            break
                        } catch (e: Exception) {
                            if (shouldAbort?.invoke() == true) return false
                            Logger.w(TAG, "httpDownloadRanged: chunk @$position failed attempt $attempt: ${e.message}")
                            if (attempt >= 4) {
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer
                }
            }
            val ok = total <= 0 || position >= total
            ok
        } catch (e: Exception) {
            Logger.e(TAG, "httpDownloadRanged: exception: ${e.message}", e)
            lastDownloadError = e.message ?: "Download error"
            false
        }
    }

    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
        }

    private fun fetchCoverForTags(context: Context, trackId: String, coverUrl: String?): File? {
        val cached = File(context.filesDir, "covers/$trackId.jpg")
        if (cached.exists() && cached.length() > 0) return cached
        if (coverUrl.isNullOrBlank()) return null
        return runCatching {
            cached.parentFile?.mkdirs()
            val conn = URL(coverUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            if (cached.length() > 0) cached else {
                cached.delete()
                null
            }
        }.getOrElse {
            Logger.w(TAG, "fetchCoverForTags: failed: ${it.message}")
            runCatching { cached.delete() }
            null
        }
    }

    private fun saveToDestination(
        context: Context,
        trackId: String,
        title: String,
        artist: String,
        tmpFile: java.io.File,
        ext: String,
        mime: String,
    ): String? {
        val folderName = DownloadPrefs.subfolder(context)
        val fileName = "$artist - $title [$trackId]"
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .let { if (it.length > 200) it.take(200) else it }

        val picked = DownloadPrefs.folder(context)
        if (picked != null && DownloadFolder.hasAccess(context, picked)) {
            val uri = DownloadFolder.create(context, picked, "$fileName.$ext", mime, tmpFile)
            if (uri != null) return uri.toString()
            Logger.w(TAG, "saveToDestination: picked folder failed, falling back to Music/$folderName")
        } else if (picked != null) {
            Logger.w(TAG, "saveToDestination: no access to the picked folder, using Music/$folderName")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val displayName = "$fileName.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$folderName")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                Logger.w(TAG, "saveToDestination: MediaStore insert returned null")
                return null
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tmpFile.inputStream().use { it.copyTo(out) }
                } ?: run {
                    Logger.w(TAG, "saveToDestination: openOutputStream returned null")
                    context.contentResolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Logger.w(TAG, "saveToDestination: MediaStore write failed: ${e.message}")
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            }
            return uri.toString()
        } else {
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                folderName,
            ).apply { mkdirs() }
            val outFile = java.io.File(dir, "$fileName.$ext")
            if (!tmpFile.renameTo(outFile)) {
                Logger.w(TAG, "saveToDestination: rename to public dir failed (API < 29)")
                return null
            }
            return outFile.absolutePath
        }
    }
}