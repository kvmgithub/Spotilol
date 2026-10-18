package com.project.lol.bridge

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import com.project.lol.R
import com.project.lol.service.MediaNotificationService
import com.project.lol.webview.helpers.AdIdStore
import org.json.JSONArray
import java.lang.ref.WeakReference
import com.project.lol.util.Logger
import com.project.lol.webview.helpers.NavToken

class SpotifyBridge(activityRef: WeakReference<Activity>) {

    companion object {
        private const val TAG = "bridge"
        private const val CALL = "bridge.call"
    }

    @Volatile
    private var activityRef = activityRef
    private val mainHandler = Handler(Looper.getMainLooper())

    fun rebind(ref: WeakReference<Activity>) {
        activityRef = ref
    }
    var onLoginDetected: (() -> Unit)? = null
    var onPlayLoaded: (() -> Unit)? = null
    var onMediaStatus: ((String) -> Unit)? = null
    var onMediaPosition: ((Long) -> Unit)? = null
    var onTimerDialogRequest: (() -> Unit)? = null
    var onVolumeDialogRequest: (() -> Unit)? = null
    var onEnterVideoFullscreen: (() -> Unit)? = null
    var onExitVideoFullscreen: (() -> Unit)? = null
    var onPlayerExpanded: ((Boolean) -> Unit)? = null
    var onWideVideo: ((Boolean) -> Unit)? = null
    var onCtxMenuState: ((Boolean) -> Unit)? = null

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
        val activity = activityRef.get() ?: return true
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
        if (ids.isEmpty()) return
        val token = NavToken.current()
        mainHandler.post {
            if (token != NavToken.current()) {
                Logger.d(CALL, "recAdContentIds: dropped ${ids.size} stale ids")
                return@post
            }
            if (AdIdStore.addAll(ids)) Logger.d(CALL, "recAdContentIds: ${ids.size}")
        }
    }

    fun playLoaded() {
        Logger.i(TAG, "player loaded, web player ready")
        activityRef.get()?.runOnUiThread {
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

    fun openVolumeDialog() {
        val activity = activityRef.get() ?: return
        Logger.d(CALL, "openVolumeDialog")
        activity.runOnUiThread {
            onVolumeDialogRequest?.invoke()
        }
    }

    fun playerExpanded(expanded: Boolean) {
        val activity = activityRef.get() ?: return
        Logger.v(CALL, "playerExpanded=$expanded")
        activity.runOnUiThread {
            onPlayerExpanded?.invoke(expanded)
        }
    }

    fun wideVideo(showing: Boolean) {
        val activity = activityRef.get() ?: return
        Logger.v(CALL, "wideVideo=$showing")
        activity.runOnUiThread {
            onWideVideo?.invoke(showing)
        }
    }

    fun ctxMenuState(open: Boolean) {
        val activity = activityRef.get() ?: return
        Logger.v(CALL, "ctxMenuState=$open")
        activity.runOnUiThread {
            onCtxMenuState?.invoke(open)
        }
    }

    fun enterVideoFullscreen() {
        val activity = activityRef.get() ?: return
        Logger.i(CALL, "enterVideoFullscreen")
        activity.runOnUiThread {
            onEnterVideoFullscreen?.invoke()
        }
    }

    fun exitVideoFullscreen() {
        val activity = activityRef.get() ?: return
        Logger.i(CALL, "exitVideoFullscreen")
        activity.runOnUiThread {
            onExitVideoFullscreen?.invoke()
        }
    }

    private val nativeFetch by lazy {
        com.project.lol.security.NativeFetch(
            readCookies = { CookieManager.getInstance().getCookie(it) },
            storeCookies = { url, cookies ->
                val manager = CookieManager.getInstance()
                cookies.forEach { manager.setCookie(url, it) }
                manager.flush()
            },
        )
    }

    fun nFetch(url: String, optsJson: String?): String = nativeFetch.execute(url, optsJson)
}
