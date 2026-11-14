package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.CapturedCall
import app.myspoti.spotify.bridge.PageAuth
import app.myspoti.spotify.bridge.PageDevice
import app.myspoti.spotify.bridge.ScriptedBridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's recovery around transient refusals, reloads and the user's pause: the real [Bridge]
 * and [SpotifyPlayer] on a page whose answers the test gives ([ScriptedBridgeHost]). Checked are the
 * commands the player sends next, in order, and what it reports. A command already on its way to
 * the page cannot be taken back: the checks are on the commands sent after it and on how later
 * answers are handled. Real time: the player's own waits and retry gaps are kept as they are; every
 * wait here is bounded.
 */
class PlayerRecoverySequenceTest {
    private lateinit var scope: CoroutineScope
    private lateinit var host: ScriptedBridgeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer
    private val logs = CopyOnWriteArrayList<String>()

    @BeforeEach fun up() = runBlocking {
        // Process-wide state other tests share: from a known start, and back to it after.
        resetShared()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = ScriptedBridgeHost()
        // The page's background reads and level steps, answered as every player test so far does
        // (null: nothing known); the playback commands stay open until the test answers them.
        BACKGROUND.forEach { host.answer(it) }
        bridge = Bridge(host, scope)
        bridge.logger = { logs += it }
        player = SpotifyPlayer(bridge, scope)
        host.awaitSubscriber()
    }

    @AfterEach fun down() {
        scope.cancel()
        resetShared()
    }

    private fun resetShared() {
        PageDevice.resetForTests()
        PageAuth.pageGone()
        AdBlock.ahead.value = null
    }

    // --- the page -----------------------------------------------------------------------------------

    private fun track(name: String) =
        """{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000}"""

