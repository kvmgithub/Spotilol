package app.myspoti.host.android

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Gives the Web Player page a `window.<name>.postMessage(string)` channel to the app.
 *
 * An `addJavascriptInterface` object is injected into every frame of the WebView, whatever its
 * origin: the player page embeds a google.com reCAPTCHA iframe, which could then post to the
 * app. A web message listener is only injected into frames of [PLAYER_ORIGIN], and only the
 * main frame's messages are taken. WebViews without the feature fall back to the interface;
 * the page calls the same `postMessage` either way.
 */
@SuppressLint("JavascriptInterface")
internal fun WebView.exposeToPlayer(name: String, onMessage: (String) -> Unit) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, name, setOf(PLAYER_ORIGIN)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let(onMessage)
        }
    } else {
        addJavascriptInterface(PlayerChannel(onMessage), name)
    }
}

private const val PLAYER_ORIGIN = "https://open.spotify.com"

/** Fallback channel for WebViews without web message listeners. */
internal class PlayerChannel(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(message: String) = onMessage(message)
}
