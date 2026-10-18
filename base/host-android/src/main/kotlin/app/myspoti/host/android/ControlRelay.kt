package app.myspoti.host.android

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
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
internal class ControlRelay(private val view: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun install() {
        view.exposeToPlayer("MyspotiNet", ::onMessage)
        val documentStart = androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStart) androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf("https://open.spotify.com"))
        android.util.Log.d(WebPlayerHost.LOG, "control relay installed (document start: $documentStart)")
    }

    /** Fallback for WebViews without document-start scripts; harmless when already installed. */
    fun injectNow() = view.evaluateJavascript(SCRIPT, null)

    /** `{id, url, method, headers, body}` from the page's wrapped `fetch`. */
    private fun onMessage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
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
        headers["User-Agent"] = WebPlayerHost.DESKTOP_UA
        headers["Origin"] = "https://open.spotify.com"
        headers["Referer"] = "https://open.spotify.com/"
        val built = headers.build()
        // The player's registration leaves out the ad formats (see AdBlock).
        val sent = if (body != null && app.myspoti.spotify.bridge.AdBlock.isDeviceRegistration(method, url)) app.myspoti.spotify.bridge.AdBlock.withoutAdFormats(body) else body
        // Bytes, not a String body: OkHttp would append "; charset=utf-8" to the content type.
        val payload = if (method == "GET" || method == "HEAD") null else (sent ?: "").toByteArray().toRequestBody(null)
        val request = Request.Builder().url(url).headers(built).method(method, payload).build()
        val asked = android.os.SystemClock.uptimeMillis()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.d(WebPlayerHost.LOG, "relay $method ${shortPath(url)} failed: ${e.message}")
                reply(id, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val out = JSONObject()
                    it.headers.names().forEach { name -> out.put(name, it.header(name)) }
                    val text = it.body.string()
                    val net = android.os.SystemClock.uptimeMillis() - asked
                    android.util.Log.d(WebPlayerHost.LOG, "relay $method ${shortPath(url)} -> ${it.code} in $net ms")
                    if (it.code >= 400) android.util.Log.d(WebPlayerHost.LOG, "relay error body: ${text.take(400)}")
                    if (debuggable && AD_MARK.containsMatchIn(text)) keepAdSample(method, url, body, text)
                    reply(id, it.code, out.toString(), text)
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
        if (waited > 30) android.util.Log.d(WebPlayerHost.LOG, "relay reply #$id waited $waited ms for the main thread")
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
        android.util.Log.d(WebPlayerHost.LOG, "relay: ad in $method ${shortPath(url)} (sample kept)")
    }

    private fun shortPath(url: String) = url.substringBefore('?').substringAfter(".com").replace(Regex("[0-9a-f]{20,}"), "…")

    companion object {
        private val RELAYED = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/(connect-state|track-playback)/v1/")

        fun isRelayed(url: String) = RELAYED.containsMatchIn(url)

        private val AD_MARK = Regex("\"is_advertisement\"\\s*:\\s*\"?true|spotify:ad:|\"ad_id\"")

        /** Wraps window.fetch; the same pattern as [RELAYED]. */
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
              const relayed = /^https:\/\/[a-z0-9-]+(\.[a-z0-9-]+)*\.spotify\.com\/(connect-state|track-playback)\/v1\//;
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
                if (!url || !relayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
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
