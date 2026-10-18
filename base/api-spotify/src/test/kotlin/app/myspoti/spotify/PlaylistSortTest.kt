package app.myspoti.spotify

import app.myspoti.api.AlbumRef
import app.myspoti.api.ArtistRef
import app.myspoti.api.PlaylistSort
import app.myspoti.api.Track
import app.myspoti.api.sortedFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Client-side playlist sort: the server has no sort parameter, so these rules are the whole behaviour. */
class PlaylistSortTest {
    private fun track(name: String, artist: String, album: String, ms: Long, added: String?) = Track(
        uri = "spotify:track:$name",
        name = name,
        durationMs = ms,
        artists = listOf(ArtistRef("spotify:artist:$artist", artist)),
        album = AlbumRef("spotify:album:$album", album),
        addedAt = added,
    )

    private val rows = listOf(
        track("b-song", "Zed", "Gold", 200_000, "2026-03-01T10:00:00Z"),
        track("A-song", "amy", "Blue", 180_000, null),
        track("c-song", "Bob", "Gold", 200_000, "2026-01-05T08:00:00Z"),
    )

    private fun List<Track>.names() = map { it.name }

    @Test fun `custom keeps the playlist order, reversed for descending`() {
        assertEquals(listOf("b-song", "A-song", "c-song"), rows.sortedFor(PlaylistSort.CUSTOM).names())
        assertEquals(listOf("c-song", "A-song", "b-song"), rows.sortedFor(PlaylistSort.CUSTOM, descending = true).names())
    }

    @Test fun `title ignores case`() {
        assertEquals(listOf("A-song", "b-song", "c-song"), rows.sortedFor(PlaylistSort.TITLE).names())
        assertEquals(listOf("c-song", "b-song", "A-song"), rows.sortedFor(PlaylistSort.TITLE, descending = true).names())
    }

    @Test fun `artist uses the first artist, ignoring case`() {
        assertEquals(listOf("A-song", "c-song", "b-song"), rows.sortedFor(PlaylistSort.ARTIST).names())
    }

    @Test fun `album, and ties keep the playlist order in both directions`() {
        assertEquals(listOf("A-song", "b-song", "c-song"), rows.sortedFor(PlaylistSort.ALBUM).names())
        assertEquals(listOf("A-song", "b-song", "c-song"), rows.sortedFor(PlaylistSort.DURATION).names())
        assertEquals(listOf("b-song", "c-song", "A-song"), rows.sortedFor(PlaylistSort.DURATION, descending = true).names())
    }

    @Test fun `date added puts rows without a date first`() {
        assertEquals(listOf("A-song", "c-song", "b-song"), rows.sortedFor(PlaylistSort.ADDED_AT).names())
        assertEquals(listOf("b-song", "c-song", "A-song"), rows.sortedFor(PlaylistSort.ADDED_AT, descending = true).names())
    }
}
