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
 * Every WebView request carries "X-Requested-With: <package>", and an app can no longer turn
 * that off. Spotify acknowledges Connect commands that carry it but never delivers them to the
 * player, so nothing plays. The page's `fetch` is wrapped at document start; matching requests
 * go through `MyspotiNet.postMessage` and are repeated with OkHttp using the page's headers.
 *
 * Spotilol's native transport rules apply: exact HTTPS spclient hosts and paths
 * ([WebPolicy.isRelayTarget]), bounded requests and responses, a total call deadline, no
 * redirects, no cookies either way, and the answer goes back through the sending document's
 * reply proxy, never to whatever page is current.
 */
internal class ControlRelay(private val view: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private val http: OkHttpClient = BoundedHttp.client(callTimeoutSeconds = 20).newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun install() {
        view.exposeToPlayer("MyspotiNet", MAX_REQUEST_BYTES, ::onMessage)
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf(WebPolicy.PLAYER_ORIGIN))
        android.util.Log.d(WebPlayerHost.LOG, "control relay installed")
    }

    /** `{id, url, method, headers, body}` from the page's wrapped `fetch`. */
    private fun onMessage(json: String, reply: JavaScriptReplyProxy) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: return
        val request = runCatching { build(m) }.getOrElse {
            android.util.Log.d(WebPlayerHost.LOG, "relay #$id rejected: ${it.message}")
            return answer(reply, id, 0, "{}", "")
        }
        send(id, request, reply)
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
        headers["User-Agent"] = WebPlayerHost.DESKTOP_UA
        headers["Origin"] = WebPolicy.PLAYER_ORIGIN
        headers["Referer"] = "${WebPolicy.PLAYER_ORIGIN}/"
        val body = if (m.isNull("body")) null else m.optString("body")
        require((body?.length ?: 0) <= MAX_REQUEST_BYTES) { "body too large" }
        // The player's registration leaves out the ad formats (see AdBlock).
        val sent = if (body != null && AdBlock.isDeviceRegistration(method, url)) AdBlock.withoutAdFormats(body) else body
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = when (method) {
            "GET", "HEAD" -> { require(sent.isNullOrEmpty()) { "body on $method" }; null }
            else -> (sent ?: "").toByteArray().toRequestBody(null)
        }
        return Request.Builder().url(url).headers(headers.build()).method(method, payload).build()
    }

    private fun send(id: Int, request: Request, reply: JavaScriptReplyProxy) {
        val asked = android.os.SystemClock.uptimeMillis()
        val label = "${request.method} ${shortPath(request.url.toString())}"
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                MyspotiRuntime.network.failed()
                android.util.Log.d(WebPlayerHost.LOG, "relay $label failed: ${e.javaClass.simpleName}")
                answer(reply, id, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                MyspotiRuntime.network.succeeded()
                response.use {
                    if (it.isRedirect || it.code in 300..399) {
                        android.util.Log.d(WebPlayerHost.LOG, "relay $label redirect ${it.code} rejected")
                        return answer(reply, id, 0, "{}", "")
                    }
                    val text = runCatching { BoundedHttp.text(it.body, MAX_RESPONSE_BYTES) }.getOrElse { e ->
                        android.util.Log.d(WebPlayerHost.LOG, "relay $label response rejected: ${e.message}")
                        return answer(reply, id, 0, "{}", "")
                    }
                    val out = JSONObject()
                    it.headers.names().forEach { name ->
                        if (!name.equals("Set-Cookie", true) && !name.equals("Set-Cookie2", true)) out.put(name, it.header(name))
                    }
                    val net = android.os.SystemClock.uptimeMillis() - asked
                    android.util.Log.d(WebPlayerHost.LOG, "relay $label -> ${it.code} in $net ms")
                    if (it.code >= 400) android.util.Log.d(WebPlayerHost.LOG, "relay error body: ${LogRedactor.redact(text.take(400))}")
                    answer(reply, id, it.code, out.toString(), text)
                }
            }
        })
    }

    /** Back to the document that asked, on the main thread (reply proxies are thread-bound). */
    private fun answer(reply: JavaScriptReplyProxy, id: Int, status: Int, headersJson: String, body: String) {
        val message = JSONObject().put("id", id).put("status", status).put("headers", headersJson).put("body", body).toString()
        main.post { runCatching { reply.postMessage(message) } }
    }

    private fun shortPath(url: String) = url.substringBefore('?').substringAfter(".com").replace(Regex("[0-9a-f]{20,}"), "…")

    companion object {
        private const val MAX_REQUEST_BYTES = 1_048_576
        private const val MAX_RESPONSE_BYTES = 4_194_304
        private const val MAX_HEADER_BYTES = 16_384
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val BLOCKED_HEADERS = setOf(
            "cookie", "cookie2", "host", "origin", "referer", "connection", "content-length", "transfer-encoding",
            "te", "trailer", "upgrade", "accept-encoding", "user-agent", "x-requested-with",
        )

        /** Wraps window.fetch for the relayed routes; answers arrive on MyspotiNet's message events. */
        private val SCRIPT = """
            (() => {
              if (window.__mspNetInstalled || !window.MyspotiNet) return;
              window.__mspNetInstalled = true;
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
              const relayed = /^https:\/\/(spclient\.wg|[a-z0-9]+-spclient)\.spotify\.com\/(connect-state|track-playback)\/v1\//;
              const pending = new Map();
              let seq = 0;
              const net = window.MyspotiNet;
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
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject, url });
                  net.postMessage(JSON.stringify({ id, url, method, headers, body: body == null ? null : body }));
                });
              };
            })();
        """.trimIndent()
    }
}