    private fun state(name: String, paused: Boolean, at: Long = 0) =
        """{"type":"state","state":{"track":${track(name)},"contextUri":"spotify:playlist:C","next":null,"isPaused":$paused,
            |"isBuffering":false,"positionMs":$at,"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    private suspend fun ready() {
        host.post("""{"type":"ready","version":1}""")
        until("page ready") { bridge.ready.value }
    }

    /**
     * The page reports [name] ([paused] or playing, at [at] ms), and the player has taken it. Said again every
     * half second until then: the player's event collector may not listen yet, and a state posted
     * before it does is lost (the bridge's events keep none for later).
     */
    private suspend fun showing(name: String, paused: Boolean, at: Long = 0) {
        val end = System.currentTimeMillis() + 10_000
        while (player.state.value?.let { it.item?.uri == "spotify:track:$name" && it.isPaused == paused } != true) {
            check(System.currentTimeMillis() < end) { "timed out: state $name paused=$paused; sent: ${sent()}; log: ${logs.takeLast(15)}" }
            host.post(state(name, paused, at))
            delay(500)
        }
    }

    /**
     * The new page reports its restored session: twice, so the restore, which waits for a state
     * newer than the one it started with, sees one whichever side of its start the first came.
     */
    private suspend fun restored(name: String, paused: Boolean, at: Long = 0) {
        showing(name, paused, at)
        delay(300)
        host.post(state(name, paused, at))
    }

    /**
     * A song playing on a page whose device had joined Spotify Connect; the page goes away (not by
     * the player: Android, the WebView) and a new one says ready, its device not joined yet. The
     * player's restore then waits for that device (it knows the host reports joins). [at]: where A was.
     */
    private suspend fun pageLostWhilePlaying(at: Long = 0) {
        PageDevice.joined()
        ready()
        showing("A", paused = false, at = at)
        bridge.pageGone()
        until("page gone") { !bridge.ready.value }
        // The player's own collector of [Bridge.ready] must see the page go: a StateFlow keeps only
        // its newest value, and "not ready" then "ready" at once is no change to a collector that
        // was late, and no restore would run (a likely cause of one timeout in CI, not shown). Nothing
        // of the player tells it has; every test here shows the restore ran (its command, or its
        // log), so a miss fails, never passes.
        delay(LOSS_SEEN_MS)
        ready()
    }

    /**
     * The new page's device joins. The restore may or may not have started waiting for it yet (it
     * says so in the log only when it did); a moment after, it goes on either way.
     */
    private suspend fun joinDevice() {
        PageDevice.joined()
        delay(300)
    }

    // --- reading what happened ------------------------------------------------------------------

    private suspend fun until(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out: $what; sent: ${sent()}; log: ${logs.takeLast(15)}" }
            delay(20)
        }
    }

    private fun logged(part: String) = logs.any { part in it }

    /** The playback commands sent, in order, with their arguments (not the background reads). */
    private fun sent(from: Int = 0): List<String> = host.calls.drop(from).filter { it.method !in BACKGROUND }.map { "${it.method} ${it.args}" }

    private fun count(method: String) = host.calls.count { it.method == method }

    /** [ScriptedBridgeHost.awaitCall], failing with what was sent and logged when nothing comes. */
    private suspend fun next(method: String, ms: Long): CapturedCall =
        runCatching { host.awaitCall(method, timeoutMs = ms) }
            .getOrElse { throw AssertionError("no $method within $ms ms; sent: ${sent()}; log: ${logs.takeLast(20)}", it) }

    /** Commands that make the page play, sent from call [from] on. */
    private fun playsSince(from: Int): List<CapturedCall> = host.calls.drop(from).filter { it.method in PLAYING }

    // --- P1: the reference --------------------------------------------------------------------------

    @Test fun transientResumeFailureRetriesAndRecovers() = runBlocking {
        ready()
        showing("A", paused = true)
        val resuming = scope.async { player.resume() }
        host.fail(host.awaitCall("player.resume"), "No such device.")
        host.succeed(host.awaitCall("player.resume", timeoutMs = 5_000))
        withTimeout(5_000) { resuming.await() }
        delay(SETTLE_MS)
        assertEquals(2, count("player.resume"), "sent again once, then not again: ${sent()}")
        assertEquals(true, player.state.value?.isPaused, "a command the page took is no playing state")
        showing("A", paused = false)
        delay(SETTLE_MS)
        assertEquals(2, count("player.resume"), "nothing more once it plays: ${sent()}")
    }

    // --- P2: a pause while a resume waits to be sent again ------------------------------------------

    /** After [mark], no command that makes the page play within [ms]; then an explicit resume works. */
    private suspend fun pauseHeld(mark: Int, ms: Long = SETTLE_MS) {
        delay(ms)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "sent after the user's pause; all: ${sent()}")
        val again = scope.async { player.resume() }
        host.succeed(host.awaitCall("player.resume"))
        withTimeout(5_000) { again.await() }
    }

    @Test fun pauseDuringResumeBackoffPreventsObsoleteRetry_pauseBeforeTheFailure() = runBlocking {
        ready()
        showing("A", paused = true)
        val resuming = scope.async { player.resume() }
        val first = host.awaitCall("player.resume")
        player.pause()
        val mark = host.calls.size
        host.fail(first, "No such device.")
        until("the failure handled") { logged("player.resume FAILED") }
        pauseHeld(mark)
        assertTrue(resuming.isCompleted, "the superseded resume is over: ${sent()}")
    }

    @Test fun pauseDuringResumeBackoffPreventsObsoleteRetry_pauseDuringTheBackoff() = runBlocking {
        ready()
        showing("A", paused = true)
        val resuming = scope.async { player.resume() }
        host.fail(host.awaitCall("player.resume"), "No such device.")
        until("the retry waits") { logged("player.resume not taken") }
        val mark = host.calls.size
        player.pause()
        pauseHeld(mark)
        assertTrue(resuming.isCompleted, "the superseded resume is over: ${sent()}")
    }

    // --- P3: a pause while the restore after a reload waits ------------------------------------------

    @Test fun pauseWhileReloadRestoreWaitsRemainsAuthoritative_pauseBeforeTheAnswers() = runBlocking {
        pageLostWhilePlaying()
        // The restore waits for the device; the user pauses (the old page's song still shows playing).
        val pausing = scope.async { player.pause() }
        host.succeed(host.awaitCall("player.pause"))
        withTimeout(5_000) { pausing.await() }
        val mark = host.calls.size
        joinDevice()
        restored("A", paused = true)
        until("restore checked the song", ms = SpotifyPlayer.RESTORE_WAIT_MS + 2_000) { logged("goes on at") }
        delay(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 1_000)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "played after the user's pause; all: ${sent()}")
    }

    @Test fun pauseWhileReloadRestoreWaitsRemainsAuthoritative_pauseBeforeTheNextAttempt() = runBlocking {
        pageLostWhilePlaying()
        joinDevice()
        restored("A", paused = true)
        // The restore's resume is refused for a moment, and waits to be sent again; the user pauses then.
        host.fail(host.awaitCall("player.resume", timeoutMs = SpotifyPlayer.RESTORE_WAIT_MS + 2_000), "No such device.")
        until("the retry waits") { logged("player.resume not taken") }
        val mark = host.calls.size
        player.pause()
        // Past the restore's next attempt.
        delay(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 1_000)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "played after the user's pause; all: ${sent()}")
    }

    /** The other side of P3: nobody pauses, the restore plays the song on. */
    @Test fun restoreWithoutUserActionResumesTheLostSong() = runBlocking {
        pageLostWhilePlaying()
        joinDevice()
        restored("A", paused = true)
        host.succeed(host.awaitCall("player.resume", timeoutMs = SpotifyPlayer.RESTORE_WAIT_MS + 2_000))
        showing("A", paused = false)
        delay(SETTLE_MS)
        assertEquals(1, count("player.resume"), "${sent()}")
        assertEquals(0, count("player.play"), "${sent()}")
    }

    // --- P4: ready is not playing ----------------------------------------------------------------------

    @Test fun readyWithoutJoinedDeviceIsNotPlaybackConfirmation() = runBlocking {
        pageLostWhilePlaying()
        val readyAt = System.currentTimeMillis()
        // The device does not join: the restore waits its time for it, then goes on anyway.
        until("waited for the device", ms = SpotifyPlayer.JOIN_WAIT_MS + 5_000) { logged("page device not joined after") }
        assertTrue(System.currentTimeMillis() - readyAt >= SpotifyPlayer.JOIN_WAIT_MS - 500, "the wait for the device kept")
        restored("A", paused = true)
        val first = host.awaitCall("player.resume", timeoutMs = SpotifyPlayer.RESTORE_WAIT_MS + 2_000)
        host.succeed(first)
        val firstAt = System.currentTimeMillis()
        delay(1_000)
        assertEquals(true, player.state.value?.isPaused, "the page took the resume, nothing plays: no playing state")
        // The join comes late: the restore's next attempt, after its own check and gap.
        PageDevice.joined()
        val second = host.awaitCall("player.resume", timeoutMs = SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + 5_000)
        assertTrue(System.currentTimeMillis() - firstAt >= SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS - 500,
            "the restore's check and gap kept")
        host.succeed(second)
        showing("A", paused = false)
        delay(SETTLE_MS)
        assertEquals(2, count("player.resume"), "not again once it plays: ${sent()}")
        assertEquals(0, count("player.play"), "${sent()}")
    }

    // --- P5: a pick newer than the restore -----------------------------------------------------------

    private suspend fun pick(name: String) {
        val picking = scope.async { player.play("spotify:playlist:C", trackUri = "spotify:track:$name") }
        host.succeed(host.awaitCall("player.play"))
        withTimeout(5_000) { picking.await() }
    }

    @Test fun newPickSupersedesOlderRestore_pickWhileTheDeviceIsAwaited() = runBlocking {
        pageLostWhilePlaying()
        pick("P")
        val mark = host.calls.size
        joinDevice()
        restored("P", paused = false)
        delay(SETTLE_MS + 1_000)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "the restore displaced the pick; all: ${sent()}")
        assertEquals("spotify:track:P", player.state.value?.item?.uri)
        assertRestoreStopped("picked")
    }

    @Test fun newPickSupersedesOlderRestore_pickWhileTheRestoredStateIsAwaited() = runBlocking {
        pageLostWhilePlaying()
        joinDevice()
        pick("P")
        val mark = host.calls.size
        restored("P", paused = false)
        delay(SETTLE_MS + 1_000)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "the restore displaced the pick; all: ${sent()}")
        assertEquals("spotify:track:P", player.state.value?.item?.uri)
        assertRestoreStopped("picked")
    }

    /**
     * The restore ran and stopped at the user's [what] ("paused", "picked"): without this, a test that
     * finds no command after the user's action would pass also when no restore ran at all.
     */
    private fun assertRestoreStopped(what: String) =
        assertTrue(logs.any { "page back after a reload" in it && "$what meanwhile, A not played on" in it }, "the restore stopped at the $what: ${logs.takeLast(15)}")

    // --- from the review of M2.1: what the restore does once its own command is answered -------------

    /**
     * R1: the restore's resume is on its way, the user picks P and P plays; the old resume is
     * answered only then. Positions within 5 s: no seek, only another attempt could play A again.
     */
    @Test fun lateRestoreAnswerKeepsANewerPick_resume() = runBlocking {
        pageLostWhilePlaying()
        joinDevice()
        restored("A", paused = true)
        val old = next("player.resume", SpotifyPlayer.RESTORE_WAIT_MS + 2_000)
        pick("P")
        showing("P", paused = false)
        val mark = host.calls.size
        host.succeed(old)
        // Past the restore's check and gap: where its next attempt would be.
        delay(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + SETTLE_MS)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "the old restore displaced the pick; all: ${sent()}")
        assertEquals("spotify:track:P", player.state.value?.item?.uri)
        assertRestoreStopped("picked")
    }

    /** R1 with the restore's play (the new page restored another song): the same for its late answer. */
    @Test fun lateRestoreAnswerKeepsANewerPick_play() = runBlocking {
        pageLostWhilePlaying()
        joinDevice()
        restored("B", paused = true)
        val old = next("player.play", SpotifyPlayer.RESTORE_WAIT_MS + 2_000)
        assertEquals("spotify:track:A", old.args["trackUri"]?.jsonPrimitive?.content, "the restore plays A: ${old.args}")
        pick("P")
        showing("P", paused = false)
        val mark = host.calls.size
        host.succeed(old)
        delay(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + SETTLE_MS)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "the old restore displaced the pick; all: ${sent()}")
        assertEquals("spotify:track:P", player.state.value?.item?.uri)
        assertRestoreStopped("picked")
    }

    /**
     * R2: A was at 98 s, the new page restored it at its start (more than 5 s off). The restore's
     * resume is refused for a moment and dropped by the user's pause: no seek after it either.
     */
    @Test fun droppedRestoreResumeSendsNoSeek() = runBlocking {
        pageLostWhilePlaying(at = 98_000)
        joinDevice()
        restored("A", paused = true)
        host.fail(next("player.resume", SpotifyPlayer.RESTORE_WAIT_MS + 2_000), "No such device.")
        until("the retry waits") { logged("player.resume not taken") }
        val mark = host.calls.size
        player.pause()
        delay(SpotifyPlayer.RESTORE_CHECK_MS + SpotifyPlayer.RESTORE_GAP_MS + SETTLE_MS)
        assertEquals(emptyList<String>(), playsSince(mark).map { "${it.method} ${it.args}" }, "sent after the user's pause; all: ${sent()}")
        assertRestoreStopped("paused")
    }

    /** The other side of R2: nobody pauses, the resume is answered, and the far position is set once. */
    @Test fun restoreSetsAFarPositionOnce() = runBlocking {
        pageLostWhilePlaying(at = 98_000)
        joinDevice()
        val mark = host.calls.size
        restored("A", paused = true)
        host.succeed(next("player.resume", SpotifyPlayer.RESTORE_WAIT_MS + 2_000))
        val seek = next("player.seek", 5_000)
        val to = seek.args["positionMs"]?.jsonPrimitive?.long ?: 0L
        assertTrue(to >= 98_000, "back to where A was: ${seek.args}")
        host.succeed(seek)
        showing("A", paused = false, at = to)
        delay(SETTLE_MS)
        assertEquals(listOf("player.resume", "player.seek"), playsSince(mark).map { it.method }, "${sent()}")
    }

    private companion object {
        /** Longer than the player's first retry gap ([SpotifyPlayer.COMMAND_RETRY_MS]) and its rise. */
        const val SETTLE_MS = 2_000L

        /** Time for the player's collector to take a page's loss (see pageLostWhilePlaying). */
        const val LOSS_SEEN_MS = 300L

        /** Reads and level steps of the page, answered with null as in the other player tests. */
        val BACKGROUND = setOf(
            "media.state", "bridge.version", "volume.get", "player.state", "player.queue", "device.activeNow",
            "player.audioInfo", "drm.probe", "player.preloadNext", "diag.gain", "ads.off",
            "media.rise", "media.ramp", "media.volume", "media.fade", "media.mute", "media.normalize",
            "media.boost", "media.crossfade", "media.ending",
        )

        /** Commands that start or move playback. */
        val PLAYING = setOf("player.resume", "player.play", "player.next", "player.previous", "queue.skipTo", "player.seek")
    }
}
