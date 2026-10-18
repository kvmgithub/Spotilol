package app.myspoti.host.cdp

import app.myspoti.api.ItemKind
import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.math.abs

/**
 * The whole API against a real, signed-in Web Player in desktop Chrome.
 *
 *     google-chrome --user-data-dir=<profile> --remote-debugging-port=9222   # sign in once
 *     ./gradlew :host-cdp:test -Pcdp=http://localhost:9222
 *
 * Every test restores what it changes (volume, queue, likes, pause).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LiveApiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var host: CdpHost
    private lateinit var api: SpotifyApi

    private fun live(block: suspend () -> Any?) { runBlocking { withTimeout(90_000) { block() } } }

    private suspend fun <T> eventually(timeoutMs: Long = 15_000, check: suspend () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            check()?.let { return it }
            delay(300)
        }
        error("condition not reached in $timeoutMs ms")
    }

    @BeforeAll
    fun connect() = live {
        host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        api = SpotifyApi(host, scope, language = "fr")
        host.reload()
        host.awaitLoad()
        api.onPageLoaded()
        api.awaitReady()
    }

    @AfterAll
    fun close() {
        runBlocking { runCatching { api.player.pause() } }
        host.close()
        scope.cancel()
    }

    @Test @Order(1)
    fun `search returns every kind in one round trip`() = live {
        val t0 = System.currentTimeMillis()
        val r = api.search.all("stromae", limit = 5)
        val ms = System.currentTimeMillis() - t0
        println("search.all: $ms ms")
        assertEquals(5, r.tracks.items.size)
        assertTrue(r.tracks.items.all { it.uri.startsWith("spotify:track:") && it.durationMs > 0 && it.artists.isNotEmpty() })
        println("artists: " + r.artists.items.map { it.name })
        assertTrue(r.artists.items.first().name.contains("Stromae", ignoreCase = true))
        assertTrue(r.albums.items.isNotEmpty() && r.playlists.items.isNotEmpty())
        assertTrue(r.tracks.total > 5 && r.tracks.nextOffset != null)
        assertTrue(api.search.suggestions("stro").isNotEmpty())
        assertEquals(0, api.httpGql.fellBack.get(), "reads must use the direct HTTP path")
        assertTrue(api.httpGql.direct.get() >= 5)
    }

    @Test @Order(2)
    fun `catalog reads track, album and artist`() = live {
        val hit = api.search.tracks("papaoutai", limit = 1).items.single()
        val track = api.catalog.track(hit.uri)
        assertEquals(hit.uri, track.uri)
        val album = api.catalog.album(track.album!!.uri)
        assertTrue(album.trackCount > 0 && album.images.isNotEmpty())
        val tracks = api.catalog.albumTracks(album.uri)
        assertEquals(album.trackCount.coerceAtMost(50), tracks.items.size)
        val artist = api.catalog.artist(track.artists.first().uri)
        assertTrue(artist.name.isNotBlank() && (artist.monthlyListeners ?: 0) > 0)
        assertTrue(api.catalog.artistTopTracks(artist.uri).isNotEmpty())
    }

    @Test @Order(3)
    fun `playlists page through their tracks`() = live {
        val pl = api.playlists.playlist(TOP_HITS)
        assertTrue(pl.name.isNotBlank() && pl.trackCount >= 50)
        val first = api.playlists.tracks(TOP_HITS, 0, 30)
        val second = api.playlists.tracks(TOP_HITS, 30, 30)
        assertEquals(30, first.items.size)
        assertTrue(second.items.isNotEmpty() && second.items.first().uri != first.items.first().uri)
        assertTrue(first.items.all { it.uid != null })
        api.playlists.mine() // the test account may have none; it must not fail
    }

    @Test @Order(4)
    fun `library toggles a like and restores it`() = live {
        val uri = api.playlists.tracks(TOP_HITS, 10, 1).items.single().uri
        val was = api.library.contains(listOf(uri)).single()
        if (was) api.library.remove(listOf(uri)) else api.library.add(listOf(uri))
        eventually { api.library.contains(listOf(uri)).single().takeIf { it != was } }
        if (was) api.library.add(listOf(uri)) else api.library.remove(listOf(uri))
        eventually { api.library.contains(listOf(uri)).single().takeIf { it == was } }
    }

    @Test @Order(5)
    fun `player plays, pauses, seeks and skips`() = live {
        api.player.play(TOP_HITS, index = 0)
        val playing = eventually { api.player.state.value?.takeIf { it.contextUri == TOP_HITS && !it.isPaused && it.item != null } }
        assertEquals(ItemKind.TRACK, playing.item!!.kind)
        delay(3_000)
        assertTrue(api.player.state.value!!.positionAt(System.currentTimeMillis()) > 1_000)

        api.player.pause()
        eventually { api.player.state.value?.takeIf { it.isPaused } }
        api.player.resume()
        eventually { api.player.state.value?.takeIf { !it.isPaused } }

        val target = playing.item!!.durationMs / 2
        api.player.seek(target)
        eventually { api.player.state.value?.takeIf { abs(it.positionAt(System.currentTimeMillis()) - target) < 3_000 } }

        val first = playing.item!!.uri
        api.player.next()
        eventually { api.player.state.value?.takeIf { it.item?.uri != first } }
        api.player.previous()
        delay(1_000)
        if (api.player.state.value?.item?.uri != first) {
            eventually { api.player.state.value?.takeIf { it.restrictions.canSkipPrevious } }
            api.player.previous()
        }
        eventually { api.player.state.value?.takeIf { it.item?.uri == first } }
    }

    @Test @Order(6)
    fun `queue adds and removes a track`() = live {
        val candidate = eventually { api.player.queue.value?.nextUp?.getOrNull(3) }
        api.player.addToQueue(listOf(candidate.uri))
        val queued = eventually { api.player.queue.value?.queued?.filter { it.uri == candidate.uri }?.takeIf { it.isNotEmpty() } }
        api.player.removeFromQueue(queued)
        eventually { api.player.queue.value?.queued?.takeIf { q -> q.none { it.uri == candidate.uri } } }
    }

    @Test @Order(7)
    fun `volume is set and restored`() = live {
        val old = api.player.volume()
        api.player.setVolume(0.3)
        eventually { api.player.volume().takeIf { abs(it - 0.3) < 0.02 } }
        api.player.setVolume(old)
        assertNotEquals(-1.0, old)
    }

    companion object {
        const val TOP_HITS = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
    }
}
