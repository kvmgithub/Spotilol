package app.myspoti.host.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream

/**
 * [BridgeHost] on an Android WebView that loads the official Web Player.
 *
 * - The WebView is built on the application context, so playback outlives activities.
 * - Desktop user agent: on a phone UA the Web Player only offers to open the app.
 * - Protected media (Widevine) is allowed; ad audio requests ([AdBlock]) are refused.
 * - [loginRequired] turns true on Spotify's login pages: the UI then shows [view] so the
 *   user signs in on Spotify's own page.
 */
class WebPlayerHost(context: Context) : BridgeHost {
    private val main = Handler(Looper.getMainLooper())

    // DROP_OLDEST: tryEmit under a main-thread stall must not drop the newest
    // state/result (the default SUSPEND overflow drops exactly the new value).
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: SharedFlow<String> = _messages

    private val _url = MutableStateFlow("")
    /** The page's current URL. */
    val url: StateFlow<String> = _url

    private val _loginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> = _loginRequired

    /** Fires after each Web Player page load; the API injects the bridge then. */
    var onPageLoaded: (() -> Unit)? = null

    /** Fires when a Web Player page load starts: the old page and its bridge are gone. */
    var onPageStarting: (() -> Unit)? = null

    init {
        // Debug builds only: lets desktop tools (chrome://inspect, CDP) look at the page.
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
    }

    private lateinit var relay: ControlRelay

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context.applicationContext).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // Client hints must agree with the desktop user agent: by default the WebView announces
        // "Android WebView" / mobile in Sec-CH-UA headers and navigator.userAgentData.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            val brands = listOf(
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion("155").setFullVersion("155.0.0.0").build(),
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion("155").setFullVersion("155.0.0.0").build(),
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand("Not?A_Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build(),
            )
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion("155.0.0.0")
                .setPlatform("Linux")
                .setPlatformVersion("6.8.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setMobile(false)
                .setModel("")
                .setWow64(false)
                .build())
        }
        // The page plays the audio: its renderer keeps its priority when the screen is off.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Playback control requests leave from the app, without the WebView's X-Requested-With.
        relay = ControlRelay(this).also { it.install() }
        val webView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        exposeToPlayer("MyspotiHost") { json -> _messages.tryEmit(json) }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                    message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                    android.util.Log.d(LOG, "console ${message.messageLevel()}: ${message.message().take(300)}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                android.util.Log.d(LOG, "permission request: ${request.resources.joinToString()}")
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!AdBlock.isBlocked(request.url.toString())) return null
                android.util.Log.d(LOG, "blocked ad request ${request.url.toString().take(120)}")
                // Refused like a blocked request: the player reports a failed ad and moves on.
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                android.util.Log.d(LOG, "page start ${url.take(120)}")
                pageStarts++
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) { onPageStarting?.invoke(); relay.injectNow() }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.url.host?.contains("spotify") == true)
                    android.util.Log.d(LOG, "load error ${error.errorCode} ${error.description} ${request.url.toString().take(120)}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.url.host?.contains("spotify") == true)
                    android.util.Log.d(LOG, "http ${response.statusCode} ${request.method} ${request.url.toString().substringBefore('?').take(120)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                android.util.Log.d(LOG, "page finished ${url.take(120)}")
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) onPageLoaded?.invoke()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                // Stay on Spotify's own pages (player, login, consent); open nothing else.
                // Exact or subdomain match: "evilspotify.com" must not count as Spotify.
                val allowed = listOf("spotify.com", "spotify.net", "scdn.co", "google.com", "facebook.com", "apple.com")
                    .any { host == it || host.endsWith(".$it") }
                return !allowed
            }
        }
    }

    private fun isLoginPage(url: String?) =
        url != null && (url.contains("accounts.spotify.com") || url.contains("/login") || url.contains("challenge.spotify.com"))

    fun load() = main.post {
        pageStarts = 0
        loadAsked = true
        view.loadUrl(PLAYER_URL)
        watchLoad(PLAYER_URL, attempt = 1)
    }

    /** Page starts since the last [load]; the load watchdog looks at it. */
    private var pageStarts = 0
    /** [load] was called and no [unload] since (data saver keeps the page off). */
    private var loadAsked = false

    /**
     * An exec-spawned process (GrapheneOS "secure app spawning") can lose the first
     * navigation of a WebView made in Application.onCreate: no page start, ever. When
     * nothing started after a while, the page is asked for again (a few times, further apart).
     */
    private fun watchLoad(url: String, attempt: Int) {
        main.postDelayed({
            if (pageStarts > 0 || !loadAsked || attempt > LOAD_RETRIES) return@postDelayed
            android.util.Log.d(LOG, "page never started: loading again (attempt $attempt)")
            if (attempt > 1) view.stopLoading()
            view.loadUrl(url)
            watchLoad(url, attempt + 1)
        }, LOAD_WATCH_MS * attempt)
    }

    /** The player's own URL, not the page's current one: the bridge parks the page on an empty route. */
    fun reload() = main.post { view.loadUrl(PLAYER_URL) }

    fun openLogin() = main.post { view.loadUrl(LOGIN_URL) }

    /** Signs out of Spotify in this WebView, then shows the login page. */
    fun logout() = main.post {
        CookieManager.getInstance().removeAllCookies {
            main.post {
                // Web storage too: localStorage/IndexedDB hold the player's session
                // state, and clearCache(true) also drops the disk cache (false kept it).
                android.webkit.WebStorage.getInstance().deleteAllData()
                view.clearCache(true)
                view.loadUrl(LOGIN_URL)
            }
        }
    }

    override suspend fun evaluate(script: String) {
        val done = CompletableDeferred<Unit>()
        main.post {
            runCatching { view.evaluateJavascript(script, null) }
            done.complete(Unit)
        }
        done.await()
    }

    /**
     * Keeps the page attached to a window so Chromium runs it at full speed: a tiny,
     * non-interactive view in [container] while the player is hidden.
     */
    fun attach(container: ViewGroup, visible: Boolean) = main.post {
        // Re-added each time: addView converts the params to the container's own type
        // (assigning plain ViewGroup.LayoutParams to a FrameLayout child crashes on measure).
        (view.parent as? ViewGroup)?.removeView(view)
        val size = if (visible) ViewGroup.LayoutParams.MATCH_PARENT else 1
        container.addView(view, ViewGroup.LayoutParams(size, size))
        view.alpha = if (visible) 1f else 0.01f
        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Typing in the login form needs touch focus, or the keyboard never opens.
        view.isFocusable = visible
        view.isFocusableInTouchMode = visible
        view.descendantFocusability = if (visible) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (visible) view.requestFocus()
        view.requestLayout()
        // Now in a window: a first navigation that never started gets another go.
        if (loadAsked && _url.value.isEmpty() && pageStarts == 0) view.loadUrl(PLAYER_URL)
    }

    fun detach() = main.post { (view.parent as? ViewGroup)?.removeView(view) }

    /** Frees the page (data saver, low memory). [load] brings it back. */
    fun unload() = main.post { loadAsked = false; view.loadUrl("about:blank") }

    companion object {
        /** Logcat tag of the engine: `adb logcat -s Myspoti`. */
        const val LOG = "Myspoti"
        const val PLAYER_URL = "https://open.spotify.com/"
        private const val LOAD_WATCH_MS = 4_000L
        private const val LOAD_RETRIES = 4
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
    }
}
