package com.music.spotui.data.entity

/**
 * Personalized Spotify home feed (the `home` GQL operation): a greeting plus an
 * ordered list of titled sections ("Your top mixes", "Jump back in", "Your
 * favorite artists", …), each holding albums / artists / playlists.
 */
@kotlinx.serialization.Serializable
data class HomeFeedModel(
    val greeting: String = "",
    val sections: List<HomeSection> = emptyList(),
)

@kotlinx.serialization.Serializable
data class HomeSection(
    val title: String,
    val items: List<HomeItem>,
)

@kotlinx.serialization.Serializable
sealed class HomeItem {
    abstract val name: String
    abstract val imageUrl: String

    @kotlinx.serialization.Serializable
    data class Album(
        val id: String = "",
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val artists: String = "",
    ) : HomeItem()

    @kotlinx.serialization.Serializable
    data class Artist(
        override val name: String,
        override val imageUrl: String,
        val id: String = "",
    ) : HomeItem()

    /** Podcast show / audiobook, or a single episode ([isEpisode]). */
    @kotlinx.serialization.Serializable
    data class Show(
        val id: String,
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val isEpisode: Boolean,
    ) : HomeItem()

    @kotlinx.serialization.Serializable
    data class Playlist(
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val id: String = "",
    ) : HomeItem()
}
