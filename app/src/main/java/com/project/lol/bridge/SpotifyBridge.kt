package com.project.lol.bridge

import android.app.Activity
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import com.project.lol.R
import com.project.lol.service.MediaNotificationService
import com.project.lol.webview.helpers.AdIdStore
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import com.project.lol.security.WebSecurityPolicy
import com.project.lol.security.BoundedInput
import java.util.concurrent.TimeUnit
import java.util.Locale
import com.project.lol.offline.DownloadManager
import com.project.lol.util.Logger

class SpotifyBridge(activityRef: WeakReference<Activity>) {

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

        private const val TAG = "bridge"
        private const val CALL = "bridge.call"
    }

    private val activityRef = activityRef
    var onLoginDetected: (() -> Unit)? = null
    var onPlayLoaded: (() -> Unit)? = null
    var onMediaStatus: ((String) -> Unit)? = null
    var onMediaPosition: ((Long) -> Unit)? = null
    var onTimerDialogRequest: (() -> Unit)? = null
    var onEnterPipRequest: (() -> Unit)? = null
    var onEnterPipVideoRequest: ((Int, Int) -> Unit)? = null
    var onDownloadTrack: ((String) -> Unit)? = null
    var onDownloadCollection: ((String) -> Unit)? = null

    fun loginDetected() {
        val activity = activityRef.get() ?: return
        Logger.i(TAG, "login detected")
        activity.getSharedPreferences("spotilol_prefs", Activity.MODE_PRIVATE)
            .edit()
            .putBoolean("LoggedIn", true)
            .apply()
        activity.runOnUiThread {
            onLoginDetected?.invoke()
        }
    }

    fun deferMessage(msg: String?) {
        val activity = activityRef.get() ?: return
        if (msg == "adblock") return
        val display = when (msg) {
            "unlock" -> activity.getString(R.string.bridge_player_unlocked)
            "reload" -> activity.getString(R.string.bridge_reloading)
            else -> msg
        }
        Logger.d(CALL, "deferMessage: $display")
        activity.runOnUiThread {
            Toast.makeText(activity, display, Toast.LENGTH_SHORT).show()
        }
    }

    fun isWoke(): Boolean {
        val activity = activityRef.get() ?: return false
        val visible = activity.window?.decorView?.visibility == View.VISIBLE
        Logger.v(CALL, "isWoke -> $visible")
        return visible
    }

    fun wakeUp() {
        Logger.v(CALL, "wakeUp")
    }

    fun wakeOff() {
        Logger.v(CALL, "wakeOff")
    }

    fun cssInjected() {
        Logger.v(CALL, "cssInjected")
    }

    fun dbg(level: String?, msg: String?) {
        if (!Logger.isEnabled()) return
        Logger.js(level, msg)
    }

    fun clearDebugLog() {
        Logger.clear()
        Logger.d(CALL, "logger buffer cleared from js")
    }

    fun recAdContentIds(json: String?) {
        val payload = json ?: return
        val arr = try { JSONArray(payload) } catch (e: Exception) { return }
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "")
            if (v.isNotEmpty()) ids.add(v)
        }
        if (ids.isNotEmpty()) {
            AdIdStore.addAll(ids)
            Logger.d(CALL, "recAdContentIds: ${ids.size}")
        }
    }

    fun playLoaded() {
        val activity = activityRef.get() ?: return
        Logger.i(TAG, "play loaded, web player ready")
        activity.runOnUiThread {
            onPlayLoaded?.invoke()
        }
    }

    fun recMediaPosition(position: Long) {
        Logger.v(CALL, "position=$position")
        onMediaPosition?.invoke(position)
        MediaNotificationService.instance?.updatePlaybackPosition(position)
    }

    fun recMediaStatus(json: String?) {
        json?.let {
            Logger.d(CALL, "media status (${it.length} chars): ${it.take(180)}")
            onMediaStatus?.invoke(it)
            MediaNotificationService.instance?.updateFromMediaStatus(it)
        }
    }

    fun onMediaItemsLoaded(parentId: String?, json: String?) {
        Logger.d(CALL, "media items parent=$parentId size=${json?.length ?: 0}")
        parentId?.let { MediaNotificationService.onMediaItemsLoaded(it, json ?: "[]") }
    }

    fun onSearchCompleted(query: String?, json: String?) {
        Logger.d(CALL, "search completed query=$query size=${json?.length ?: 0}")
        query?.let { MediaNotificationService.onSearchCompleted(it, json ?: "[]") }
    }

    fun manageTShut(enabled: Boolean) {
        Logger.v(CALL, "manageTShut=$enabled")
    }

    fun manageTSleep(enabled: Boolean) {
        Logger.v(CALL, "manageTSleep=$enabled")
    }

    fun recAccountName(name: String) {
        val activity = activityRef.get() ?: return
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            Logger.i(TAG, "account name updated")
            activity.getSharedPreferences("spotilol_prefs", Activity.MODE_PRIVATE)
                .edit()
                .putString("CurrentAccountName", trimmed)
                .apply()
        }
    }

    fun openTimerDialog() {
        val activity = activityRef.get() ?: return
        Logger.d(CALL, "openTimerDialog")
        activity.runOnUiThread {
            onTimerDialogRequest?.invoke()
        }
    }

    fun enterPip() {
        val activity = activityRef.get() ?: return
        Logger.i(CALL, "enterPip")
        activity.runOnUiThread {
            onEnterPipRequest?.invoke()
        }
    }

    fun enterPipVideo(w: Int, h: Int) {
        val activity = activityRef.get() ?: return
        Logger.i(CALL, "enterPipVideo ${w}x$h")
        activity.runOnUiThread {
            onEnterPipVideoRequest?.invoke(w, h)
        }
    }

    fun downloadTrack(json: String?) {
        Logger.i(CALL, "downloadTrack (${json?.length ?: 0} chars)")
        json?.let { onDownloadTrack?.invoke(it) }
    }

    @Suppress("unused")
    fun downloadCollection(json: String?) {
        Logger.i(CALL, "downloadCollection (${json?.length ?: 0} chars)")
        json?.let { onDownloadCollection?.invoke(it) }
    }

    @Suppress("unused")
    fun skipDownload() {
        Logger.i(CALL, "skipDownload")
        DownloadManager.skipCurrent()
    }

    @Suppress("unused")
    fun cancelDownload() {
        Logger.i(CALL, "cancelDownload")
        DownloadManager.cancelAll()
    }

    private val fetchClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }

    fun nFetch(url: String, optsJson: String?): String {
        return try {
            require(WebSecurityPolicy.isNativeFetch(url)) { "Untrusted native request target" }
            require((optsJson?.length ?: 0) <= 1_048_576) { "Request too large" }
            val opts = if (optsJson.isNullOrBlank()) JSONObject() else JSONObject(optsJson)
            val method = opts.optString("method", "GET").uppercase(Locale.ROOT)
            require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            val body = if (opts.has("body") && !opts.isNull("body")) opts.getString("body") else null
            require((body?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 1_048_576)
            val headers = opts.optJSONObject("headers") ?: JSONObject()
            require(headers.length() <= 64)
            val builder = Request.Builder().url(url)
            val blocked = setOf("cookie", "cookie2", "host", "origin", "referer", "connection",
                "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection",
                "te", "trailer", "upgrade", "accept-encoding", "user-agent", "x-requested-with")
            var headerBytes = 0
            headers.keys().forEach { key ->
                val lower = key.lowercase(Locale.ROOT)
                if (lower !in blocked && !lower.startsWith("sec-") && !headers.isNull(key)) {
                    val value = headers.getString(key)
                    headerBytes += key.length + value.length
                    require(headerBytes <= 16_384)
                    builder.header(key, value)
                }
            }
            builder.header("User-Agent", DESKTOP_UA)
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua", "\"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
            if (WebSecurityPolicy.isCookieHost(url)) {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            }
            val requestBody = when (method) {
                "GET", "HEAD" -> { require(body.isNullOrEmpty()); null }
                "POST", "PUT", "PATCH" -> (body ?: "").toRequestBody(headers.optString("Content-Type").toMediaTypeOrNull())
                else -> body?.toRequestBody(headers.optString("Content-Type").toMediaTypeOrNull())
            }
            fetchClient.newCall(builder.method(method, requestBody).build()).execute().use { response ->
                require(response.code !in setOf(300, 301, 302, 303, 305, 307, 308)) { "Native redirects are not permitted" }
                if (WebSecurityPolicy.isCookieHost(url)) {
                    response.headers.values("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
                    CookieManager.getInstance().flush()
                }
                val responseBody = response.body.byteStream().use { BoundedInput.readUtf8(it, 4_194_304) }
                val responseHeaders = JSONObject()
                response.headers.names().forEach { key ->
                    if (!key.equals("Set-Cookie", true) && !key.equals("Set-Cookie2", true)) {
                        responseHeaders.put(key, response.header(key))
                    }
                }
                JSONObject().put("status", response.code).put("body", responseBody)
                    .put("headers", responseHeaders).toString()
            }
        } catch (_: Exception) {
            Logger.w(TAG, "native request rejected or failed")
            "{\"status\":0,\"body\":\"Native request rejected or failed\",\"headers\":{}}"
        }
    }
}
