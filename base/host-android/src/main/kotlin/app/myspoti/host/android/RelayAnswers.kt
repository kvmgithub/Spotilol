package app.myspoti.host.android

import app.myspoti.spotify.bridge.AdBlock
import okhttp3.Headers
import org.json.JSONObject

/**
 * The relay's last step for an answer Spotify gave, and the gain diagnostics' part in the relay:
 * apart from [ControlRelay], which needs a WebView, so a JVM test runs the code the phone runs.
 *
 * [ticket] is taken at the relay's entry, from the page tag the request itself carries ("gain", put
 * there by the relay's fetch wrapper from the bridge's `window.__mspGainTag`): an answer is read for
 * the page that asked, never for whichever page is armed when it arrives. [deliver] builds what
 * goes back to the page (the headers without Set-Cookie; a track-playback answer without the ads
 * [AdBlock.withoutAds] leaves out), hands it once to [deliver]'s reply (the sending document's), and
 * only then lets the gain diagnostics read the body as it was handed back. Nothing the diagnostics
 * do, failing included, reaches the answer, the log or the network: they get a copy of nothing but
 * a String, inside runCatching, and this class has no HTTP client.
 */
internal class RelayAnswers(
    private val log: (String) -> Unit,
    /** The gain diagnostics' ticket for a request of the page with this tag (null: not read). */
    private val tickets: (pageTag: Long) -> Long?,
    /** The gain diagnostics reading an answer the page was handed. */
    private val observe: (ticket: Long, path: String, body: String) -> Unit,
) {
    /** The last reason an ad was kept in a queue (see [AdBlock.whyKept]): logged once per reason. */
    @Volatile private var lastAdKept: String? = null

    /** The gain diagnostics' ticket of a relayed request [message] (`{..., gain}`); null when it is not read. */
    fun ticket(message: JSONObject): Long? = runCatching { tickets(message.optLong("gain", 0L)) }.getOrNull()

    /**
     * Hands Spotify's answer ([code], [headers], [text]) for [path] to [reply] once, then shows it
     * to the gain diagnostics under [ticket] (a successful track-playback answer only).
     */
    fun deliver(path: String, code: Int, headers: Headers, text: String, ticket: Long?, reply: (status: Int, headersJson: String, body: String) -> Unit) {
        val out = JSONObject()
        headers.names().forEach { name ->
            if (!name.equals("Set-Cookie", true) && !name.equals("Set-Cookie2", true)) out.put(name, headers[name])
        }
        // An ad in the queue Spotify sends: the song before it goes on to the next song
        // instead, before the page sees the queue (AdBlock.withoutAds, Myspoti 2.0.0).
        val playback = path.contains("/track-playback/")
        val given = if (playback) AdBlock.withoutAds(text) else null
        given?.second?.forEach { note -> log("ad left out: $note") }
        // Marked as holding an ad, yet left as it was: why (which marker, what kept it), once per reason.
        if (playback && given == null) AdBlock.whyKept(text)?.takeIf { it != lastAdKept }?.let { why -> lastAdKept = why; log("ad kept in the queue: $why") }
        val sent = given?.first ?: text
        // Both ways on from this song lead to the ad: the player plays the next song
        // just before this one ends (AdBlock.adAhead).
        if (playback) AdBlock.adAhead(sent)?.let { a ->
            if (AdBlock.ahead.value != a) log("ad ahead: ${a.nextUri} is played before the end")
            AdBlock.ahead.value = a
        }
        reply(code, out.toString(), sent)
        // Gain diagnostics: what the page was handed, read after; nothing of theirs reaches the answer or this relay.
        if (ticket != null && playback && code in 200..299) runCatching { observe(ticket, path, sent) }
    }
}
