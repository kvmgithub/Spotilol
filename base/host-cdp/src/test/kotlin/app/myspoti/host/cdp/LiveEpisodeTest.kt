package app.myspoti.host.cdp

import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Episodes still play with the ad formats left out of the player's registration. */
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LiveEpisodeTest {
    @Test
    fun `an episode plays`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        val api = SpotifyApi(host, scope, language = "fr")
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        for (query in listOf("france inter", "the daily", "joe rogan")) {
            val ep = api.search.episodes(query, limit = 1).items.firstOrNull() ?: continue
            try {
                api.player.play(ep.uri)
                val s = withTimeout(30_000) { api.player.state.first { it?.item?.uri == ep.uri && !it.isPaused && !it.isBuffering } }!!
                delay(4_000)
                val pos = api.player.state.value!!.positionAt(System.currentTimeMillis())
                println("episode ${ep.name}: position $pos")
                assertTrue(pos > 2_000)
            } finally { runCatching { api.player.pause() } }
        }
    }
}
