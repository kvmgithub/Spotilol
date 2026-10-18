package app.myspoti.host.cdp

import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * A free account plays through several songs in a row, ads included: each song is
 * jumped to its last seconds, and the next one must start by itself.
 *
 *     ./gradlew :host-cdp:test -Pcdp=http://localhost:9222 --tests '*LivePlaythroughTest*'
 */
@EnabledIfSystemProperty(named = "myspoti.cdp", matches = ".+")
class LivePlaythroughTest {
    @Test
    fun `plays song after song`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = CdpHost.connect(System.getProperty("myspoti.cdp"))
        val api = SpotifyApi(host, scope, language = "fr")
        host.reload(); host.awaitLoad(); api.onPageLoaded(); api.awaitReady()
        api.bridge.logger = { line -> if ("stalled" in line || "ad stuck" in line || "call #" in line && "player.play" in line) println(line) }
        api.player.reloadHost = { println("reloading the page"); host.reload(); host.awaitLoad(); api.onPageLoaded() }
        scope.launch { api.player.adPlaying.collect { println("ad playing: $it") } }
        scope.launch {
            var last = ""
            api.player.state.collect { s ->
                val line = "${s?.item?.name} paused=${s?.isPaused} buffering=${s?.isBuffering}"
                if (line != last) { println("state: $line"); last = line }
            }
        }
        val songs = (System.getProperty("myspoti.songs") ?: System.getenv("MSP_SONGS") ?: "8").toInt()
        api.player.play("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", index = 0)
        var played = 0
        try {
            repeat(songs) { n ->
                val s = withTimeout(300_000) { api.player.state.first { it?.item != null && !it.isPaused && !it.isBuffering } }!!
                println("song ${n + 1}: ${s.item!!.name}")
                played++
                // An ad being dropped refuses the seek for a moment.
                for (attempt in 1..10) {
                    if (runCatching { api.player.seek((s.item!!.durationMs - 4_000).coerceAtLeast(0)) }.isSuccess) break
                    delay(1_000)
                }
                val moved = kotlinx.coroutines.withTimeoutOrNull(300_000) { api.player.state.first { it?.item != null && it.item!!.uri != s.item!!.uri } }
                if (moved == null) {
                    println("stuck; page says: " + api.bridge.call("player.state").toString().take(1500))
                    error("no next song after ${s.item!!.name}")
                }
            }
        } finally {
            runCatching { api.player.pause() }
            println("played $played of $songs")
            delay(500)
        }
        assertTrue(played == songs)
    }
}
