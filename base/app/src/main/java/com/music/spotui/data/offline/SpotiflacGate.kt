package com.music.spotui.data.offline

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.DownloadQuality
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Downloads asked for while no SpotiFLAC server is verified: they wait here, the app opens
 * the verification ([open]), and they start when the user leaves it, verified or not.
 */
object SpotiflacGate {
    private val _open = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The navigation opens the verification page on each emission. */
    val open: SharedFlow<Unit> = _open

    @Volatile private var pending: Pair<List<SongsModel>, DownloadQuality?>? = null

    fun hold(songs: List<SongsModel>, quality: DownloadQuality?) {
        val before = pending?.first.orEmpty()
        pending = (before + songs).distinctBy { it.spotifyTrackId } to (quality ?: pending?.second)
        _open.tryEmit(Unit)
    }

    /** The verification page closed: what waited is downloaded now. */
    fun resume() {
        val (songs, quality) = pending ?: return
        pending = null
        OfflineDownloader.enqueue(songs, quality, verifiedOrSkipped = true)
    }
}
