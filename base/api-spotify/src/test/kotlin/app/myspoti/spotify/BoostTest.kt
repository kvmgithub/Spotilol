package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The volume boost: sent to the page, and off again (with a reload) when the page hears nothing. */
class BoostTest {
    private class Host : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        override suspend fun evaluate(script: String) {
            val call = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)""").find(script) ?: return
            synchronized(sent) { sent += call.groupValues[2] + " " + call.groupValues[3] }
            messages.emit("""{"type":"result","id":${call.groupValues[1]},"ok":true,"value":null}""")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    @Test fun `a silent probe turns the boost off and reloads the page`() = runBlocking {
        val host = Host()
        val bridge = Bridge(host, scope)
        val player = SpotifyPlayer(bridge, scope)
        val reloaded = CompletableDeferred<Unit>()
        player.reloadHost = { reloaded.complete(Unit) }
        host.messages.subscriptionCount.first { it > 0 }
        host.messages.emit("""{"type":"ready","version":1}""")
        bridge.ready.first { it }

        player.setBoost(3.0)
        assertEquals(SpotifyPlayer.BOOST_MAX, player.boost)
        assertTrue(synchronized(host.sent) { host.sent.any { it.startsWith("media.boost") && it.contains("2.0") } }, host.sent.toString())

        // A probe that heard sound changes nothing; the silent one after it turns the boost off.
        host.messages.emit("""{"type":"boost","sound":true,"src":"blob","state":"running"}""")
        // Subscribed before the next message: the flow keeps no replay.
        val silent = scope.async(start = CoroutineStart.UNDISPATCHED) { player.boostSilent.first() }
        host.messages.emit("""{"type":"boost","sound":false,"src":"blob","state":"running"}""")
        withTimeout(5_000) { silent.await(); reloaded.await() }
        assertEquals(1.0, player.boost)
    }
}
