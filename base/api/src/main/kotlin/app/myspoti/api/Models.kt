package app.myspoti.api

/** A Spotify-style uri such as `spotify:track:{id}`. Kept as a string so every kind fits. */
typealias Uri = String

data class Image(val url: String, val width: Int? = null, val height: Int? = null)

/** Picks the smallest image at least [minPx] wide, or the largest one. */
fun List<Image>.best(minPx: Int): Image? =
    filter { (it.width ?: 0) >= minPx }.minByOrNull { it.width ?: 0 } ?: maxByOrNull { it.width ?: 0 }

data class ArtistRef(val uri: Uri, val name: String)

data class AlbumRef(val uri: Uri, val name: String, val images: List<Image> = emptyList())

data class Track(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val explicit: Boolean = false,
    val playable: Boolean = true,
    /** Position in a playlist; needed to remove or move this exact row. */
    val uid: String? = null,
    /** When it was added to the playlist (ISO 8601), for playlist rows. */
    val addedAt: String? = null,
)

data class Album(
    val uri: Uri,
    val name: String,
    val artists: List<ArtistRef>,
    val images: List<Image>,
    val type: String,
    val releaseDate: String?,
    val trackCount: Int,
    val label: String? = null,
)

data class Artist(
    val uri: Uri,
    val name: String,
    val images: List<Image>,
    val followers: Long? = null,
    val monthlyListeners: Long? = null,
    val verified: Boolean = false,
)

data class Playlist(
    val uri: Uri,
    val name: String,
    val description: String?,
    val owner: String?,
    val images: List<Image>,
    val trackCount: Int,
    val editable: Boolean = false,
    val ownerUri: Uri? = null,
    val ownerImages: List<Image> = emptyList(),
    val followers: Long? = null,
)

/**
 * A playlist's base permission, as the Web Player sets it: anyone may view it (public),
 * nobody but the owner (private, "BLOCKED"), or anyone may add and remove tracks.
 */
enum class PlaylistAccess(val permission: String) {
    PUBLIC("VIEWER"), PRIVATE("BLOCKED"), COLLABORATIVE("CONTRIBUTOR");
    companion object { fun of(permission: String?) = entries.firstOrNull { it.permission == permission } ?: PUBLIC }
}

/** Client-side orders for a playlist's rows. [CUSTOM] is the playlist's own order. */
enum class PlaylistSort { CUSTOM, TITLE, ARTIST, ALBUM, ADDED_AT, DURATION }

/**
 * Sorts playlist rows the way the Web Player's sort menu does. The server has no sort
 * parameter, so this runs on rows already loaded. Ties keep the playlist's own order.
 */
fun List<Track>.sortedFor(sort: PlaylistSort, descending: Boolean = false): List<Track> {
    val order: Comparator<Track> = when (sort) {
        PlaylistSort.CUSTOM -> return if (descending) asReversed() else this
        PlaylistSort.TITLE -> compareBy<Track> { it.name.lowercase() }
        PlaylistSort.ARTIST -> compareBy<Track> { it.artists.firstOrNull()?.name?.lowercase() ?: "" }
        PlaylistSort.ALBUM -> compareBy<Track> { it.album?.name?.lowercase() ?: "" }
        PlaylistSort.ADDED_AT -> compareBy<Track> { it.addedAt ?: "" }
        PlaylistSort.DURATION -> compareBy<Track> { it.durationMs }
    }
    return sortedWith(if (descending) order.reversed() else order)
}

/** One page of a longer list. [nextOffset] is null on the last page. */
data class Page<T>(val items: List<T>, val offset: Int, val total: Int, val nextOffset: Int?)

data class SearchResults(
    val tracks: Page<Track>,
    val artists: Page<Artist>,
    val albums: Page<Album>,
    val playlists: Page<Playlist>,
)

enum class Repeat { OFF, CONTEXT, TRACK }

enum class ItemKind { TRACK, EPISODE, AD }

/** What the player is on now. Ads never reach the UI as the current item. */
data class NowPlaying(
    val kind: ItemKind,
    val uri: Uri,
    val uid: String?,
    val name: String,
    val artists: List<ArtistRef>,
    val album: AlbumRef?,
    val imageUrl: String?,
    val durationMs: Long,
    val explicit: Boolean = false,
    /** Where the player took this item from ("context", "queue", "autoplay"...), when it says. */
    val provider: String? = null,
    /**
     * The item's media kind as the page reports it ("audio", "video"). A **video** ad never
     * reports a position or a speed the way an audio one does, so the recovery has to treat it as
     * playing (see the ad branch of the stall watcher) instead of waiting for a state that never
     * comes.
     */
    val mediaType: String? = null,
)

data class PlayerRestrictions(
    val canPause: Boolean = true,
    val canResume: Boolean = true,
    val canSeek: Boolean = true,
    val canSkipNext: Boolean = true,
    val canSkipPrevious: Boolean = true,
)

data class PlayerState(
    val item: NowPlaying?,
    val contextUri: Uri?,
    /** "Liked Songs", the playlist or album name... */
    val contextName: String? = null,
    val previous: NowPlaying? = null,
    val next: NowPlaying? = null,
    val isPaused: Boolean,
    val isBuffering: Boolean,
    /** Position at [timestampMs] (epoch). Use [positionAt] for a live value. */
    val positionMs: Long,
    val timestampMs: Long,
    val speed: Double,
    val shuffle: Boolean,
    val repeat: Repeat,
    val restrictions: PlayerRestrictions,
) {
    fun positionAt(nowMs: Long): Long =
        if (isPaused || item == null) positionMs
        else (positionMs + (nowMs - timestampMs) * speed).toLong().coerceIn(0, item.durationMs)
}

data class Queue(val current: NowPlaying?, val queued: List<NowPlaying>, val nextUp: List<NowPlaying>)

data class TopTrack(val track: Track, val playcount: Long?)

/** Everything the artist screen shows, in one call. */
data class ArtistPage(
    val artist: Artist,
    val biography: String?,
    val headerImages: List<Image>,
    val topTracks: List<TopTrack>,
    val popularReleases: List<Album>,
    val appearsOn: List<Album>,
    val related: List<Artist>,
    /** "Featuring": Spotify's own playlists around the artist (This Is, the artist's radio). */
    val featuring: List<Playlist> = emptyList(),
    /** "Discovered on": popular playlists the artist's listeners found them on. */
    val discoveredOn: List<Playlist> = emptyList(),
    /** The artist's own playlists. */
    val playlists: List<Playlist> = emptyList(),
)
