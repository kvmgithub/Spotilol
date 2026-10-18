package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel constructor(private val repository: AppRepository = Graph.repository)  : ViewModel() {

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    /** Selected home chip ("" = All). */
    val facet = androidx.compose.runtime.mutableStateOf("")
    private var homeJob: kotlinx.coroutines.Job? = null

    init {
        fetchHome()
        // A Home that failed (player still signing in, network back) loads again by itself:
        // when the player is ready again, then a few times with growing pauses.
        viewModelScope.launch {
            app.myspoti.host.android.MyspotiRuntime.api.ready.collect { ready ->
                if (ready && _home.value is Response.Error) fetchHome()
            }
        }
        viewModelScope.launch {
            var tries = 0
            _home.collect { state ->
                if (state is Response.Success) tries = 0
                if (state is Response.Error && tries < RETRY_DELAYS_MS.size) {
                    kotlinx.coroutines.delay(RETRY_DELAYS_MS[tries++])
                    if (_home.value is Response.Error) fetchHome()
                }
            }
        }
    }

    /** Tries again now (the Home error's button). */
    fun retry() {
        _home.value = Response.Loading()
        fetchHome()
    }

    fun selectFacet(f: String) {
        facet.value = if (facet.value == f) "" else f
        fetchHome()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)
    }

    private fun fetchHome() {
        homeJob?.cancel()
        val f = facet.value
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideHomeFeed(f).collect { feed ->
                _home.value = feed
            }
        }
    }
}
