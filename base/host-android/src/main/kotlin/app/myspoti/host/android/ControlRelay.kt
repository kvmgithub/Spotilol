package app.myspoti.host.android

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.security.BoundedHttp
import app.myspoti.spotify.security.LogRedactor
import app.myspoti.spotify.security.WebPolicy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sends the Web Player's playback control requests (Spotify Connect commands, transfers,
 * device and track-playback state) from the app instead of the WebView.
 *
 * Every WebView request carries "X-Requested-With: <package>". Spotify acknowledges Connect
 * commands that carry it but never delivers them to the player, so nothing plays. The page's
 * `fetch` is wrapped at document start; matching requests go through `MyspotiNet.postMessage`
 * and are repeated with OkHttp using the page's headers.
 *
 * Spotilol's native transport rules apply: exact HTTPS spclient hosts and paths
 * ([WebPolicy.isRelayTarget]), bounded requests and responses, a total call deadline, no
 * redirects, Set-Cookie never stored nor returned, and the answer goes back through the sending
 * document's reply proxy, never to whatever page is current.
 *
 * From Myspoti 2.0.0-beta.9: the relayed request presents the same browser as the page (its
 * user agent, client hints and languages, plus Chromium's Sec-Fetch headers). Cookies are sent
 * only when the page's own fetch asked for them (`credentials: 'include'`), exactly as Chromium
 * would; the device registration keeps the Web Player's own name.
 */
internal class ControlRelay(private val view: WebView, private val fingerprint: Fingerprint) {
    /** The page's player registered its playback device (it started, and takes commands). */
    @Volatile var onDeviceRegistered: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val http: OkHttpClient = BoundedHttp.client(callTimeoutSeconds = 20).newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The identity the page presents (user agent, client hints, languages); null until it says. */
    @Volatile private var identity: Map<String, String>? = null

    private val fallbackLanguage: String by lazy {
        Fingerprint.acceptLanguage(android.os.LocaleList.getDefault().toLanguageTags().split(','))
    }

    fun install() {
        view.exposeToPlayer("MyspotiNet", MAX_REQUEST_BYTES, ::onMessage)
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf(WebPolicy.PLAYER_ORIGIN))
        Logbook.log("control relay installed")
    }

    /** `{id, url, method, headers, body, credentials}` from the page's wrapped `fetch`, or its identity. */
    private fun onMessage(json: String, reply: JavaScriptReplyProxy) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (m.optString("type") == "identity") {
            identity = readIdentity(m.optJSONObject("value"))
            Logbook.log("page identity: ${identity?.get("userAgent")?.take(160)}")
            return
        }
        // A refused command sent again from the page itself, and whether that way was kept (Myspoti 2.0.1).
        if (m.optString("type") == "direct") {
            if (m.has("kept")) Logbook.log("commands from the page: ${if (m.optBoolean("kept")) "kept, the music plays" else "dropped, nothing played in 10 s"}")
            else Logbook.log("refused command sent again from the page -> ${m.optInt("status")}")
            return
        }
        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: return
        val request = runCatching { build(m) }.getOrElse {
            Logbook.log("relay #$id rejected: ${it.message}")
            return answer(reply, id, 0, "{}", "")
        }
        send(id, request, if (m.isNull("body")) null else m.optString("body"), reply)
    }

    /** The page's identity, bounded and limited to printable header-safe values. */
    private fun readIdentity(o: JSONObject?): Map<String, String>? {
        o ?: return null
        val out = HashMap<String, String>()
        for (key in listOf("userAgent", "brands", "mobile", "platform", "acceptLanguage")) {
            val v = o.optString(key)
            if (v.isNotEmpty() && v.length <= 512 && v.all { it.code in 0x20..0x7e }) out[key] = v
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The validated OkHttp request, or an exception naming the rule it broke. */
    private fun build(m: JSONObject): Request {
        val url = m.optString("url")
        require(WebPolicy.isRelayTarget(url)) { "untrusted target" }
        require(!AdBlock.isBlocked(url)) { "blocked target" }
        val method = m.optString("method", "GET").uppercase(Locale.ROOT)
        require(method in METHODS) { "method" }
        val given = m.optJSONObject("headers") ?: JSONObject()
        require(given.length() <= 64) { "too many headers" }
        val headers = Headers.Builder()
        var headerBytes = 0
        given.keys().forEach { key ->
            val lower = key.lowercase(Locale.ROOT)
            if (lower in BLOCKED_HEADERS || lower.startsWith("sec-") || lower.startsWith("proxy-") || given.isNull(key)) return@forEach
            val value = given.getString(key)
            headerBytes += key.length + value.length
            require(headerBytes <= MAX_HEADER_BYTES) { "headers too large" }
            headers.add(key, value)
        }
        val page = identity
        fun fromPage(name: String, fallback: String) = page?.get(name) ?: fallback
        headers["User-Agent"] = fromPage("userAgent", fingerprint.userAgent)
        headers["Origin"] = WebPolicy.PLAYER_ORIGIN
        headers["Referer"] = "${WebPolicy.PLAYER_ORIGIN}/"
        // What Chromium adds by itself to the page's cross-origin fetch (JS cannot set these).
        headers["Sec-Fetch-Site"] = "same-site"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Dest"] = "empty"
        headers["Accept-Language"] = fromPage("acceptLanguage", fallbackLanguage)
        headers["sec-ch-ua"] = fromPage("brands", fingerprint.secChUa)
        headers["sec-ch-ua-mobile"] = fromPage("mobile", "?0")
        headers["sec-ch-ua-platform"] = "\"${fromPage("platform", "Linux")}\""
        // Cookies only where the browser itself would send them: a fetch with credentials
        // 'include'. Read for this exact URL from the WebView's store; nothing is ever written back.
        if (m.optString("credentials") == "include") {
            android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        }
        val body = if (m.isNull("body")) null else m.optString("body")
        require((body?.length ?: 0) <= MAX_REQUEST_BYTES) { "body too large" }
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = when (method) {
            "GET", "HEAD" -> { require(body.isNullOrEmpty()) { "body on $method" }; null }
            else -> (body ?: "").toByteArray().toRequestBody(null)
        }
        return Request.Builder().url(url).headers(headers.build()).method(method, payload).build()
    }

    private fun send(id: Int, request: Request, body: String?, reply: JavaScriptReplyProxy) {
        val asked = android.os.SystemClock.uptimeMillis()
        val label = "${request.method} ${shortPath(request.url.toString())}"
        // The device the previous page left (app swiped away or killed before its unload handler
        // ran) is taken off the account alongside this registration (Myspoti 2.0.2): no idle
        // "Web Player" copy of this phone stays among the account's devices.
        if (isRegistration(request)) {
            body?.let { b -> runCatching { JSONObject(b).optJSONObject("device")?.optString("device_id") }.getOrNull() }
                ?.takeIf { DEVICE_ID.matches(it) }
                ?.let { mine -> devices.registering(mine)?.let { (old, seq) -> sayGoodbye(request, old, seq) } }
        } else if (request.method != "DELETE") devices.sent(request.url.toString(), body)
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                MyspotiRuntime.network.failed()
                Logbook.log("relay $label failed: ${e.javaClass.simpleName}")
                answer(reply, id, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                MyspotiRuntime.network.succeeded()
                response.use {
                    if (it.isRedirect || it.code in 300..399) {
                        Logbook.log("relay $label redirect ${it.code} rejected")
                        return answer(reply, id, 0, "{}", "")
                    }
                    val text = runCatching { BoundedHttp.text(it.body, MAX_RESPONSE_BYTES) }.getOrElse { e ->
                        Logbook.log("relay $label response rejected: ${e.message}")
                        return answer(reply, id, 0, "{}", "")
                    }
                    val out = JSONObject()
                    it.headers.names().forEach { name ->
                        if (!name.equals("Set-Cookie", true) && !name.equals("Set-Cookie2", true)) out.put(name, it.header(name))
                    }
                    val net = android.os.SystemClock.uptimeMillis() - asked
                    Logbook.log("relay $label -> ${it.code} in $net ms")
                    if (it.code >= 400) Logbook.log("relay error body: ${LogRedactor.redact(text.take(400))}")
                    val path = request.url.encodedPath
                    val registration = isRegistration(request)
                    if (it.code in 200..299 && registration) onDeviceRegistered?.invoke()
                    if (it.code in 200..299 && registration) runCatching { JSONObject(text).optLong("initial_seq_num", 0) }.getOrNull()?.let(devices::answered)
                    if (it.code in 200..299 && request.method == "DELETE" && path.contains("/track-playback/v1/devices/")) devices.deregistered(request.url.toString())
                    // 402 (refused playback on some free accounts), from Myspoti 2.0.0: the refusal's
                    // own headers and the refused command's shape. No ids, no cookies.
                    if (it.code == 402) {
                        val heads = it.headers.names().filterNot { n -> n.equals("set-cookie", true) || n.startsWith("authorization", true) }
                            .joinToString(", ") { n -> "$n=${it.header(n)?.take(80)}" }
                        Logbook.log("refusal headers: $heads")
                        if (body != null) Logbook.log("refused command: ${commandSummary(body)}")
                    }
                    if (registration) Regex("\"effective_license\"\\s*:\\s*\"([^\"]*)\"").find(text)
                        ?.let { m -> Logbook.log("registration: effective_license=${m.groupValues[1]}") }
                    // An ad in the queue Spotify sends: the song before it goes on to the next song
                    // instead, before the page sees the queue (AdBlock.withoutAds, Myspoti 2.0.0).
                    val playback = path.contains("/track-playback/")
                    val given = if (playback) AdBlock.withoutAds(text) else null
                    given?.second?.forEach { note -> Logbook.log("ad left out: $note") }
                    // Marked as holding an ad, yet left as it was: why (which marker, what kept it), once per reason.
                    if (playback && given == null) AdBlock.whyKept(text)?.takeIf { it != lastAdKept }?.let { why -> lastAdKept = why; Logbook.log("ad kept in the queue: $why") }
                    val sent = given?.first ?: text
                    // Both ways on from this song lead to the ad: the player plays the next song
                    // just before this one ends (AdBlock.adAhead).
                    if (playback) AdBlock.adAhead(sent)?.let { a ->
                        if (AdBlock.ahead.value != a) Logbook.log("ad ahead: ${a.nextUri} is played before the end")
                        AdBlock.ahead.value = a
                    }
                    answer(reply, id, it.code, out.toString(), sent)
                }
            }
        })
    }

    private fun isRegistration(request: Request) =
        request.method == "POST" && request.url.encodedPath.endsWith("/track-playback/v1/devices")

    /** The last page's playback device, kept across starts (see [PlayerDeviceRecord]). */
    private val devices = PlayerDeviceRecord(java.io.File(view.context.filesDir, "spotify/player-device"))

    /** The page's own goodbye for a device it left: the same DELETE, with the registration's headers. */
    private fun sayGoodbye(registration: Request, old: String, seq: Long) {
        if (!DEVICE_ID.matches(old)) return
        val url = PlayerDeviceRecord.deviceUrl(registration.url.toString(), old)
        if (!WebPolicy.isRelayTarget(url)) return
        val request = Request.Builder()
            .url(url)
            .headers(registration.headers)
            .delete(PlayerDeviceRecord.goodbye(seq).toByteArray().toRequestBody(null))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Logbook.log("previous player device: goodbye failed: ${e.javaClass.simpleName}")
            override fun onResponse(call: Call, response: Response) = response.use {
                Logbook.log("previous player device taken off -> ${it.code}")
            }
        })
    }

    /** The last reason an ad was kept in a queue (see [AdBlock.whyKept]): logged once per reason. */
    @Volatile private var lastAdKept: String? = null

    /** Back to the document that asked, on the main thread (reply proxies are thread-bound). */
    private fun answer(reply: JavaScriptReplyProxy, id: Int, status: Int, headersJson: String, body: String) {
        val message = JSONObject().put("id", id).put("status", status).put("headers", headersJson).put("body", body).toString()
        main.post { runCatching { reply.postMessage(message) } }
    }

    /** A Connect command's shape, without ids: endpoint, license, picked song, origin. */
    private fun commandSummary(body: String): String = runCatching {
        val c = JSONObject(body).optJSONObject("command") ?: JSONObject(body)
        val opts = c.optJSONObject("options")
        listOf(
            "endpoint=${c.optString("endpoint", "?")}",
            "license=${opts?.optString("license", "<none>") ?: "<no options>"}",
            "skip_to=${opts?.has("skip_to") == true}",
            "origin=${c.optJSONObject("play_origin")?.optString("feature_identifier", "?") ?: "?"}",
            "transfer=${c.has("transfer_options") || JSONObject(body).has("transfer_options")}",
        ).joinToString(" ")
    }.getOrDefault("unreadable")

    /**
     * The path with device ids hidden. A command's `from/<id>/to/<id>` keeps one fact: whether it
     * targets this player itself (`to/self`) or another device (`to/other`) — a refusal of a
     * command sent to the official app on the same account reads very differently from one sent
     * to this player.
     */
    private fun shortPath(url: String): String {
        val path = url.substringBefore('?').substringAfter(".com")
        val ids = Regex("/from/([0-9a-f]{20,})/to/([0-9a-f]{20,})").find(path)
        if (ids != null) {
            val target = if (ids.groupValues[1] == ids.groupValues[2]) "self" else "other"
            return path.replaceRange(ids.range, "/from/…/to/$target").replace(Regex("[0-9a-f]{20,}"), "…")
        }
        return path.replace(Regex("[0-9a-f]{20,}"), "…")
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 1_048_576
        private const val MAX_RESPONSE_BYTES = 4_194_304
        private const val MAX_HEADER_BYTES = 16_384
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        /** A playback device id as the page registers it (hex); anything else is never put in a URL. */
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val BLOCKED_HEADERS = setOf(
            "cookie", "cookie2", "host", "origin", "referer", "connection", "content-length", "transfer-encoding",
            "te", "trailer", "upgrade", "accept-encoding", "user-agent", "x-requested-with", "accept-language",
        )

        /** Wraps window.fetch for the relayed routes; answers arrive on MyspotiNet's message events. */
        private val SCRIPT = """
            (() => {
              if (window.__mspNetInstalled || !window.MyspotiNet) return;
              window.__mspNetInstalled = true;
              const net = window.MyspotiNet;
              // The identity this page presents, so relayed requests present the same browser.
              try {
                const d = navigator.userAgentData || {};
                const langs = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language];
                net.postMessage(JSON.stringify({ type: 'identity', value: {
                  userAgent: navigator.userAgent,
                  brands: (d.brands || []).map(b => '"' + b.brand + '";v="' + b.version + '"').join(', '),
                  mobile: d.mobile === true ? '?1' : '?0',
                  platform: d.platform || 'Linux',
                  acceptLanguage: langs.map((l, i) => i === 0 ? l : l + ';q=0.' + Math.max(1, 10 - i)).join(','),
                }}));
              } catch (e) { /* the host keeps its fallback identity */ }
              // The player's media elements, often never added to the document: the bridge
              // silences them during an ad ("media.mute"); one started then starts silent.
              // Silent is a tiny volume, never muted: Chromium pauses muted media of a hidden page.
              const media = new Set();
              window.__mspMedia = media;
              const play = HTMLMediaElement.prototype.play;
              HTMLMediaElement.prototype.play = function () {
                media.add(this);
                if (window.__mspMuted) { this.muted = false; this.volume = 0.001; }
                // The bridge's audio graph (boost, crossfade), when there is one: the element joins
                // it before its first sound.
                if (window.__mspRoute) { try { window.__mspRoute(this); } catch (e) { /* plays directly */ } }
                return play.apply(this, arguments);
              };
              const relayed = /^https:\/\/(spclient\.wg|[a-z0-9]+-spclient)\.spotify\.com\/(connect-state|track-playback)\/v1\//;
              const command = /player\/command|connect\/transfer/;
              const pending = new Map();
              let seq = 0;
              const original = window.fetch;
              // A refused play or transfer (free accounts in some countries): the app tries another way.
              // late: the command was acknowledged but nothing played (see trust): the host judges it
              // more than its usual few seconds after the play.
              const tell = (status, url, late) => {
                if ((status === 402 || status === 403) && command.test(url) && window.MyspotiHost) {
                  try { window.MyspotiHost.postMessage(JSON.stringify({ type: 'refused', status, late: !!late, path: url.split('?')[0].replace(/^.*\/v1\//, '').replace(/\/from\/.*$/, '') })); } catch (e) { /* host gone */ }
                }
              };
              const note = (value) => { try { net.postMessage(JSON.stringify(Object.assign({ type: 'direct' }, value))); } catch (e) { /* host gone */ } };
              // From Myspoti 2.0.1: some free accounts get every relayed command refused (402
              // COMMAND_FAILED) while the same account plays in apps that send them from the page
              // itself. A refused command is sent once more from the page, and the page's commands
              // keep going that way if it took. Kept only if something plays within 10 s: a command
              // sent from the page can be acknowledged and never delivered (X-Requested-With).
              const plays = () => [...media].some(m => !m.paused && !m.ended && m.readyState > 2);
              const trust = (url) => {
                if (window.__mspDirect) return;
                window.__mspDirect = true;
                setTimeout(() => {
                  const kept = plays();
                  if (!kept) window.__mspDirect = false;
                  note({ kept });
                  // Acknowledged and never delivered: the page showed a song at 0:00 that never
                  // started (reported after 2.0.1: "I can press play, the clock moves, nothing
                  // plays"). Told as a refusal, so the app's fallbacks run and, failing all of
                  // them, the user is told instead of left on a silent player.
                  if (!kept) tell(402, url, true);
                }, 10000);
              };
              const direct = (url, init) => original.call(window, url, init).then(r => { tell(r.status, url); return r; });
              net.addEventListener('message', (event) => {
                let m;
                try { m = JSON.parse(event.data); } catch (e) { return; }
                const p = pending.get(m.id);
                if (!p) return;
                pending.delete(m.id);
                const status = m.status;
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                const relayedAnswer = () => {
                  const noBody = status === 204 || status === 205 || status === 304;
                  let headers = {};
                  try { headers = JSON.parse(m.headers); } catch (e) { /* none */ }
                  return new Response(noBody ? null : m.body, { status, headers });
                };
                if (status === 402 && command.test(p.url) && !window.__mspDirect) {
                  original.call(window, p.url, p.init).then(r => {
                    note({ status: r.status });
                    if (r.ok) trust(p.url); else tell(r.status, p.url);
                    p.resolve(r);
                  }, () => { tell(status, p.url); p.resolve(relayedAnswer()); });
                  return;
                }
                tell(status, p.url);
                p.resolve(relayedAnswer());
              });
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                if (!url || !relayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                if (window.__mspDirect && command.test(url)) return direct(url, init);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                const credentials = (init && init.credentials) || 'same-origin';
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url, init });
                  net.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body, credentials }));
                });
              };
            })();
        """.trimIndent()
    }
}
