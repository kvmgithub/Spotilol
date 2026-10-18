package app.myspoti.host.cdp

import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** A sorted playlist plays in the sorted order, next song included. */
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LiveOrderTest {
    @Test
    fun `plays a playlist in a given order`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        val api = SpotifyApi(host, scope, language = "fr")
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        val list = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val songs = api.playlists.tracks(list, 0, 10).items.map { it.uri }
        val order = songs.reversed()
        try {
            api.player.play(list, trackUri = order[0], order = order)
            val s = withTimeout(30_000) { api.player.state.first { it?.item?.uri == order[0] && !it.isPaused } }!!
            println("now ${s.item?.name}, next ${s.next?.name}, context ${s.contextUri}")
            assertEquals(order[1], s.next?.uri)
            api.player.next()
            val t = withTimeout(30_000) { api.player.state.first { it?.item?.uri != order[0] && it?.item != null } }!!
            println("after next: ${t.item?.name}")
            assertEquals(order[1], t.item?.uri)
        } finally {
            runCatching { api.player.pause() }
        }
    }

    /** Smart shuffle: a recommended song (not in the playlist) plays inside its order. */
    @Test
    fun `plays recommendations inside a playlist order`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        val api = SpotifyApi(host, scope, language = "fr")
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        val list = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        val songs = api.playlists.tracks(list, 0, 5).items.map { it.uri }
        val rec = api.playlists.recommended(list, 5).first { it.uri !in songs }.uri
        val order = listOf(songs[0], rec, songs[1])
        try {
            api.player.play(list, trackUri = songs[0], order = order)
            withTimeout(30_000) { api.player.state.first { it?.item?.uri == songs[0] && !it.isPaused } }
            api.player.next()
            val t = withTimeout(30_000) { api.player.state.first { it?.item != null && it.item!!.uri != songs[0] } }!!
            println("recommended: ${t.item?.name} context ${t.contextUri}")
            assertEquals(rec, t.item?.uri)
        } finally {
            runCatching { api.player.pause() }
        }
    }

    /** Smart shuffle on Liked Songs: other songs play inside the Liked Songs order. */
    @Test
    fun `plays other songs inside liked songs`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        val api = SpotifyApi(host, scope, language = "fr")
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        val liked = api.library.likedTracks(0, 5).items.map { it.track.uri }
        val other = api.playlists.tracks("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", 0, 5).items.map { it.uri }.first { it !in liked }
        val order = listOf(liked[0], other) + liked.drop(1)
        try {
            api.player.play("spotify:collection:tracks", trackUri = liked[0], order = order)
            withTimeout(30_000) { api.player.state.first { it?.item?.uri == liked[0] && !it.isPaused } }
            api.player.next()
            val t = withTimeout(30_000) { api.player.state.first { it?.item != null && it.item!!.uri != liked[0] } }!!
            println("liked then: ${t.item?.name} context ${t.contextUri}")
            assertEquals(other, t.item?.uri)
        } finally {
            runCatching { api.player.pause() }
        }
    }
}
