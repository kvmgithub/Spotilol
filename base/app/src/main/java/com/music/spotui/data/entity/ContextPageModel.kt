package com.music.spotui.data.entity

/** Everything the album / playlist page shows, loaded by Spotify id. */
@kotlinx.serialization.Serializable
data class ContextPageModel(
    val uri: String,
    val type: String, // "album" | "playlist"
    val name: String,
    val coverUri: String,
    val description: String = "",
    val ownerName: String = "",
    val ownerAvatar: String = "",
    val ownerUri: String = "",
    val metaLine: String = "",
    val explicit: Boolean = false,
    val tracks: List<SongsModel> = emptyList(),
    val complete: Boolean = true,
    /** Loading ended with a page missing: [tracks] is the start of the list, not all of it. */
    val partial: Boolean = false,
    /** A Spotify Blend: its page shows the members and the taste match. */
    val isBlend: Boolean = false,
    /** A member who is not the owner and may leave (`canCancelMembership`). */
    val canLeave: Boolean = false,
) {
    /** Every song of the list is here: loaded to its end, no page missing (kept, queued, dealt as a whole). */
    val whole: Boolean get() = complete && !partial
}
