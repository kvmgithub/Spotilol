package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CategoryViewModel constructor(private val repository: AppRepository = Graph.repository) : ViewModel() {

    private val _playlists: MutableStateFlow<Response<List<LibraryEntry>>> = MutableStateFlow(Response.Loading())
    val playlists: StateFlow<Response<List<LibraryEntry>>> = _playlists

    private var genreKey: String? = null

    fun load(genre: String) {
        if (genreKey == genre) return
        genreKey = genre
        viewModelScope.launch(Dispatchers.IO) {
            repository.provideCategoryPlaylists(genre).collect { _playlists.value = it }
        }
    }
}
