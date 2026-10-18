package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ShowViewModel constructor(
    private val repository: AppRepository = Graph.repository,
    private val currentSongState: CurrentSongState = Graph.currentSongState,
) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private val _episodes: MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val episodes: StateFlow<Response<List<SongsModel>>> = _episodes

    private val _show: MutableStateFlow<PodcastModel?> = MutableStateFlow(null)
    val show: StateFlow<PodcastModel?> = _show

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    private var showKey: String? = null

    fun loadShow(showId: String) {
        if (showKey == showId) return
        showKey = showId
        viewModelScope.launch(Dispatchers.IO) {
            _show.value = repository.provideShow(showId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideShowEpisodes(showId).collect { _episodes.value = it }
        }
    }
}
