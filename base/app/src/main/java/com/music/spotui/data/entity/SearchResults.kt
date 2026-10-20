package com.music.spotui.data.entity

/** Combined Spotify search results: tracks, albums, artists, podcasts + episodes. */
data class SearchResults(
    val songs: List<SongsModel> = emptyList(),
    val albums: List<AlbumsModel> = emptyList(),
    val artists: List<ArtistsModel> = emptyList(),
    val shows: List<PodcastModel> = emptyList(),
    val episodes: List<SongsModel> = emptyList(),
    val playlists: List<LibraryEntry> = emptyList(),
    /** "Featuring <artist>" when the query names an artist: This Is, the artist's radio. */
    val featured: List<LibraryEntry> = emptyList(),
)
