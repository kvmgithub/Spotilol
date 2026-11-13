package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The gain diagnostics: what Spotify's track-playback answers carry as `gain_db`, kept per song and
 * file candidate, next to the page player's level settings at a change of song or element. Only
 * observed and logged: bounded, rate-limited, off by default, without Spotify's ids in its lines.
 */
class GainDiagnosticsTest {
    /** A clock and one-shot timers that move only when the test says so. */
    private class Time {
        var now = 0L
        private class Timer(val at: Long, val run: () -> Unit) { var cancelled = false }
        private val timers = mutableListOf<Timer>()
        var scheduled = 0
            private set
        val pending: Int get() = timers.count { !it.cancelled }

        fun schedule(delayMs: Long, run: () -> Unit): () -> Unit {
            scheduled++
            val t = Timer(now + delayMs, run)
            timers += t
            return { t.cancelled = true }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                timers.remove(next)
                now = maxOf(now, next.at)
                next.run()
            }
            now = end
            timers.removeAll { it.cancelled }
        }
    }

    private val time = Time()
    private val lines = mutableListOf<String>()
    private val loud = HashMap<String, Double>()
    private var app = GainDiagnostics.App(normalize = true, boost = 1.5, crossfadeSeconds = 12, adMuted = false)
    private var active: Boolean? = true

    private val diag = GainDiagnostics(
        output = { lines += it },
        schedule = time::schedule,
        clock = { time.now },
        loudness = { loud[it] },
        app = { app },
        device = { active },
    )

    private fun on(): Long {
        diag.setEnabled(true)
        return diag.runToken()!!
    }

    // --- what Spotify sends (synthetic, in the shape of a track-playback state answer) ---------------

    private fun file(id: String?, format: String, gain: String?) = buildString {
        append("{")
        if (id != null) append("\"file_id\":\"$id\",")
        append("\"format\":\"$format\",\"bitrate\":128000,\"file_url\":null")
        if (gain != null) append(",\"gain_db\":$gain")
        append("}")
    }

    private fun track(uri: String, mp4: List<String> = emptyList(), dual: List<String> = emptyList(), type: String = "TRACK",
                      context: String = "spotify:playlist:PLAYLISTSECRET") =
        """{"metadata":{"uri":"$uri","context_uri":"$context","linked_from_uri":null},
           |"manifest":{"file_ids_mp4":[${mp4.joinToString(",")}],"file_ids_mp4_dual":[${dual.joinToString(",")}],
           |"file_urls_mp3":[{"file_url":"https://audio.example/SECRETURL?token=TOKEN_SECRET","gain_db":-1.0}]},
           |"track_type":"AUDIO","content_type":"$type"}""".trimMargin()

    private fun answer(vararg tracks: String, current: Int = 0) =
        """{"state_machine":{"state_machine_id":"SMSECRET","tracks":[${tracks.joinToString(",")}],
           |"states":[${tracks.indices.joinToString(",") { """{"state_id":"s$it","track":$it,"track_uid":"UIDSECRET$it","player_cookie":"COOKIE_SECRET_$it"}""" }}],
           |"attributes":{"playback_session_id":"SESSION_SECRET"}},
           |"updated_state_ref":{"state_index":$current,"paused":false},"connection_id":"CONNECTION_SECRET"}""".trimMargin()

    private val path = "/track-playback/v1/devices/DEVICESECRET0123456789abcdef/state"

    private val fA1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"
    private val fA2 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"
    private val fB1 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb1"
    private val fB2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2"
    private val uA = "spotify:track:AAAAsecretAAAA"
    private val uB = "spotify:track:BBBBsecretBBBB"

    // --- what the page says (the bridge's "diag" message) --------------------------------------------

    private fun page(ev: String, uri: String?, pv: String = "0.8", pvSt: String = "ok", el: Int = 1, extra: String = ""): JsonObject =
        Json.parseToJsonElement(
            """{"type":"diag","v":1,"ev":"$ev","uri":${uri?.let { "\"$it\"" } ?: "null"},"ctx":"playlist","el":$el,
               |"pv":$pv,"pvSt":"$pvSt","cubic":true,"vol":0.512,"muted":false,"paused":false,"level":0.512,"routed":false,
               |"adMute":false,"wrote":"level","wroteMs":40,"wroteMatch":true,"merged":0$extra}""".trimMargin(),
        ).jsonObject

    // --- reading the lines ------------------------------------------------------------------------

    private fun fields(line: String): Map<String, String> =
        line.removePrefix(GainDiagnostics.PREFIX + " ").split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun lines(ev: String) = lines.filter { fields(it)["ev"] == ev }.map(::fields)

    // --- off ------------------------------------------------------------------------------------------

    @Test fun `off by default it reads nothing, writes nothing and sets no timer`() {
        assertFalse(diag.enabled)
        assertNull(diag.runToken())
        diag.onRelayAnswer(0, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.1")))))
        diag.onRelayAnswer(1, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.1")))))
        diag.onPage(page("track", uA))
        assertTrue(lines.isEmpty(), "$lines")
        assertEquals(0, diag.assignments)
        assertEquals(0, time.scheduled)
    }

    @Test fun `turned on it starts a run and says the app's sound settings once`() {
        on()
        val start = lines("start").single()
        assertEquals("1", start["run"])
        assertEquals("true", start["norm"])
        assertEquals("12", start["xf"])
        assertTrue(start["boost"]!!.startsWith("1.5"), "$start")
    }

    // --- data quality -----------------------------------------------------------------------------------

    @Test fun `two formats of one song keep their own gain, as offered candidates, not as the file played`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.127103")), dual = listOf(file(fA2, "12", "-5.127103")))))
        val a = lines("answer").single()
        val entries = a["entries"]!!.split(',')
        assertEquals(2, entries.size, "$a")
        assertTrue(entries.any { it.startsWith("t1.f1@10/mp4:candidate=") && it.endsWith("=-5.1271") }, "$entries")
        assertTrue(entries.any { it.startsWith("t1.f2@12/mp4_dual:candidate=") && it.endsWith("=-5.1271") }, "$entries")
        assertFalse(lines.any { "selected" in it }, "nothing says which file plays: $lines")
        assertEquals(2, diag.assignments)
    }

    @Test fun `several songs of one answer each keep their own id, the answer's current one is only the answer's`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(
            track(uA, mp4 = listOf(file(fA1, "10", "-5.1"))),
            track(uB, mp4 = listOf(file(fB1, "10", "-7.3"))),
            current = 1,
        ))
        val a = lines("answer").single()
        assertEquals("t2", a["answerCurrent"], "$a")
        assertEquals("2", a["tracks"])
        assertTrue(a["entries"]!!.contains("t1.f1@10/mp4:candidate=-5.1000"), "$a")
        assertTrue(a["entries"]!!.contains("t2.f2@10/mp4:candidate=-7.3000"), "$a")
        assertEquals("playlist", a["ctx"])
        // The page plays A: its line shows A's gain only, never B's.
        time.advance(1_000)
        diag.onPage(page("track", uA))
        val p = lines("track").single()
        assertEquals("t1", p["track"])
        assertEquals("f1@10/mp4:candidate=-5.1000", p["gain"])
    }

    @Test fun `gain null, absent, invalid and zero stay apart`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(
            file("cccccccccccccccccccccccccccccccccccccc01", "10", "null"),
            file("cccccccccccccccccccccccccccccccccccccc02", "10", null),
            file("cccccccccccccccccccccccccccccccccccccc03", "10", "\"loud\""),
            file("cccccccccccccccccccccccccccccccccccccc04", "10", "0.0"),
            file("cccccccccccccccccccccccccccccccccccccc05", "10", "1e400"),
            file("cccccccccccccccccccccccccccccccccccccc06", "10", "{\"v\":1}"),
        ))))
        val values = lines("answer").single()["entries"]!!.split(',').map { it.substringAfterLast('=') }
        assertEquals(listOf("null", "absent", "invalid", "0.0000", "invalid", "invalid"), values)
    }

    @Test fun `a file without an id is unknown, not a candidate`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(file(null, "10", "-4.0")))))
        assertEquals("t1.?@10/mp4:unknown=-4.0000", lines("answer").single()["entries"])
    }

    @Test fun `ads are counted, not kept`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(track("spotify:ad:ADSECRET", mp4 = listOf(file(fA1, "10", "-3.0")), type = "AD"), track(uA)))
        val a = lines("answer").single()
        assertEquals("1", a["ads"])
        assertEquals(0, diag.assignments)
    }

    @Test fun `loudness is read from what the player has, never asked for`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0"))), track(uB)))
        time.advance(1_000)
        diag.onPage(page("track", uA))
        val notRead = lines("track").last()
        assertEquals("unknown", notRead["loud"])
        assertEquals("not-read", notRead["loudSt"])
        loud[uA] = Double.NaN
        time.advance(1_000)
        diag.onPage(page("volume", uA))
        assertEquals("none", lines("volume").last()["loudSt"])
        loud[uB] = -7.25
        time.advance(1_000)
        diag.onPage(page("track", uB))
        val cached = lines("track").last()
        assertEquals("cached", cached["loudSt"])
        assertEquals("-7.2500", cached["loud"])
        assertEquals("none", cached["gain"])
    }

    @Test fun `the page's raw player volume, missing or invalid, is said as such`() {
        on()
        diag.onPage(page("track", uA, pv = "null", pvSt = "absent"))
        val missing = lines("track").single()
        assertEquals("unknown", missing["pv"])
        assertEquals("absent", missing["pvSt"])
        time.advance(1_000)
        diag.onPage(page("volume", uA, pv = "null", pvSt = "invalid"))
        assertEquals("invalid", lines("volume").single()["pvSt"])
        time.advance(1_000)
        diag.onPage(page("volume", uA, pv = "0.0", pvSt = "ok"))
        assertEquals("0.0000", lines("volume").last()["pv"])
    }

    // --- assignment over time ---------------------------------------------------------------------------

    @Test fun `a late answer after a skip never becomes the current song's gain`() {
        val run = on()
        // The page skipped to B; an answer still naming A as its current state arrives afterwards.
        diag.onPage(page("track", uB))
        time.advance(1_000)
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0"))), current = 0))
        time.advance(1_000)
        diag.onPage(page("volume", uB))
        val b = lines("volume").single()
        assertEquals("t1", b["track"], "B was the first song seen: $b")
        assertEquals("none", b["gain"], "A's gain is not B's: $b")
        assertEquals("t2", lines("answer").single()["answerCurrent"])
    }

    @Test fun `an answer of the run before a logout is dropped and counted`() {
        val before = on()
        diag.onRelayAnswer(before, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        assertEquals(1, diag.assignments)
        diag.newRun()
        val after = diag.runToken()!!
        assertTrue(after != before)
        assertEquals(0, diag.assignments, "a new account starts from nothing")
        time.advance(1_000)
        diag.onRelayAnswer(before, path, answer(track(uB, mp4 = listOf(file(fB1, "10", "-6.0")))))
        assertEquals(0, diag.assignments)
        time.advance(1_000)
        diag.onPage(page("track", uB))
        val p = lines("track").single()
        assertEquals("2", p["run"])
        assertEquals("1", p["late"])
        assertEquals("none", p["gain"])
        assertEquals("t1", p["track"], "ids start again in the new run")
    }

    @Test fun `another device playing is said, the local element is not taken for what is heard`() {
        on()
        active = false
        diag.onPage(page("track", uA))
        assertEquals("remote", lines("track").single()["device"])
        active = null
        time.advance(1_000)
        diag.onPage(page("volume", uA))
        assertEquals("unknown", lines("volume").single()["device"])
    }

    // --- bounds ---------------------------------------------------------------------------------------

    @Test fun `257 assignments keep the newest 256`() {
        val run = on()
        val files = (0 until 257).map { i -> file("%040x".format(i + 1), "10", "-1.0") }
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = files)))
        assertEquals(GainDiagnostics.MAX_ENTRIES, diag.assignments)
        val a = lines("answer").single()
        assertEquals("1", a["evicted"])
        assertEquals("256", a["table"])
        assertTrue(a["more"]!!.toInt() > 0, "a long answer is shortened: $a")
        assertTrue(lines.single { "ev=answer" in it }.length < 2_000, "one bounded line")
    }

    @Test fun `a flood of events gives at most two lines a second, the rest merged and counted`() {
        on() // the start line is the first of this second
        repeat(1_000) { diag.onPage(page("volume", uA)) }
        assertEquals(2, lines.size, "start and one more in the first second: $lines")
        assertEquals(1, time.pending, "one flush waiting, no queue")
        time.advance(1_000)
        assertEquals(3, lines.size)
        val merged = fields(lines.last())
        assertEquals("998", merged["merged"], "1000 events: one written, one written later, 998 folded into it")
        time.advance(5_000)
        assertEquals(3, lines.size, "nothing more without new events")
        assertEquals(0, time.pending)
    }

    @Test fun `answers and page events wait apart, a flood of one never hides the other`() {
        val run = on()
        repeat(50) { diag.onPage(page("volume", uA)) }
        diag.onRelayAnswer(run, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        time.advance(1_000)
        assertEquals(1, lines("answer").size, "$lines")
        assertTrue(lines("volume").isNotEmpty())
    }

    @Test fun `turning off with a line waiting drops it, a new run never shows it`() {
        on()
        repeat(5) { diag.onPage(page("volume", uA)) }
        assertEquals(1, time.pending)
        diag.setEnabled(false)
        assertFalse(diag.enabled)
        assertEquals(0, time.pending, "the flush is cancelled")
        val before = lines.size
        time.advance(3_000)
        assertEquals(before, lines.size, "nothing written after off")
        diag.setEnabled(true)
        time.advance(3_000)
        assertTrue(lines.drop(before).all { fields(it)["run"] == "2" }, "${lines.drop(before)}")
        assertEquals(1, lines.drop(before).size, "only the new run's start: ${lines.drop(before)}")
        assertEquals(0, diag.assignments)
    }

    @Test fun `malformed page messages are counted and never written, later ones still are`() {
        on()
        val bad = listOf(
            """{"type":"diag"}""",
            """{"type":"diag","ev":"nonsense"}""",
            """{"type":"diag","ev":{"x":1}}""",
            """{"type":"diag","ev":"track","uri":["spotify:track:LISTSECRET"],"el":"x","pv":"loud","vol":"x","merged":-5}""",
        ).map { Json.parseToJsonElement(it).jsonObject }
        bad.forEach(diag::onPage)
        time.advance(1_000)
        diag.onPage(page("track", uA))
        val p = lines("track").last()
        assertEquals("3", p["bad"], "three without a known event; the fourth is read with its odd fields unknown: $lines")
        assertFalse(lines.any { "LISTSECRET" in it })
        assertTrue(lines("track").any { it["track"] == "unknown" && it["el"] == "0" && it["vol"] == "unknown" }, "$lines")
    }

    @Test fun `an unreadable answer or a failing source never reaches the caller`() {
        val run = on()
        diag.onRelayAnswer(run, path, "not json")
        diag.onRelayAnswer(run, path, "{\"state_machine\":[1,2]}")
        diag.onRelayAnswer(run, path, "[]")
        val failing = GainDiagnostics(output = { lines += it }, schedule = time::schedule, clock = { time.now },
            loudness = { error("cache gone") }, app = { error("settings gone") }, device = { error("no media") })
        failing.setEnabled(true)
        time.advance(1_000)
        failing.onPage(page("track", uA))
        assertTrue(lines.none { "cache gone" in it || "settings gone" in it })
    }

    // --- privacy ----------------------------------------------------------------------------------------

    @Test fun `no token, cookie, URL or Spotify id reaches a line`() {
        val run = on()
        diag.onRelayAnswer(run, path, answer(
            track(uA, mp4 = listOf(file(fA1, "10", "-5.0")), dual = listOf(file(fA2, "12", "-5.0"))),
            track(uB, mp4 = listOf(file(fB1, "10", "-6.0"), file(fB2, "11", "-6.0")), context = "spotify:user:USERSECRET:collection"),
        ))
        time.advance(1_000)
        diag.onPage(page("track", uA, extra = ""","token":"TOKEN_SECRET","cookie":"sp_dc=COOKIE_SECRET","url":"https://x.example/?a=SECRETURL""""))
        time.advance(1_000)
        diag.onPage(page("element", "spotify:track:ELEMENTSECRET", el = 2))
        assertTrue(lines.size >= 3, "$lines")
        val forbidden = listOf("SECRET", "TOKEN", "COOKIE", "sp_dc", "http", "spotify:", fA1, fA2, fB1, fB2, "aaaaaaaa", "bbbbbbbb")
        for (l in lines) for (f in forbidden) assertFalse(l.contains(f, ignoreCase = true), "'$f' in: $l")
    }

    // --- no part in playback ------------------------------------------------------------------------

    /** The page as the player sees it: every call it gets is recorded and answered with null. */
    private class RecordingHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 256)
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", """).find(script) ?: return
            calls += m.groupValues[2]
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
    }

    private fun stateOf(name: String, paused: Boolean = false) =
        """{"type":"state","state":{"track":{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000},
           |"contextUri":"spotify:playlist:C","next":null,"isPaused":$paused,"isBuffering":false,"positionMs":0,
           |"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    /** Reads of the page's state, made on timers: not playback commands. */
    private val reads = setOf("media.state", "volume.get", "player.state", "player.queue", "device.activeNow", "bridge.version", "player.audioInfo", "drm.probe")

    /** The same page events and user actions, with the diagnostics on or off: every call the player made. */
    private fun callsWith(diagnostics: Boolean): List<String> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = RecordingHost()
            val bridge = Bridge(host, scope)
            val player = SpotifyPlayer(bridge, scope)
            withTimeout(10_000) { host.messages.subscriptionCount.first { it > 0 } }
            player.setGainDiagnostics(diagnostics)
            host.messages.emit("""{"type":"ready"}""")
            withTimeout(10_000) { bridge.ready.first { it } }
            host.messages.emit(stateOf("A"))
            withTimeout(10_000) { player.state.first { it?.item?.uri == "spotify:track:A" } }
            // What the page tells while the diagnostics are on (sent by it whether or not they are read).
            host.messages.emit("""{"type":"diag","v":1,"ev":"track","uri":"spotify:track:A","el":1,"pv":0.8,"pvSt":"ok","vol":0.5,"merged":0}""")
            host.messages.emit(stateOf("B"))
            withTimeout(10_000) { player.state.first { it?.item?.uri == "spotify:track:B" } }
            // A pause, as the page then reports it: no fade or rise whose timers could land on
            // either side of the end of the recording (a skip's rise did, once).
            player.pause()
            host.messages.emit(stateOf("B", paused = true))
            withTimeout(10_000) { player.state.first { it?.isPaused == true } }
            delay(1_000)
            host.calls.toList()
        } finally {
            scope.cancel()
        }
    }

    @Test fun `with the diagnostics on the player sends the same playback commands as with them off`() {
        val off = callsWith(false)
        val on = callsWith(true)
        fun commands(calls: List<String>) = calls.filter { it !in reads && it != "diag.gain" }.sorted()
        assertTrue(commands(off).contains("player.pause"), "$off")
        assertEquals(commands(off), commands(on))
        assertEquals(0, off.count { it == "diag.gain" })
        assertEquals(1, on.count { it == "diag.gain" }, "turned on once in the page that came up: $on")
    }
}
