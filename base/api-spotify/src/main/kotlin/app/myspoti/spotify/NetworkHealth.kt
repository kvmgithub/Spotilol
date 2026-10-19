package app.myspoti.spotify

/**
 * Whether Spotify is reachable: the system has a network, and the app's own requests are not
 * failing at the network level. A failure after the last success counts as offline for
 * [FAILURE_HOLD_MS]; the next success clears it. (A VPN or DNS outage leaves the system
 * network up while every name lookup fails, so the system's view alone is not enough.)
 */
class NetworkHealth(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var systemOnline = true
    @Volatile private var lastFailureAt = 0L
    @Volatile private var lastSuccessAt = 0L

    fun failed() { lastFailureAt = clock() }
    fun succeeded() { lastSuccessAt = clock() }

    fun online(): Boolean {
        if (!systemOnline) return false
        val failure = lastFailureAt
        return failure <= lastSuccessAt || clock() - failure > FAILURE_HOLD_MS
    }

    companion object {
        const val FAILURE_HOLD_MS = 20_000L
    }
}
