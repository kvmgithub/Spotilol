package com.music.spotui.data.entity

/**
 * A show page as the screen shows it: the header plus the rows.
 *
 * A podcast has episodes; a book has chapters, fetched through another Spotify operation
 * ([audiobook] = true). Both arrive as [SongsModel] rows so they play through the same
 * queue as an episode (url = "episode:<raw id>").
 */
data class ShowPage(
    val show: PodcastModel,
    val items: List<SongsModel> = emptyList(),
    val audiobook: Boolean = false,
    /** Spotify's cover for the page: the show's, or the first item's when the header failed. */
    val coverUri: String = "",
) {
    val complete: Boolean get() = items.isNotEmpty()
}
