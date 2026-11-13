package app.myspoti.spotify

import kotlinx.serialization.json.JsonObject

/** Gain diagnostics: not implemented yet (the tests come first). */
class GainDiagnostics(
    private val output: (String) -> Unit,
    private val schedule: (delayMs: Long, run: () -> Unit) -> (() -> Unit),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val loudness: (String) -> Double? = { null },
    private val app: () -> App = { App() },
    private val device: () -> Boolean? = { null },
) {
    data class App(val normalize: Boolean? = null, val boost: Double? = null, val crossfadeSeconds: Int? = null, val adMuted: Boolean? = null)

    val enabled: Boolean get() = false

    fun setEnabled(on: Boolean) {}

    fun newRun() {}

    fun runToken(): Long? = null

    fun onRelayAnswer(token: Long, path: String, body: String) {}

    fun onPage(msg: JsonObject) {}

    internal val assignments: Int get() = 0

    companion object {
        const val MAX_ENTRIES = 256
        const val LINES_PER_S = 2
        const val WINDOW_MS = 1_000L
        const val PREFIX = "gain-diag"
    }
}
