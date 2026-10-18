package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerRestrictions
import app.myspoti.api.PlayerState
import app.myspoti.api.Repeat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StallTest {
    private val song = NowPlaying(kind = ItemKind.TRACK, uri = "spotify:track:a", uid = null, name = "A", artists = emptyList(), album = null, imageUrl = null, durationMs = 200_000)
    private val after = song.copy(uri = "spotify:track:b", name = "B")
    private fun state(pos: Long, at: Long, paused: Boolean = false, buffering: Boolean = false, next: NowPlaying? = after) =
        PlayerState(item = song, contextUri = "spotify:playlist:x", next = next, isPaused = paused, isBuffering = buffering,
            positionMs = pos, timestampMs = at, speed = 1.0, shuffle = false, repeat = Repeat.OFF, restrictions = PlayerRestrictions())

    @Test fun `a playing song is fine`() = assertNull(SpotifyPlayer.stallOf(state(100_000, 0), 50_000, false))
    @Test fun `clock past the end`() = assertEquals("past the end", SpotifyPlayer.stallOf(state(190_000, 0), 20_000, false))
    @Test fun `paused by itself at the end`() = assertEquals("paused at the end", SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, false))
    @Test fun `paused by the user at the end`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 60_000, true))
    @Test fun `last song of the list`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true, next = null), 60_000, false))
    @Test fun `a user pause at the end never expires`() = assertNull(SpotifyPlayer.stallOf(state(199_500, 0, paused = true), 600_000, true))
    @Test fun `paused mid-song`() = assertNull(SpotifyPlayer.stallOf(state(50_000, 0, paused = true), 60_000, false))
    @Test fun `buffering for long`() = assertEquals("buffering", SpotifyPlayer.stallOf(state(0, 0, buffering = true), 20_000, false))
    @Test fun `buffering a moment`() = assertNull(SpotifyPlayer.stallOf(state(0, 0, buffering = true), 5_000, false))

    @Test fun `a stuck ad is skipped twice, then the page reloads`() {
        assertEquals("next", SpotifyPlayer.adRecovery(1))
        assertEquals("next", SpotifyPlayer.adRecovery(2))
        assertEquals("reload", SpotifyPlayer.adRecovery(3))
    }

    // Chromium pauses a hidden page's media that (re)starts at volume 0.
    @Test fun `a fade never reaches volume 0`() = SpotifyPlayer.FADE_STEPS.forEach { assertEquals(true, it > 0.0) }
}
