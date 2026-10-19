package app.myspoti.spotify

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkHealthTest {
    @Test
    fun `failures hold offline until a success or the hold ends`() {
        var now = 1_000L
        val n = NetworkHealth { now }
        assertTrue(n.online())
        n.failed()
        assertFalse(n.online())
        now += NetworkHealth.FAILURE_HOLD_MS
        assertFalse(n.online())
        now += 1
        assertTrue(n.online())
        n.failed(); now += 10
        n.succeeded()
        assertTrue(n.online())
    }

    @Test
    fun `no system network means offline`() {
        val n = NetworkHealth { 0L }
        n.systemOnline = false
        n.succeeded()
        assertFalse(n.online())
    }
}
