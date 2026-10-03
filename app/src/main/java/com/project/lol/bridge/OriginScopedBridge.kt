package com.project.lol.bridge

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.project.lol.security.WebSecurityPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** No JavascriptInterface fallback: unsupported WebViews must be updated. */
class OriginScopedBridge private constructor(private val view: WebView, private val bridge: SpotifyBridge) {
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    private var script: ScriptHandler? = null
    @Volatile private var closed = false

    private fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            error("Secure bridge features unavailable")
        }
        val origins = setOf("https://open.spotify.com", "https://accounts.spotify.com")
        WebViewCompat.addWebMessageListener(view, "NativeBridge", origins) { _, message, origin, mainFrame, reply ->
            if (closed || !mainFrame || !WebSecurityPolicy.isBridgeOrigin(origin.toString())) return@addWebMessageListener
            val data = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (data.length > 1_048_576) return@addWebMessageListener
            val request = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
            val id = request.optString("id")
            if (!id.matches(Regex("[0-9]{1,16}"))) return@addWebMessageListener
            val method = request.optString("method")
            if (!WebSecurityPolicy.isBridgeCall(origin.toString(), mainFrame, method)) return@addWebMessageListener
            val args = request.optJSONArray("args") ?: return@addWebMessageListener
            if (method == "nFetch") {
                try {
                    workers.execute {
                        val result = runCatching { bridge.nFetch(args.getString(0), args.optString(1).takeUnless { args.isNull(1) }) }
                        // ReplyProxy belongs to the sending JS object, never use evaluateJavascript for replies.
                        view.post {
                            if (!closed) sendReply(reply, id, result)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sendReply(reply, id, Result.failure(IllegalStateException("Bridge busy")))
                }
            } else {
                // Listener callbacks run on the UI thread. Playback and download callbacks retain UI affinity.
                runCatching { dispatch(method, args) }
            }
        }
        script = WebViewCompat.addDocumentStartJavaScript(view, BridgeScript.CONTENT, origins)
    }

    private fun sendReply(reply: JavaScriptReplyProxy, id: String, result: Result<String>) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            reply.postMessage(JSONObject().apply {
                put("id", id)
                result.fold({ put("result", it) }, { put("error", "Native request rejected") })
            }.toString())
        }
    }

    private fun dispatch(method: String, a: JSONArray) {
        fun s(i: Int) = if (a.isNull(i)) null else a.getString(i)
        when (method) {
            "loginDetected" -> bridge.loginDetected()
            "deferMessage" -> bridge.deferMessage(s(0))
            "wakeUp" -> bridge.wakeUp()
            "wakeOff" -> bridge.wakeOff()
            "cssInjected" -> bridge.cssInjected()
            "dbg" -> bridge.dbg(s(0), s(1))
            "clearDebugLog" -> bridge.clearDebugLog()
            "recAdContentIds" -> bridge.recAdContentIds(s(0))
            "playLoaded" -> bridge.playLoaded()
            "recMediaPosition" -> bridge.recMediaPosition(a.getLong(0).coerceAtLeast(0))
            "recMediaStatus" -> bridge.recMediaStatus(s(0))
            "onMediaItemsLoaded" -> bridge.onMediaItemsLoaded(s(0), s(1))
            "onSearchCompleted" -> bridge.onSearchCompleted(s(0), s(1))
            "manageTShut" -> bridge.manageTShut(a.getBoolean(0))
            "manageTSleep" -> bridge.manageTSleep(a.getBoolean(0))
            "recAccountName" -> bridge.recAccountName(a.getString(0).take(256))
            "openTimerDialog" -> bridge.openTimerDialog()
            "enterPip" -> bridge.enterPip()
            "enterPipVideo" -> bridge.enterPipVideo(a.getInt(0).coerceIn(1, 8192), a.getInt(1).coerceIn(1, 8192))
            "downloadTrack" -> bridge.downloadTrack(s(0))
            "downloadCollection" -> bridge.downloadCollection(s(0))
            "skipDownload" -> bridge.skipDownload()
            "cancelDownload" -> bridge.cancelDownload()
        }
    }

    private fun close() {
        closed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) script?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(view, "NativeBridge")
        }
        workers.shutdownNow()
    }

    companion object {
        private val installed = java.util.IdentityHashMap<WebView, OriginScopedBridge>()
        fun install(view: WebView, bridge: SpotifyBridge) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                "Update Android System WebView: secure bridge features are required"
            }
            remove(view)
            val scoped = OriginScopedBridge(view, bridge)
            scoped.install()
            installed[view] = scoped
        }
        fun remove(view: WebView) { installed.remove(view)?.close() }
    }
}
