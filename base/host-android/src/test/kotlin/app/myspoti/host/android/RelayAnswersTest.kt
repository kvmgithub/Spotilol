package app.myspoti.host.android

import app.myspoti.spotify.GainDiagnostics
import app.myspoti.spotify.bridge.AdBlock
import okhttp3.Headers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The relay's last step ([RelayAnswers], the code [ControlRelay] runs for every answer) with the
 * gain diagnostics off and on: the page gets the same answer once, whatever the diagnostics do;
 * they read the version the page was handed, for the page that asked.
 */
class RelayAnswersTest {
    private val path = "/track-playback/v1/devices/0123456789abcdef0123456789abcdef01234567/state"
    private val headers = Headers.headersOf(
        "content-type", "application/json; charset=utf-8",
        "Set-Cookie", "sp_t=COOKIE_SECRET; Path=/",
        "x-spotify-test", "1",
    )

    private fun answer(vararg tracks: String, current: Int = 0) =
        """{"state_machine":{"state_machine_id":"m","tracks":[${tracks.joinToString(",")}],""" +
            """"states":[${tracks.indices.joinToString(",") { """{"state_id":"s$it","track":$it,"transitions":{}}""" }}],"attributes":{}},""" +
            """"updated_state_ref":{"state_index":$current,"paused":false}}"""

    private fun song(uri: String, gain: String) =
        """{"content_type":"TRACK","metadata":{"uri":"$uri","context_uri":"spotify:album:X"},""" +
            """"manifest":{"file_ids_mp4":[{"file_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1","format":"10","gain_db":$gain}]}}"""

    // An ad the song's end leads to, its skip to the next song (AdQueueTest's first shape): AdBlock
    // sends the end to the next song as well.
    private val withAd = run {
        fun go(to: Int?) = if (to == null) "null" else """{"state_index":$to,"paused":false,"active_alias":null}"""
        fun state(track: Int, advance: Int? = null, next: Int? = null, show: Int? = null) =
            """{"state_id":"s$track","track":$track,"transitions":{"skip_next":${go(next)},"skip_prev":null,"show_next":${go(show)},"show_prev":null,"advance":${go(advance)}}}"""
        val tracks = listOf(
            """{"content_type":"AD","metadata":{"uri":"spotify:ad:An-ad","name":"An ad"},"manifest":{}}""",
            song("spotify:track:Before", "-3.0"), song("spotify:track:Playing", "-5.0"), song("spotify:track:After", "-7.0"),
        )
        val states = listOf(state(0), state(1), state(2, advance = 0, next = 4, show = 4), state(2), state(3))
        """{"state_machine":{"state_machine_id":"m","tracks":[${tracks.joinToString(",")}],"states":[${states.joinToString(",")}],"attributes":{}},""" +
            """"updated_state_ref":{"state_index":2,"paused":false}}"""
    }

    /** One relay, with what it handed back, what it logged, and what the diagnostics read and wrote. */
    private class Relay(diag: GainDiagnostics?, failTicket: Boolean = false, failRead: Boolean = false) {
        val replies = mutableListOf<Triple<Int, String, String>>()
        val logged = mutableListOf<String>()
        val read = mutableListOf<String>()
        // As ControlRelay wires it, with the diagnostics given here instead of MyspotiRuntime's.
        val relay = RelayAnswers(
            log = { logged += it },
            tickets = { tag -> if (failTicket) error("ticket broken SECRET") else diag?.relayTicket(tag) },
            observe = { ticket, path, body -> read += body; if (failRead) error("diagnostics broken SECRET"); diag?.onRelayAnswer(ticket, path, body) },
        )

        /** A request of the page with [pageTag] as it reaches the relay, then Spotify's answer to it. */
        fun exchange(pageTag: Long, path: String, code: Int, headers: Headers, text: String, into: MutableList<Triple<Int, String, String>> = replies) {
            val ticket = relay.ticket(JSONObject().put("id", 1).put("url", "https://gew4-spclient.spotify.com$path").put("gain", pageTag))
            relay.deliver(path, code, headers, text, ticket) { status, h, body -> into += Triple(status, h, body) }
        }
    }

    private val lines = mutableListOf<String>()
    private fun diagnostics(on: Boolean) = GainDiagnostics(output = { lines += it }, schedule = { _, _ -> {} }, clock = { 0L })
        .also { it.setEnabled(on) }

    private fun fields(line: String) = line.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Before fun noAdAhead() { AdBlock.ahead.value = null }

    @Test fun `the same answer goes back once and unchanged, with the diagnostics off or on`() {
        val text = answer(song("spotify:track:A", "-5.127103"))
        val off = Relay(diagnostics(false)).apply { exchange(1, path, 200, headers, text) }
        val diag = diagnostics(true)
        val tag = diag.armPage()!!
        val on = Relay(diag).apply { exchange(tag, path, 200, headers, text) }
        assertEquals(off.replies, on.replies)
        assertEquals(1, on.replies.size)
        val (status, h, body) = on.replies.single()
        assertEquals(200, status)
        assertEquals("the very body Spotify sent", text, body)
        val sentHeaders = JSONObject(h)
        assertEquals("application/json; charset=utf-8", sentHeaders.getString("content-type"))
        assertEquals("1", sentHeaders.getString("x-spotify-test"))
        assertFalse("no cookie handed to the page", h.contains("COOKIE_SECRET"))
        assertEquals(off.logged, on.logged)
        assertEquals(listOf(text), on.read)
        assertTrue(off.read.isEmpty())
        assertTrue(lines.any { it.contains("ev=answer page=$tag") && it.contains("candidate=-5.1271") })
    }

