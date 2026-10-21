package app.myspoti.host.android

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the Web Player's playback control requests (Spotify Connect commands, transfers,
 * device and track-playback state) from the app instead of the WebView.
 *
 * Every WebView request carries "X-Requested-With: <package>", and an app can no longer turn
 * that off (androidx.webkit's allow-list API is a no-op on current WebViews). Spotify
 * acknowledges Connect commands that carry it but never delivers them to the player, so
 * nothing plays. Tested on desktop Chrome: adding only that header to the command request
 * reproduces the silence, and removing it restores playback.
 *
 * The page's `fetch` is wrapped at document start. Matching requests go through
 * `MyspotiNet.postMessage` ([exposeToPlayer]), which repeats them with OkHttp using the page's own headers (its token,
 * client token, connection id), then hands the response back to the page.
 */
internal class ControlRelay(private val view: WebView, private val fingerprint: Fingerprint) {
    /** The page's player registered its playback device (it started, and takes commands). */
    @Volatile var onDeviceRegistered: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * The WebView's own cookies. The page's `fetch` sends them, this app's OkHttp does not: a
     * relayed request without them is not the shape of the real Web Player's request, which
     * Spotify can tell apart (a `402 COMMAND_FAILED` on a free account is one such refusal).
     */
    private val cookies = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = runCatching {
            val raw = android.webkit.CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
            raw.split(';').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null
                else Cookie.Builder()
                    .name(part.take(at).trim())
                    .value(part.substring(at + 1).trim())
                    .domain(url.host)
                    .path("/")
                    .build()
            }
        }.getOrDefault(emptyList())
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookies)
        .build()

    fun install() {
        view.exposeToPlayer("MyspotiNet", ::onMessage)
        val documentStart = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("https://open.spotify.com"))
        Logbook.log("control relay installed (document start: $documentStart)")
    }

    /** Fallback for WebViews without document-start scripts; harmless when already installed. */
    fun injectNow() = view.evaluateJavascript(SCRIPT, null)

    /**
     * The identity the page presents, as its own requests carry it (user agent, client hints,
     * accepted languages). The relay echoes these instead of hand-written constants, so a relayed
     * request cannot contradict the page it comes from. Sent from the page at document start; the
     * [WebPlayerHost] constants are only the fallback for the moment before it arrives.
     */
    @Volatile private var identity: JSONObject? = null

    /**
     * Until the page has said which identity it presents, the relay presents the one the WebView
     * was given ([Fingerprint]) and the phone's languages, so even the first requests agree with it.
     */
    private val fallbackLanguage: String by lazy {
        Fingerprint.acceptLanguage(android.os.LocaleList.getDefault().toLanguageTags().split(','))
    }

    /** The page's identity, for the audio proxy: the user agent and language it presents itself. */
    internal fun identityPair(): Pair<String, String>? {
        val page = identity ?: return null
        val userAgent = page.optString("userAgent").takeIf { it.isNotEmpty() } ?: return null
        return userAgent to page.optString("acceptLanguage")
    }

    /**
     * The name this device shows in Spotify's device lists: the one its owner gave the phone
     * (Paramètres → À propos → Nom de l'appareil), the same string Bluetooth and Quick Share show.
     * A phone appearing as "Web Player (Chrome)" on every other device is a phone that stands out;
     * under its own name it looks like what it is. Falls back to the model, then to a neutral name.
     */
    private val deviceName: String by lazy {
        val resolver = view.context.contentResolver
        fun setting(key: String): String? = runCatching {
            android.provider.Settings.Global.getString(resolver, key)
                ?: android.provider.Settings.Secure.getString(resolver, key)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        setting("device_name") ?: setting("bluetooth_name") ?: android.os.Build.MODEL.ifBlank { "Spotify" }
    }

    /** `{id, url, method, headers, body}` from the page's wrapped `fetch`. */
    private fun onMessage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (m.optString("type") == "identity") {
            identity = m.optJSONObject("value")
            Logbook.log("page identity: ${identity.toString().take(320)}")
            return
        }
        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: return
        val body = if (m.isNull("body")) null else m.optString("body")
        send(id, m.optString("url"), m.optString("method", "GET"), m.optJSONObject("headers") ?: JSONObject(), body)
    }

    private fun send(id: Int, url: String, method: String, given: JSONObject, body: String?) {
        if (!isRelayed(url)) {
            reply(id, 0, "{}", "")
            return
        }
        val headers = Headers.Builder()
        given.keys().forEach { key ->
            // OkHttp sets the transport headers itself.
            if (key.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding", "x-requested-with"))
                runCatching { headers.add(key, given.getString(key)) }
        }
        val page = identity
        fun fromPage(name: String, fallback: String) = page?.optString(name)?.takeIf { it.isNotEmpty() } ?: fallback
        headers["User-Agent"] = fromPage("userAgent", fingerprint.userAgent)
        headers["Origin"] = "https://open.spotify.com"
        headers["Referer"] = "https://open.spotify.com/"
        // The transport headers Chromium adds by itself, which the page's `fetch` cannot set
        // (forbidden header names in JS) and OkHttp does not add either: without them the
        // relayed request carries a shape the real Web Player's request never has.
        headers["Sec-Fetch-Site"] = "same-site"
        headers["Sec-Fetch-Mode"] = "cors"
        headers["Sec-Fetch-Dest"] = "empty"
        // Values taken from the page, not written by hand: whichever language and client hints it
        // presents, the relayed request presents the same.
        headers["Accept-Language"] = fromPage("acceptLanguage", fallbackLanguage)
        headers["sec-ch-ua"] = fromPage("brands", fingerprint.secChUa)
        headers["sec-ch-ua-mobile"] = fromPage("mobile", "?0")
        headers["sec-ch-ua-platform"] = "\"${fromPage("platform", "Linux")}\""
        val built = headers.build()
        // The registration goes out as the real Web Player sends it, except for its name: the phone's
        // own (see [deviceName]) replaces "Web Player (Chrome)".
        val registration = body != null && app.myspoti.spotify.bridge.AdBlock.isDeviceRegistration(method, url)
        if (debuggable && body != null && url.contains("/connect-state/"))
            Logbook.log("connect-state body: ${body.take(500)}")
        val sent = if (registration) {
            val rewritten = app.myspoti.spotify.bridge.AdBlock.withDeviceName(body!!, deviceName)
            if (debuggable && rewritten != body) Logbook.log("device registered as \"$deviceName\"")
            rewritten
        } else body
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = if (method == "GET" || method == "HEAD") null else (sent ?: "").toByteArray().toRequestBody(null)
        val request = Request.Builder().url(url).headers(built).method(method, payload).build()
        val asked = android.os.SystemClock.uptimeMillis()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Logbook.log("relay $method ${shortPath(url)} failed: ${e.message}")
                reply(id, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val out = JSONObject()
                    it.headers.names().forEach { name -> out.put(name, it.header(name)) }
                    val text = it.body.string()
                    val net = android.os.SystemClock.uptimeMillis() - asked
                    Logbook.log("relay $method ${shortPath(url)} -> ${it.code} in $net ms")
                    if (it.code >= 400) Logbook.log("relay error body: ${text.take(400)}")
                    if (it.code in 200..299 && method == "POST" && url.substringBefore('?').endsWith("/track-playback/v1/devices")) onDeviceRegistered?.invoke()
                    // B3 (402 on some free accounts): what a tester's report must carry to tell the
                    // causes apart — the refusal's own headers, and what the refused command asked
                    // (the license the page put in it, a picked song or not). No ids, no cookies.
                    if (it.code == 402) {
                        val heads = it.headers.names().filterNot { n -> n.equals("set-cookie", true) || n.startsWith("authorization", true) }
                            .joinToString(", ") { n -> "$n=${it.header(n)?.take(80)}" }
                        Logbook.log("refusal headers: $heads")
                        if (body != null) Logbook.log("refused command: ${commandSummary(body)}")
                    }
                    if (registration) Regex("\"effective_license\"\\s*:\\s*\"([^\"]*)\"").find(text)
                        ?.let { m -> Logbook.log("registration: effective_license=${m.groupValues[1]}") }
                    if (debuggable && registration) Logbook.log("registration answered: ${text.take(500)}")
                    if (debuggable && AD_MARK.containsMatchIn(text)) keepAdSample(method, url, body, text)
                    // An ad in the queue Spotify sends: the song before it goes on to the next song
                    // instead, before the page sees the queue (see AdBlock.withoutAds).
                    val given = if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.withoutAds(text) else null
                    given?.second?.forEach { note -> Logbook.log("ad left out: $note") }
                    val sent = given?.first ?: text
                    // Both ways on from this song lead to the ad: the player plays the next song
                    // just before this one ends (AdBlock.adAhead).
                    if (url.contains("/track-playback/")) app.myspoti.spotify.bridge.AdBlock.adAhead(sent)?.let { a ->
                        if (app.myspoti.spotify.bridge.AdBlock.ahead.value != a) Logbook.log("ad ahead: ${a.nextUri} is played before the end")
                        app.myspoti.spotify.bridge.AdBlock.ahead.value = a
                    }
                    reply(id, it.code, out.toString(), sent)
                }
            }
        })
    }

    private fun reply(id: Int, status: Int, headersJson: String, body: String) {
        val posted = android.os.SystemClock.uptimeMillis()
        main.post { replyNow(id, status, headersJson, body, posted) }
    }

    private fun replyNow(id: Int, status: Int, headersJson: String, body: String, posted: Long) {
        val waited = android.os.SystemClock.uptimeMillis() - posted
        if (waited > 30) Logbook.log("relay reply #$id waited $waited ms for the main thread")
        view.evaluateJavascript(
            "window.__mspNetDone && window.__mspNetDone($id, $status, ${JSONObject.quote(headersJson)}, ${JSONObject.quote(body)})",
            null,
        )
    }

    private val debuggable = view.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Debug builds: a playback response that carries an ad, kept in cache/ad-samples for study. */
    private fun keepAdSample(method: String, url: String, sent: String?, received: String) = runCatching {
        val dir = java.io.File(view.context.cacheDir, "ad-samples").apply { mkdirs() }
        java.io.File(dir, "${System.currentTimeMillis()}.json").writeText(
            JSONObject().put("method", method).put("url", url.substringBefore('?')).put("sent", sent ?: "").put("received", received).toString())
        Logbook.log("relay: ad in $method ${shortPath(url)} (sample kept)")
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
        /**
         * The page's own API calls. Every request the WebView makes carries
         * "X-Requested-With: <our package>" — no browser does that, and it was measured on the wire
         * riding on the telemetry and on the audio files themselves. Relaying through OkHttp is what
         * removes it; the playback commands were only the first ones (that header alone was enough to
         * silence them). One rule on Spotify's side is all it takes to flag every install, so the
         * whole API goes through here now.
         *
         * Login and consent hosts stay out: their flows post forms and redirect, and a relay would
         * have to reproduce every step exactly to sign in.
         */
        private val RELAYED = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/(connect-state|track-playback)/v1/")
        private val NOT_RELAYED = Regex("^https://(accounts|challenge|www)\\.spotify\\.com/")

        fun isRelayed(url: String) = RELAYED.containsMatchIn(url) && !NOT_RELAYED.containsMatchIn(url)

        private val AD_MARK = Regex("\"is_advertisement\"\\s*:\\s*\"?true|spotify:ad:|\"ad_id\"")

        /** Wraps window.fetch; the same pattern as [RELAYED]. */
        private val SCRIPT = """
            (() => {
              if (window.__mspNetInstalled || !window.MyspotiNet) return;
              window.__mspNetInstalled = true;
              // The identity this page really presents, read from the browser itself and handed to
              // the host, which echoes it on every relayed request. Written by hand before, the
              // relayed headers contradicted the page's own (a grease brand that read
              // "Not:A-Brand" against the page's "Not?A_Brand", and "en" against its "fr-CH"):
              // a client whose headers disagree with its page is what a server-side gate can spot.
              try {
                const d = navigator.userAgentData || {};
                const langs = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language];
                window.MyspotiNet.postMessage(JSON.stringify({ type: 'identity', value: {
                  userAgent: navigator.userAgent,
                  brands: (d.brands || []).map(b => '"' + b.brand + '";v="' + b.version + '"').join(', '),
                  mobile: d.mobile === true ? '?1' : '?0',
                  platform: d.platform || 'Linux',
                  // Chromium's own shape: the first language plain, then q=0.9, 0.8, ...
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
                return play.apply(this, arguments);
              };
              const relayed = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/(connect-state|track-playback)\/v1\//;
              const notRelayed = /^https:\/\/(accounts|challenge|www)\.spotify\.com\//;
              const pending = new Map();
              let seq = 0;
              window.__mspNetDone = (id, status, headers, body) => {
                const p = pending.get(id);
                if (!p) return;
                pending.delete(id);
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                // A refused play or transfer (free accounts in some countries): the app tries another way.
                if ((status === 402 || status === 403) && /player\/command|connect\/transfer/.test(p.url) && window.MyspotiHost) {
                  try { window.MyspotiHost.postMessage(JSON.stringify({ type: 'refused', status, path: p.url.split('?')[0].replace(/^.*\/v1\//, '').replace(/\/from\/.*$/, '') })); } catch (e) { /* host gone */ }
                }
                const noBody = status === 204 || status === 205 || status === 304;
                p.resolve(new Response(noBody ? null : body, { status, headers: JSON.parse(headers) }));
              };
              const original = window.fetch;
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                if (!url || !relayed.test(url) || notRelayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url });
                  window.MyspotiNet.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body }));
                });
              };
            })();
        """.trimIndent()
    }
}
