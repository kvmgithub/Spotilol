package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test

/** The app builds the player on the main dispatcher, whose coroutines start at once. */
class PlayerStartTest {
    private class SilentHost : BridgeHost {
        override val messages = MutableSharedFlow<String>()
        override suspend fun evaluate(script: String) {}
    }

    @Test fun `the player starts on a dispatcher that runs its coroutines at once`() {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> failures += e })
        try {
            SpotifyPlayer(Bridge(SilentHost(), scope), scope)
            if (failures.isNotEmpty()) throw failures.first()
        } finally {
            scope.cancel()
        }
    }
}
