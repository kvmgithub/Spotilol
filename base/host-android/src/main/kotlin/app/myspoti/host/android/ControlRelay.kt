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
                    val registration = request.method == "POST" && path.endsWith("/track-playback/v1/devices")
                    if (it.code in 200..299 && registration) onDeviceRegistered?.invoke()
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
              const pending = new Map();
              let seq = 0;
              net.addEventListener('message', (event) => {
                let m;
                try { m = JSON.parse(event.data); } catch (e) { return; }
                const p = pending.get(m.id);
                if (!p) return;
                pending.delete(m.id);
                const status = m.status;
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                // A refused play or transfer (free accounts in some countries): the app tries another way.
                if ((status === 402 || status === 403) && /player\/command|connect\/transfer/.test(p.url) && window.MyspotiHost) {
                  try { window.MyspotiHost.postMessage(JSON.stringify({ type: 'refused', status, path: p.url.split('?')[0].replace(/^.*\/v1\//, '').replace(/\/from\/.*$/, '') })); } catch (e) { /* host gone */ }
                }
                const noBody = status === 204 || status === 205 || status === 304;
                let headers = {};
                try { headers = JSON.parse(m.headers); } catch (e) { /* none */ }
                p.resolve(new Response(noBody ? null : m.body, { status, headers }));
              });
              const original = window.fetch;
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                if (!url || !relayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                const credentials = (init && init.credentials) || 'same-origin';
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url });
                  net.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body, credentials }));
                });
              };
            })();
        """.trimIndent()
    }
}