    @Test fun `only a successful track-playback answer is read`() {
        val diag = diagnostics(true)
        val tag = diag.armPage()!!
        val r = Relay(diag)
        r.exchange(tag, "/connect-state/v1/devices/hobs_0123456789abcdef", 200, headers, "{}")
        r.exchange(tag, path, 404, headers, answer(song("spotify:track:A", "-5.0")))
        assertEquals(2, r.replies.size)
        assertTrue(r.read.isEmpty())
        assertFalse(lines.any { "ev=answer" in it })
    }

    @Test fun `a failing diagnostics read leaves the answer as it is, and nothing more goes back`() {
        val text = answer(song("spotify:track:A", "-5.0"))
        val off = Relay(diagnostics(false)).apply { exchange(1, path, 200, headers, text) }
        val diag = diagnostics(true)
        val tag = diag.armPage()!!
        // The ticket fails: the answer is not read. The reading fails: nothing comes of it. Neither
        // throws, nor adds an answer (RelayAnswers has no HTTP client: no request either).
        val noTicket = Relay(diag, failTicket = true).apply { exchange(tag, path, 200, headers, text) }
        val failing = Relay(diag, failRead = true).apply { exchange(tag, path, 200, headers, text) }
        for (r in listOf(noTicket, failing)) {
            assertEquals(off.replies, r.replies)
            assertEquals(off.logged, r.logged)
        }
        assertTrue(noTicket.read.isEmpty())
        assertEquals(listOf(text), failing.read)
        assertFalse(lines.any { "SECRET" in it })
    }

    @Test fun `an answer with an ad goes back as AdBlock leaves it, on or off, and the diagnostics read that version`() {
        val filtered = AdBlock.withoutAds(withAd)!!.first
        val off = Relay(diagnostics(false)).apply { exchange(1, path, 200, headers, withAd) }
        AdBlock.ahead.value = null
        val diag = diagnostics(true)
        val tag = diag.armPage()!!
        val on = Relay(diag).apply { exchange(tag, path, 200, headers, withAd) }
        assertEquals(off.replies, on.replies)
        assertEquals(filtered, on.replies.single().third)
        assertNotEquals(withAd, filtered)
        assertEquals(off.logged, on.logged)
        assertTrue(on.logged.any { it.startsWith("ad left out: ") })
        assertEquals(listOf(on.replies.single().third), on.read)
        assertTrue(lines.any { it.contains("ev=answer page=$tag") && it.contains("ads=1") })
    }

    @Test fun `after a reload the answer to the old page's request goes to the old page and is not read`() {
        val diag = diagnostics(true)
        val first = diag.armPage()!!
        val r = Relay(diag)
        val ticket = r.relay.ticket(JSONObject().put("gain", first))
        assertNotNull(ticket)
        diag.pageGone()
        val second = diag.armPage()!!
        val oldPage = mutableListOf<Triple<Int, String, String>>()
        r.relay.deliver(path, 200, headers, answer(song("spotify:track:A", "-5.0")), ticket) { s, h, b -> oldPage += Triple(s, h, b) }
        assertEquals(1, oldPage.size)
        assertEquals(1, r.read.size)
        // The new page's answer is read; the old one counted late, never kept.
        r.exchange(second, path, 200, headers, answer(song("spotify:track:B", "-6.0")))
        val line = fields(lines.last { "ev=answer" in it })
        assertEquals(listOf("$second", "1", "1"), listOf(line["page"], line["late"], line["table"]))
    }

    @Test fun `a request the old page sent, delivered after the new page was armed, gets no ticket`() {
        val diag = diagnostics(true)
        val first = diag.armPage()!!
        diag.pageGone()
        val second = diag.armPage()!!
        val r = Relay(diag)
        assertNull("the old page's request", r.relay.ticket(JSONObject().put("gain", first)))
        assertNull("a page never armed", r.relay.ticket(JSONObject().put("gain", 0)))
        assertNull("a request without a tag", r.relay.ticket(JSONObject().put("id", 3)))
        assertNotNull("the new page's request", r.relay.ticket(JSONObject().put("gain", second)))
        // Its answer still goes back to the page that asked, unread.
        r.exchange(first, path, 200, headers, answer(song("spotify:track:A", "-5.0")))
        assertEquals(1, r.replies.size)
        assertTrue(r.read.isEmpty())
        r.exchange(second, path, 200, headers, answer(song("spotify:track:B", "-6.0")))
        assertEquals("2", fields(lines.last { "ev=answer" in it })["late"])
    }
}
