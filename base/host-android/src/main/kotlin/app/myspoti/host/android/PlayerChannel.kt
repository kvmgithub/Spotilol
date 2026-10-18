package app.myspoti.host.android

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.myspoti.spotify.security.WebPolicy

/**
 * Gives the Web Player page a `window.<name>.postMessage(string)` channel to the app.
 *
 * Spotilol rule: a web message listener only, never an `addJavascriptInterface` fallback
 * (an interface object is injected into every frame, including the player's google.com
 * reCAPTCHA iframe). Only main-frame messages from exactly https://open.spotify.com are
 * taken; [reply] answers the sending document only.
 */
internal fun WebView.exposeToPlayer(
    name: String,
    maxBytes: Int,
    onMessage: (message: String, reply: JavaScriptReplyProxy) -> Unit,
) {
    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        "Update Android System WebView: secure bridge features are required"
    }
    WebViewCompat.addWebMessageListener(this, name, setOf(WebPolicy.PLAYER_ORIGIN)) { _, message, origin, isMainFrame, reply ->
        if (!isMainFrame || origin.toString().trimEnd('/') != WebPolicy.PLAYER_ORIGIN) return@addWebMessageListener
        val data = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
        if (data.length > maxBytes) return@addWebMessageListener
        onMessage(data, reply)
    }
}
