package app.myspoti.host.cdp

import app.myspoti.api.HomeFilter
import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * The features the app's screens use beyond playback (profiles, recents, podcasts home,
 * artist ban, playlist visibility), against a signed-in Web Player. Writes are undone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LiveFeaturesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var host: CdpHost
    private lateinit var api: SpotifyApi

    private fun live(block: suspend () -> Any?) { runBlocking { withTimeout(120_000) { block() } } }

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
    fun close() { scope.cancel() }

    @Test fun podcastsHome() = live {
        val feed = api.home.feed(HomeFilter.PODCASTS, itemsPerShelf = 10)
        println("PODCASTS shelves=${feed.shelves.size} kinds=${feed.shelves.flatMap { it.items }.groupingBy { it.kind }.eachCount()}")
        feed.shelves.take(5).forEach { println("  ${it.title}: ${it.items.take(3).map { c -> c.kind.name + ":" + c.title }}") }
        assertTrue(feed.shelves.isNotEmpty())
    }

    @Test fun profilesAndRecents() = live {
        val me = api.users.me()
        println("me=${me.uri}")
        val p = api.users.profile(me.uri)
        println("profile ${p.name} followers=${p.followers} following=${p.following} playlists=${p.publicPlaylists.size}")
        val found = api.search.users("spotify", limit = 5)
        println("users: ${found.items.map { it.title + " " + it.uri }}")
        val follows = api.users.isFollowing(found.items.take(2).map { it.uri })
        println("isFollowing=$follows")
        val h = api.users.history()
        println("history ${h.size}: ${h.take(5).map { it.kind.name + ":" + it.name }}")
    }

    @Test fun artistBanRoundTrip() = live {
        val artist = "spotify:artist:0TnOYISbd1XYRBk9myaseg" // Pitbull
        val before = api.extras.isArtistBanned(listOf(artist)).single()
        api.extras.setArtistBanned(artist, !before)
        val flipped = api.extras.isArtistBanned(listOf(artist)).single()
        api.extras.setArtistBanned(artist, before)
        val back = api.extras.isArtistBanned(listOf(artist)).single()
        println("artistban before=$before flipped=$flipped back=$back")
        assertEquals(!before, flipped)
        assertEquals(before, back)
    }

    @Test fun playlistAccessAndCover() = live {
        val uri = api.playlists.create("Myspoti test ${System.currentTimeMillis() % 100000}")
        try {
            api.playlists.setAccess(uri, app.myspoti.api.PlaylistAccess.PRIVATE)
            assertEquals(app.myspoti.api.PlaylistAccess.PRIVATE, api.playlists.access(uri))
            api.playlists.setAccess(uri, app.myspoti.api.PlaylistAccess.COLLABORATIVE)
            assertEquals(app.myspoti.api.PlaylistAccess.COLLABORATIVE, api.playlists.access(uri))
            api.playlists.setAccess(uri, app.myspoti.api.PlaylistAccess.PUBLIC)
            val img = java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { color = java.awt.Color(29, 185, 84); fillRect(0, 0, 300, 300) }
            val jpeg = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "jpg", it) }.toByteArray()
            api.playlists.setCover(uri, jpeg)
            kotlinx.coroutines.delay(3000)
            println("cover images=${api.playlists.playlist(uri).images.size}")
            api.playlists.setDescription(uri, "test")
        } finally {
            api.playlists.delete(uri)
        }
    }

    @Test fun suggestionsAndSimilar() = live {
        println("suggestions=${api.search.suggestions("dua", 5)}")
        val t = api.search.tracks("Levitating", limit = 1).items.first()
        val sim = api.catalog.similarAlbums(t.uri)
        println("similar to ${t.name}: ${sim.take(3).map { it.name }}")
    }

    @Test fun episodeSaveRoundTrip() = live {
        val ep = api.search.episodes("asmr", limit = 1).items.first().uri
        val before = api.library.contains(listOf(ep)).single()
        api.podcasts.setEpisodeSaved(ep, !before)
        kotlinx.coroutines.delay(1500)
        val flipped = api.library.contains(listOf(ep)).single()
        api.podcasts.setEpisodeSaved(ep, before)
        kotlinx.coroutines.delay(1500)
        val back = api.library.contains(listOf(ep)).single()
        println("episode saved before=$before flipped=$flipped back=$back")
        assertEquals(!before, flipped)
        assertEquals(before, back)
    }
}
