package com.music.spotui.di

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.music.spotui.data.entity.SongsModel

class CurrentSongState constructor() {
    private val _title: MutableState<String> = mutableStateOf("")
    val title: State<String> get() = _title

    private val _album: MutableState<String> = mutableStateOf("")
    val album : State<String> get() = _album

    private val _singer: MutableState<String> = mutableStateOf("")
    val singer: State<String> get() = _singer

    private val _coverUri: MutableState<String> = mutableStateOf("")
    val coverUri: State<String> get() = _coverUri

    private val _playingState: MutableState<Boolean> = mutableStateOf(false)
    val playingState: State<Boolean> get() = _playingState

    private val _songIndex: MutableState<Int> = mutableStateOf(0)
    val songIndex : State<Int> get() = _songIndex

    private val _songId: MutableState<Int> = mutableStateOf(0)
    val songId : State<Int> get() = _songId

    // The actual list the user is playing (album tracks, search results, liked
    // songs…). Next/previous operate on THIS, not on a re-derived global feed.
    private val _queue: MutableState<List<SongsModel>> = mutableStateOf(emptyList())
    val queue: State<List<SongsModel>> get() = _queue

    fun updateQueue(queue: List<SongsModel>) {
        // Playback finds the current track by id, and the queue screen keys rows by
        // id, so a track listed twice (e.g. in a playlist) is queued once.
        val songs = queue.distinctBy { it.id }
        _queue.value = songs
    }

    val shuffle = mutableStateOf(false)
    val repeat = mutableStateOf(false)
    val likeState = mutableStateOf(false)

    // Original queue order, kept while shuffle is on so turning it off restores
    // the list instead of leaving it permanently scrambled.
    private var unshuffledQueue: List<SongsModel>? = null

    /**
     * Toggling shuffle reorders the queue ONCE: the current track stays where it
     * is and the rest follow in random order. (Skipping used to re-shuffle the
     * whole list on every tap, which could repeat or skip songs.)
     */
    fun updateShuffleState(newShuffleState: Boolean) {
        if (newShuffleState == shuffle.value) return
        shuffle.value = newShuffleState
        val q = _queue.value
        if (newShuffleState) {
            unshuffledQueue = q
            val curIdx = q.indexOfFirst { it.id == _songId.value }
            if (curIdx >= 0) {
                _queue.value = listOf(q[curIdx]) +
                    q.filterIndexed { i, _ -> i != curIdx }.shuffled()
                _songIndex.value = 0
            } else {
                _queue.value = q.shuffled()
            }
        } else {
            val original = unshuffledQueue
            unshuffledQueue = null
            // Restore only if we're still inside that queue (it may have been
            // replaced by another list while shuffled). Keep tracks appended in
            // the meantime (queue edits, autoplay radio).
            if (original != null && original.any { it.id == _songId.value }) {
                val appended = q.filter { s -> original.none { it.id == s.id } }
                val restored = original + appended
                _queue.value = restored
                val idx = restored.indexOfFirst { it.id == _songId.value }
                if (idx >= 0) _songIndex.value = idx
            }
        }
    }

    /**
     * Starts shuffled playback of a full list (the shuffle button on
     * playlist/album/liked screens): the queue becomes a shuffled copy, shuffle
     * turns on, and the caller plays the returned first track.
     */
    fun startShuffled(songs: List<SongsModel>): SongsModel? {
        if (songs.isEmpty()) return null
        // Spotify shuffles its own context and picks the first track itself; the
        // now-playing state follows the engine (no guessed track shown meanwhile).
        shuffle.value = true
        updateQueue(songs)
        SongPlayer.shufflePlay(songs)
        return null
    }
    fun updateRepeatState(newRepeatState : Boolean){
        repeat.value = newRepeatState
    }

    /** Sync the play/pause state without touching the rest of the now-playing
     *  metadata — used to reflect the web player's real state (e.g. after the
     *  system notification's pause button) back into the in-app UI. */
    fun updatePlayingState(playing: Boolean) {
        _playingState.value = playing
    }

    fun updateLikeState(newLikeState : Boolean){
        likeState.value = newLikeState
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int, album : String) {
        // The engine re-reports the same track on every state change (pause, like…);
        // only a new track should be persisted / logged / prefetched.
        val newTrack = songId != _songId.value || title != _title.value
        _coverUri.value = coverUri
        _title.value = title
        _album.value = album
        _singer.value = singer
        _playingState.value = playingState
        _songIndex.value = songIndex
        _songId.value = songId
        // Persist the current track so a fresh launch can restore the session.
        if (newTrack && playingState && title.isNotBlank()) {
            _queue.value.firstOrNull { it.id == songId }?.let { track ->
                com.music.spotui.data.preferences.saveLastPlayback(
                    com.music.spotui.di.Graph.app, track)
            }
        }
    }
}
