package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The gain diagnostics: what Spotify's track-playback answers carry as `gain_db`, kept per song and
 * file candidate with where each value came from, next to the page player's level settings. Only
 * observed and logged: bounded, rate-limited, off by default, bound to its run and page, without
 * Spotify's ids in its lines.
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

    /** The page's tag as last armed. */
    private var tag = 0L

    private fun on() {
        diag.setEnabled(true)
        tag = diag.armPage()!!
    }

    /** A relay request of the page armed now, as the relay takes it. */
    private fun ticket(): Long = diag.relayTicket(tag)!!

    /** A second of time: room for two more lines. */
    private fun later() = time.advance(1_000)

    // --- what Spotify sends (synthetic, in the shape of a track-playback state answer) ---------------

    private fun file(id: String?, format: String, gain: String?) = buildString {
        append("{")
        if (id != null) append("\"file_id\":\"$id\",")
        append("\"format\":\"$format\",\"bitrate\":128000,\"file_url\":null")
        if (gain != null) append(",\"gain_db\":$gain")
        append("}")
    }

    private fun track(uri: String, mp4: List<String> = emptyList(), dual: List<String> = emptyList(), type: String = "TRACK",
                      context: String? = "spotify:playlist:PLAYLISTSECRET", extraLists: String = "") =
        """{"metadata":{"uri":"$uri"${context?.let { ",\"context_uri\":\"$it\"" } ?: ""},"linked_from_uri":null},
           |"manifest":{"file_ids_mp4":[${mp4.joinToString(",")}],"file_ids_mp4_dual":[${dual.joinToString(",")}],
           |"file_urls_mp3":[{"file_url":"https://audio.example/SECRETURL?token=TOKEN_SECRET","gain_db":-1.0}]$extraLists},
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

    private fun relay(body: String, t: Long = ticket()) = diag.onRelayAnswer(t, path, body)

    // --- what the page says (the bridge's "diag" message) --------------------------------------------

    private fun page(ev: String, uri: String?, pv: String = "0.8", pvSt: String = "ok", el: Int = 1, ctx: String = "playlist",
                     tag: Long = this.tag, level: String = "0.512", extra: String = ""): JsonObject =
        Json.parseToJsonElement(
            """{"type":"diag","v":2,"tag":$tag,"ev":"$ev","uri":${uri?.let { "\"$it\"" } ?: "null"},"ctx":"$ctx","pl":1,"el":$el,
               |"pv":$pv,"pvSt":"$pvSt","cubic":true,"vol":0.512,"muted":false,"paused":false,"level":$level,"routed":false,
               |"adMute":false,"wrote":"level","wroteMs":40,"wroteMatch":true,"merged":0,"faults":0,"fault":null$extra}""".trimMargin(),
        ).jsonObject

    // --- reading the lines ------------------------------------------------------------------------

    private fun fields(line: String): Map<String, String> =
        line.removePrefix(GainDiagnostics.PREFIX + " ").split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun lines(vararg ev: String) = lines.filter { fields(it)["ev"] in ev }.map(::fields)

    // --- off ------------------------------------------------------------------------------------------

    @Test fun `off by default it reads nothing, writes nothing and sets no timer`() {
        assertFalse(diag.enabled)
        assertNull(diag.relayTicket(1))
        assertNull(diag.armPage())
        diag.onRelayAnswer((1L shl 32) or 1L, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.1")))))
        diag.onPage(page("track", uA, tag = 1))
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
        assertEquals("1.50", start["boost"])
    }

    // --- data quality -----------------------------------------------------------------------------------

    @Test fun `two formats of one song keep their own gain, as offered candidates, not as the file played`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.127103")), dual = listOf(file(fA2, "12", "-5.127103")))))
        val a = lines("answer").single()
        assertEquals(listOf("t1/cur/playlist:f1@10/mp4:candidate=-5.1271", "t1/cur/playlist:f2@12/mp4_dual:candidate=-5.1271"),
            a["entries"]!!.split(','))
        assertFalse(lines.any { "selected" in it }, "nothing says which file plays: $lines")
        assertEquals(2, diag.assignments)
    }

    @Test fun `several songs of one answer keep their own ids and places, the answer's current one is only the answer's`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.1"))), track(uB, mp4 = listOf(file(fB1, "10", "-7.3"))), current = 1))
        val a = lines("answer").single()
        assertEquals("t2", a["answerCurrent"], "$a")
        assertEquals("2", a["tracks"])
        assertEquals("1", a["ans"])
        assertEquals(listOf("t1/queue/playlist:f1@10/mp4:candidate=-5.1000", "t2/cur/playlist:f2@10/mp4:candidate=-7.3000"), a["entries"]!!.split(','))
        later()
        diag.onPage(page("track", uA))
        val p = lines("track").single()
        assertEquals("t1", p["track"])
        assertEquals("f1@10/mp4:candidate=-5.1000(ans=1;page=1;role=queue;ctx=playlist;rxDev=local;here=same)|n=1", p["gain"])
    }

    @Test fun `gain null, absent, invalid and zero stay apart`() {
        on()
        relay(answer(track(uA, mp4 = listOf(
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
        on()
        relay(answer(track(uA, mp4 = listOf(file(null, "10", "-4.0")))))
        assertEquals("t1/cur/playlist:?@10/mp4:unknown=-4.0000", lines("answer").single()["entries"])
    }

    @Test fun `ads are counted, not kept`() {
        on()
        relay(answer(track("spotify:ad:ADSECRET", mp4 = listOf(file(fA1, "10", "-3.0")), type = "AD"), track(uA)))
        val a = lines("answer").single()
        assertEquals("1", a["ads"])
        assertEquals("ad", a["answerCurrent"])
        assertEquals(0, diag.assignments)
    }

    @Test fun `loudness is read from what the player holds, never asked for, and an unusable value is not called Spotify's`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0"))), track(uB)))
        later()
        diag.onPage(page("track", uA))
        assertEquals(listOf("unknown", "not-read"), lines("track").last().let { listOf(it["loud"], it["loudSt"]) })
        loud[uA] = Double.NaN
        later()
        diag.onPage(page("volume", uA))
        assertEquals("unusable", lines("volume").last()["loudSt"])
        loud[uB] = -7.25
        later()
        diag.onPage(page("track", uB))
        val cached = lines("track").last()
        assertEquals(listOf("cached", "-7.2500", "none"), listOf(cached["loudSt"], cached["loud"], cached["gain"]))
    }

    @Test fun `the page's raw player volume, missing, invalid or unreadable, is said as such`() {
        on()
        diag.onPage(page("track", uA, pv = "null", pvSt = "absent"))
        assertEquals(listOf("unknown", "absent"), lines("track").single().let { listOf(it["pv"], it["pvSt"]) })
        later()
        diag.onPage(page("volume", uA, pv = "null", pvSt = "invalid"))
        later()
        diag.onPage(page("volume", uA, pv = "null", pvSt = "error"))
        later()
        diag.onPage(page("volume", uA, pv = "0.0", pvSt = "ok"))
        assertEquals(listOf("invalid", "error", "ok"), lines("volume").map { it["pvSt"] })
        assertEquals("0.0000", lines("volume").last()["pv"])
    }

    // --- runs and pages ---------------------------------------------------------------------------------

    @Test fun `a late answer after a skip never becomes the current song's gain`() {
        on()
        val asked = ticket()
        diag.onPage(page("track", uB))
        later()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))), asked)
        later()
        diag.onPage(page("volume", uB))
        val b = lines("volume").single()
        assertEquals(listOf("t1", "none"), listOf(b["track"], b["gain"]), "A's gain is not B's: $b")
        assertEquals("t2", lines("answer").single()["answerCurrent"])
    }

    @Test fun `after a logout nothing of the old run or page is taken, neither answers nor page messages`() {
        on()
        val oldTicket = ticket()
        val oldTag = tag
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        assertEquals(1, diag.assignments)
        diag.newRun()
        assertEquals(0, diag.assignments, "a new account starts from nothing")
        // The old page is still up while it navigates away: its requests get no ticket, the answers to
        // its earlier ones and its messages are not taken.
        assertNull(diag.relayTicket(oldTag), "no page armed in the new run yet")
        relay(answer(track(uB, mp4 = listOf(file(fB1, "10", "-6.0")))), oldTicket)
        diag.onPage(page("track", uA, tag = oldTag))
        assertEquals(0, diag.assignments)
        // The new page comes up and is armed; a request the old page sent, delivered only now, is still the old page's.
        tag = diag.armPage()!!
        assertTrue(tag != oldTag)
        assertNull(diag.relayTicket(oldTag))
        assertNull(diag.relayTicket(0), "a page not armed: not read, not counted")
        later()
        diag.onPage(page("track", uB))
        val p = lines("track").single()
        assertEquals(listOf("2", "4", "none", "t1", "$tag"), listOf(p["run"], p["late"], p["gain"], p["track"], p["page"]))
    }

    @Test fun `a message buffered under the old arming is dropped after off and on`() {
        on()
        val oldTag = tag
        diag.setEnabled(false)
        on()
        diag.onPage(page("track", uA, tag = oldTag))
        diag.onPage(page("track", uA, tag = 0))
        later()
        diag.onPage(page("track", uA))
        val p = lines("track").single()
        assertEquals(listOf("2", "2"), listOf(p["run"], p["late"]))
    }

    @Test fun `a page that went away is taken no further, nor are answers to its requests`() {
        on()
        val asked = ticket()
        val oldTag = tag
        diag.pageGone()
        assertNull(diag.relayTicket(oldTag), "a request made while no page is armed is never read")
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))), asked)
        diag.onPage(page("track", uA, tag = oldTag))
        assertEquals(0, diag.assignments)
        tag = diag.armPage()!!
        later()
        diag.onPage(page("track", uA))
        assertEquals("3", lines("track").single()["late"])
    }

    @Test fun `another device playing is said, on the page line and with each answer's own`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        active = false
        val afterSwitch = ticket()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-6.0")))), afterSwitch)
        later()
        diag.onPage(page("track", uA))
        val p = lines("track").single()
        assertEquals("remote", p["device"])
        assertEquals("f1@10/mp4:candidate=-6.0000(ans=2;page=1;role=cur;ctx=playlist;rxDev=remote;here=same)" +
            "|alt=-5.0000(ans=1;page=1;role=cur;ctx=playlist;rxDev=local;here=same)|n=2", p["gain"])
        active = null
        later()
        diag.onPage(page("volume", uA))
        assertEquals("unknown", lines("volume").single()["device"])
    }

    @Test fun `the device of an answer is the host's cache when it arrives, also for one asked before a switch`() {
        on()
        val early = ticket()                                    // asked while this phone plays
        active = false                                          // another device takes over
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-6.0")))))
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))), early)   // the early one arrives last
        later()
        // Both "remote": a snapshot at receipt, not the device the early request was made for.
        assertEquals(listOf("2" to "remote", "1" to "remote"), lines("answer").map { it["ans"] to it["rxDevice"] })
        diag.onPage(page("track", uA))
        assertEquals("f1@10/mp4:candidate=-6.0000(ans=2;page=1;role=cur;ctx=playlist;rxDev=remote;here=same)" +
            "|alt=-5.0000(ans=1;page=1;role=cur;ctx=playlist;rxDev=remote;here=same)|n=2", lines("track").single()["gain"])
    }

    @Test fun `a line held back over a reload keeps the page it came from, apart from the new page's`() {
        on()                                                    // page 1; the start line
        diag.onPage(page("track", uA))                          // the second line of this second
        diag.onPage(page("volume", uA, pv = "0.5"))             // page 1's, held back
        diag.pageGone()
        tag = diag.armPage()!!                                  // page 2: the bridge's pl and el start at 1 again
        diag.onPage(page("track", uA, pv = "0.9"))              // page 2's, held back apart
        later()
        later()
        val told = lines("track", "volume")
        assertEquals(listOf("1:track:0.8000", "1:volume:0.5000", "2:track:0.9000"), told.map { "${it["page"]}:${it["ev"]}:${it["pv"]}" })
        assertTrue(told.all { it["pl"] == "1" && it["el"] == "1" && it["merged"] == "0" }, "$told")
    }

    // --- provenance ---------------------------------------------------------------------------------

    @Test fun `many reloads keep the waiting lines bounded, every event written or counted, each line with its own page`() {
        val written = mutableListOf<Pair<Long, String>>()
        val d = GainDiagnostics(output = { written += time.now to it }, schedule = time::schedule, clock = { time.now })
        d.setEnabled(true)
        var events = 1L                                         // the start line
        var most = 0
        val pages = 1_000
        // A page every 500 ms, each with a reading and an answer: twice what two lines a second can write.
        repeat(pages) {
            val t = d.armPage()!!
            d.onPage(page("volume", uA, tag = t, el = t.toInt()))
            d.onRelayAnswer(d.relayTicket(t)!!, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
            events += 2
            most = maxOf(most, d.waitingLines)
            time.advance(500)
            d.pageGone()
        }
        assertTrue(most <= GainDiagnostics.MAX_WAITING, "at most ${GainDiagnostics.MAX_WAITING} waiting, whatever the number of pages: $most")
        time.advance(10_000)                                    // no new events: what waits goes out
        assertEquals(0, d.waitingLines)
        assertEquals(0, time.pending)
        val f = written.map { fields(it.second) }
        val dropped = f.last()["dropped"]!!.toLong()
        assertTrue(dropped > 0, "the load is more than the rate: some are dropped")
        assertEquals(events, f.sumOf { 1L + it["merged"]!!.toLong() } + dropped, "every event written, merged or counted")
        // Each line with the page it came from: its el and its answer's request order are that page's tag.
        assertTrue(f.filter { it["ev"] == "volume" }.all { it["page"] == it["el"] })
        assertTrue(f.filter { it["ev"] == "answer" }.all { it["page"] == it["ans"] })
        assertTrue(f.any { it["page"] == "$pages" }, "the newest page's lines come out")
        // Never more than two lines in a second.
        for ((at, _) in written) assertTrue(written.count { it.first in at - 999..at } <= GainDiagnostics.LINES_PER_S, "at $at")
        // Off: nothing waits, nothing more is written.
        val t = d.armPage()!!
        repeat(5) { d.onPage(page("volume", uA, tag = t)) }
        assertTrue(d.waitingLines > 0)
        d.setEnabled(false)
        assertEquals(0, d.waitingLines)
        assertEquals(0, time.pending)
        val before = written.size
        time.advance(5_000)
        assertEquals(before, written.size)
    }

    @Test fun `the page's context is its own, also when only the context changes`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        later()
        diag.onPage(page("context", uA, ctx = "album"))
        val p = lines("context").single()
        assertEquals("album", p["ctx"])
        assertEquals("f1@10/mp4:candidate=-5.0000(ans=1;page=1;role=cur;ctx=playlist;rxDev=local;here=other)|n=1", p["gain"])
    }

    @Test fun `an answer's current song without a context says unknown, never another song's`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")), context = null),
            track(uB, mp4 = listOf(file(fB1, "10", "-6.0")), context = "spotify:album:ALBUMSECRET")))
        val a = lines("answer").single()
        assertEquals(listOf("t1", "unknown"), listOf(a["answerCurrent"], a["ctx"]))
        assertTrue(a["entries"]!!.contains("t2/queue/album:"), "$a")
    }

    @Test fun `a later answer from another context does not pass as the page's, both values stay visible`() {
        on()
        val first = ticket()
        val second = ticket()
        // The playlist answer was asked first and arrives last.
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-4.0")), context = "spotify:album:ALBUMSECRET")), second)
        relay(answer(track(uB), track(uA, mp4 = listOf(file(fA1, "10", "-7.0")))), first)
        later()
        diag.onPage(page("track", uA, ctx = "album"))
        assertEquals("f1@10/mp4:candidate=-4.0000(ans=2;page=1;role=cur;ctx=album;rxDev=local;here=same)" +
            "|alt=-7.0000(ans=1;page=1;role=queue;ctx=playlist;rxDev=local;here=other)|n=2", lines("track").single()["gain"])
        // Asked last, from the playlist: the newest by request order, and marked as another context's.
        relay(answer(track(uB), track(uA, mp4 = listOf(file(fA1, "10", "-7.0")))))
        later()
        diag.onPage(page("volume", uA, ctx = "album"))
        assertTrue(lines("volume").single()["gain"]!!.startsWith("f1@10/mp4:candidate=-7.0000(ans=3;page=1;role=queue;ctx=playlist;rxDev=local;here=other)|alt=-4.0000"),
            "${lines("volume")}")
    }

    @Test fun `ids held by an assignment are never given away, however many songs pass`() {
        on()
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        repeat(GainDiagnostics.MAX_ALIASES + 100) { diag.onPage(page("track", "spotify:track:PASSING$it")) }
        time.advance(5_000)
        diag.onPage(page("volume", uA))
        val p = lines("volume").single()
        assertEquals("t1", p["track"])
        assertTrue(p["gain"]!!.startsWith("f1@"), "$p")
        assertTrue(p["aliasEvicted"]!!.toInt() > 0, "the passing ones are counted: $p")
        assertEquals("1", p["table"])
    }

    // --- bounds ---------------------------------------------------------------------------------------

    @Test fun `257 assignments keep the newest 256`() {
        on()
        relay(answer(track(uA, mp4 = (0 until 257).map { i -> file("%040x".format(i + 1), "10", "-1.0") })))
        assertEquals(GainDiagnostics.MAX_ENTRIES, diag.assignments)
        val a = lines("answer").single()
        assertEquals(listOf("1", "256"), listOf(a["evicted"], a["table"]))
        assertTrue(a["more"]!!.toInt() > 0, "a long answer is shortened: $a")
        assertTrue(lines.single { "ev=answer" in it }.length < 2_000, "one bounded line")
    }

    @Test fun `one answer is read up to its bound, a page line names a bounded number of candidates`() {
        on()
        relay(answer(track(uA, mp4 = (0 until GainDiagnostics.MAX_PER_ANSWER + 88).map { i -> file("%040x".format(i + 1), "10", "-1.0") })))
        assertEquals("88", lines("answer").single()["capped"])
        later()
        diag.onPage(page("track", uA))
        val p = lines("track").single()
        assertEquals(GainDiagnostics.MAX_SHOWN, p["gain"]!!.split(',').size)
        assertEquals("${GainDiagnostics.MAX_ENTRIES - GainDiagnostics.MAX_SHOWN}", p["gainMore"])
        assertTrue(lines.last().length < 4_000, "one bounded line: ${lines.last().length}")
    }

    @Test fun `a page line stays whole in Logcat, also with every candidate in conflict`() {
        on()
        // Four answers, each with 20 files of one song at another gain and from another context: every
        // candidate keeps four observations, all of them differing.
        listOf("album", "playlist", "artist", "station").forEachIndexed { n, kind ->
            relay(answer(track(uA, mp4 = (0 until 20).map { i -> file("%040x".format(i + 1), "123", "-1234${n}.123456") },
                context = "spotify:$kind:CONTEXTSECRET")))
            later()
        }
        diag.onPage(page("track", uA, ctx = "show", pv = "1e300", level = "1e300"))
        val line = lines.last()
        val p = fields(line)
        val shown = p["gain"]!!.split(',')
        assertTrue(line.length < 4_000, "${line.length}: $line")
        assertTrue(shown.size in 1 until GainDiagnostics.MAX_SHOWN, "the length bounds it first: ${shown.size}")
        assertTrue(shown.all { it.endsWith("|n=4") && it.split("|alt=").size == 4 }, "whole candidates only: $shown")
        assertEquals("${20 - shown.size}", p["gainMore"])
        assertEquals(listOf("out-of-range", "out-of-range"), listOf(p["pv"], p["level"]))
    }

    @Test fun `a flood of events gives at most two lines a second, the rest merged and counted`() {
        on() // the start line is the first of this second
        repeat(1_000) { diag.onPage(page("volume", uA)) }
        assertEquals(2, lines.size, "start and one more in the first second: $lines")
        assertEquals(1, time.pending, "one flush waiting, no queue")
        later()
        assertEquals(3, lines.size)
        assertEquals("998", fields(lines.last())["merged"], "1000 events: one written, one written later, 998 folded into it")
        time.advance(5_000)
        assertEquals(3, lines.size, "nothing more without new events")
        assertEquals(0, time.pending)
    }

    @Test fun `answers and page events wait apart, a flood of one never hides the other`() {
        on()
        repeat(50) { diag.onPage(page("volume", uA)) }
        relay(answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        later()
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
        on()
        time.advance(3_000)
        assertEquals(listOf("2"), lines.drop(before).map { fields(it)["run"] }, "only the new run's start")
        assertEquals(0, diag.assignments)
    }

    @Test fun `malformed page messages are counted and never written, later ones still are`() {
        on()
        listOf(
            """{"type":"diag","tag":$tag}""",
            """{"type":"diag","tag":$tag,"ev":"nonsense"}""",
            """{"type":"diag","tag":$tag,"ev":{"x":1}}""",
            """{"type":"diag","ev":"track"}""",
            """{"type":"diag","tag":"$tag","ev":"track"}""",
            """{"type":"diag","tag":$tag,"ev":"track","uri":["spotify:track:LISTSECRET"],"el":"x","pv":"loud","vol":"x","merged":-5,"fault":"FAULTSECRET"}""",
        ).map { Json.parseToJsonElement(it).jsonObject }.forEach(diag::onPage)
        later()
        diag.onPage(page("track", uA))
        assertEquals("5", lines("track").last()["bad"], "five without a known event or tag; the sixth is read with its odd fields unknown: $lines")
        assertFalse(lines.any { "SECRET" in it })
        assertTrue(lines("track").any { it["track"] == "unknown" && it["el"] == "0" && it["vol"] == "unknown" && it["pageFault"] == "none" }, "$lines")
    }

    @Test fun `an unreadable answer or a failing source never reaches the caller`() {
        on()
        relay("not json")
        relay("{\"state_machine\":[1,2]}")
        relay("[]")
        val failing = GainDiagnostics(output = { error("log gone") }, schedule = time::schedule, clock = { time.now },
            loudness = { error("cache gone") }, app = { error("settings gone") }, device = { error("no media") })
        failing.setEnabled(true)
        val t = failing.armPage()!!
        failing.onRelayAnswer(failing.relayTicket(t)!!, path, answer(track(uA, mp4 = listOf(file(fA1, "10", "-5.0")))))
        failing.onPage(page("track", uA, tag = t))
        assertEquals(1, failing.assignments)
    }

    @Test fun `a large answer and page messages at the same time, nothing lost, nothing thrown`() {
        on()
        val big = answer(*(0 until 300).map { i -> track("spotify:track:BIG$i", mp4 = listOf(file("%040x".format(i + 1), "10", "-2.0"))) }.toTypedArray())
        val pool = Executors.newSingleThreadExecutor()
        try {
            val answers = pool.submit(Runnable { repeat(20) { relay(big) } })
            repeat(200) { diag.onPage(page("volume", uA)) }
            answers.get()
        } finally {
            pool.shutdown()
        }
        time.advance(5_000)
        assertEquals(GainDiagnostics.MAX_ENTRIES, diag.assignments)
        // Every event got its place in the run and the newest of each kind was written: seq 0 is the start, 220 the last event.
        assertEquals(220, lines.maxOf { fields(it)["seq"]!!.toInt() })
    }

    // --- privacy ----------------------------------------------------------------------------------------

    @Test fun `no token, cookie, URL, Spotify id or unknown manifest name reaches a line`() {
        on()
        relay(answer(
            track(uA, mp4 = listOf(file(fA1, "10", "-5.0")), dual = listOf(file(fA2, "12", "-5.0")),
                extraLists = ""","file_ids_synthetic_key_secret":[${file("dddddddddddddddddddddddddddddddddddddd01", "10", "-3.0")}]"""),
            track(uB, mp4 = listOf(file(fB1, "10", "-6.0"), file(fB2, "11", "-6.0")), context = "spotify:user:USERSECRET:collection"),
        ))
        assertEquals("1", lines("answer").single()["otherLists"])
        later()
        diag.onPage(page("track", uA, extra = ""","token":"TOKEN_SECRET","cookie":"sp_dc=COOKIE_SECRET","url":"https://x.example/?a=SECRETURL""""))
        later()
        diag.onPage(page("element", "spotify:track:ELEMENTSECRET", el = 2))
        assertTrue(lines.size >= 3, "$lines")
        val forbidden = listOf("SECRET", "synthetic", "TOKEN", "COOKIE", "sp_dc", "http", "spotify:", fA1, fA2, fB1, fB2, "aaaaaaaa", "bbbbbbbb", "dddddddd")
        for (l in lines) for (f in forbidden) assertFalse(l.contains(f, ignoreCase = true), "'$f' in: $l")
    }

    // --- no part in playback ------------------------------------------------------------------------

    /** One call the player made: its method and its arguments, as sent. */
    private data class Call(val method: String, val args: String)

    /** The page as the player sees it: every call it gets is recorded in order and answered with null. */
    private class RecordingHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 256)
        val calls = CopyOnWriteArrayList<Call>()
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            calls += Call(m.groupValues[2], m.groupValues[3])
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":null}""")
        }
    }

    private fun stateOf(name: String, paused: Boolean = false) =
        """{"type":"state","state":{"track":{"kind":"track","uri":"spotify:track:$name","uid":"u$name","name":"$name","durationMs":200000},
           |"contextUri":"spotify:playlist:C","next":null,"isPaused":$paused,"isBuffering":false,"positionMs":0,
           |"timestamp":${System.currentTimeMillis()},"restrictions":{}}}""".trimMargin()

    /** Reads of the page's state, made on timers, and the diagnostics' own call: not playback commands. */
    private val notPlayback = setOf("media.state", "volume.get", "player.state", "player.queue", "device.activeNow", "bridge.version",
        "player.audioInfo", "drm.probe", "diag.gain")

    private fun playback(calls: List<Call>) = calls.filter { it.method !in notPlayback }

    /** Same commands, same arguments, same order; only reads and the diagnostics' call may differ. */
    private fun assertSamePlayback(off: List<Call>, on: List<Call>) =
        assertEquals(playback(off), playback(on), "playback commands differ with the diagnostics on")

    /**
     * The same page events and user actions with the diagnostics on or off, the player on one thread
     * (so the order is the program's, not the scheduler's), each step once the one before has
     * settled, recorded up to a defined end: the pause asked for, then reported by the page.
     * [extra] runs before the pause (sensitivity checks).
     */
    private fun callsWith(diagnostics: Boolean, extra: suspend (Bridge) -> Unit = {}): List<Call> = runBlocking {
        val pool = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher())
        try {
            val host = RecordingHost()
            // Settled: no new playback command for 300 ms (reads on timers do not count).
            suspend fun settle() = withTimeout(10_000) {
                var seen = -1
                while (seen != playback(host.calls).size) { seen = playback(host.calls).size; delay(300) }
            }
            val bridge = Bridge(host, scope)
            val player = SpotifyPlayer(bridge, scope)
            withTimeout(10_000) { host.messages.subscriptionCount.first { it > 0 } }
            player.setGainDiagnostics(diagnostics)
            host.messages.emit("""{"type":"ready"}""")
            withTimeout(10_000) { bridge.ready.first { it } }
            settle()
            host.messages.emit(stateOf("A"))
            withTimeout(10_000) { player.state.first { it?.item?.uri == "spotify:track:A" } }
            settle()
            // What a page tells while the diagnostics are on (sent whether or not they are read).
            host.messages.emit("""{"type":"diag","v":2,"tag":1,"ev":"track","uri":"spotify:track:A","pl":1,"el":1,"pv":0.8,"pvSt":"ok","vol":0.5,"merged":0}""")
            host.messages.emit(stateOf("B"))
            withTimeout(10_000) { player.state.first { it?.item?.uri == "spotify:track:B" } }
            settle()
            extra(bridge)
            player.pause()
            host.messages.emit(stateOf("B", paused = true))
            withTimeout(10_000) { player.state.first { it?.isPaused == true } }
            settle()
            host.calls.toList()
        } finally {
            scope.cancel()
            pool.shutdown()
        }
    }

    @Test fun `with the diagnostics on the player sends the same playback commands, arguments and order as with them off`() {
        val off = callsWith(false)
        val on = callsWith(true)
        assertTrue(playback(off).any { it.method == "player.pause" }, "$off")
        assertSamePlayback(off, on)
        assertEquals(0, off.count { it.method == "diag.gain" })
        val armed = on.filter { it.method == "diag.gain" }
        assertEquals(1, armed.size, "armed once in the page that came up: $on")
        assertTrue(armed.single().args.contains("\"on\":true") && armed.single().args.contains("\"tag\":"), "$armed")
    }

    @Test fun `the comparison catches swapped commands, changed arguments and an added command`() {
        val base = listOf(Call("player.pause", "{}"), Call("media.volume", """{"level":0.5}"""), Call("media.state", "{}"))
        assertSamePlayback(base, base + Call("diag.gain", """{"on":true,"tag":1}""") + Call("media.state", "{}"))
        assertThrows(AssertionFailedError::class.java) { assertSamePlayback(base, listOf(base[1], base[0], base[2])) }
        assertThrows(AssertionFailedError::class.java) { assertSamePlayback(base, listOf(base[0], Call("media.volume", """{"level":0.6}"""), base[2])) }
        assertThrows(AssertionFailedError::class.java) { assertSamePlayback(base, base + Call("player.seek", """{"positionMs":0}""")) }
    }

    @Test fun `the comparison catches a command added or changed in a real run`() {
        fun volume(level: Double): suspend (Bridge) -> Unit = { b -> b.call("media.volume", buildJsonObject { put("level", level) }) }
        val off = callsWith(false, volume(0.5))
        assertSamePlayback(off, callsWith(true, volume(0.5)))
        assertThrows(AssertionFailedError::class.java) { assertSamePlayback(off, callsWith(true, volume(0.6))) }
        assertThrows(AssertionFailedError::class.java) { assertSamePlayback(callsWith(false), callsWith(true, volume(0.5))) }
        assertNotNull(off.firstOrNull { it.method == "media.volume" })
    }
}
