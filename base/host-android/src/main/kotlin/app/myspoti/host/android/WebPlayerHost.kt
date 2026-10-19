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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebViewClient
import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.security.LogRedactor
import app.myspoti.spotify.security.WebPolicy
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
 * - Protected media (Widevine) is granted to the player origin only; ad audio is refused and
 *   telemetry ([AdBlock.isTelemetry]) gets an empty success.
 * - Spotilol rules: no JavascriptInterface fallback, exact-host navigation ([WebPolicy]),
 *   redacted logs ([LogRedactor]).
 * - [loginRequired] turns true on Spotify's login pages: the UI then shows [view] so the
 *   user signs in on Spotify's own page.
 */
class WebPlayerHost(private val context: Context) : BridgeHost {
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

    /** Debug builds: extra logging (a proxy that fails, a device name rewritten…) — and CDP. */
    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * This install's browser identity, drawn once (see [Fingerprint]) and handed to the API as
     * well: the page and the app's own calls must present the *same* browser, or the difference
     * between them is itself the tell.
     */
    internal val fingerprint = Fingerprint.of(context)
    val userAgent: String get() = fingerprint.userAgent

    init {
        check(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER) &&
            androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "Update Android System WebView: secure bridge features are required"
        }
        // Debug builds only: lets desktop tools (chrome://inspect, CDP) look at the page.
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    var view: WebView = createView()

    /**
     * Builds the page view. Called once at startup, and again after a renderer crash: a WebView
     * whose renderer died can never be reused, and Android kills the process if one is used after
     * [onRenderProcessGone] returns false (see [rebuild]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView = WebView(context.applicationContext).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = fingerprint.userAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // The WebView stamps every request the page makes with "X-Requested-With: <our package>".
        // No browser does that, and it is measured on the wire ([tools/cdp.py] --trace --headers):
        // it rode on the audio files themselves and on Spotify's telemetry, so a single rule on
        // Spotify's side would flag every Myspoti install. The androidx API was made to allow that
        // header for a few origins; an empty allowlist is what suppresses it everywhere.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            Logbook.log("x-requested-with: off for every origin")
        } else {
            Logbook.log("x-requested-with: cannot be turned off on this WebView")
        }
        // Client hints must agree with the desktop user agent: by default the WebView announces
        // "Android WebView" / mobile in Sec-CH-UA headers and navigator.userAgentData.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            val brands = fingerprint.brands.map {
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(it.name).setMajorVersion(it.major).setFullVersion(it.fullVersion).build()
            }
            androidx.webkit.WebSettingsCompat.setUserAgentMetadata(settings, androidx.webkit.UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(fingerprint.fullVersion)
                .setPlatform("Linux")
                .setPlatformVersion(fingerprint.kernel)
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
        ControlRelay(this, fingerprint).install()
        val webView = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        exposeToPlayer("MyspotiHost", MAX_MESSAGE_BYTES) { json, _ -> _messages.tryEmit(json) }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                    message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING)
                    log("console ${message.messageLevel()}: ${message.message().take(300)}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                log("permission request: ${request.resources.joinToString()} from ${request.origin?.host}")
                // Widevine for the player page only; nothing else is ever granted.
                val allowed = if (!WebPolicy.isDrmOrigin(request.origin?.toString())) emptyList()
                    else request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (AdBlock.isTelemetry(url)) {
                    // Once per host and route a session, so the diagnostics show what is blocked.
                    val key = "${request.url.host}${request.url.path?.substringBeforeLast('/')?.take(48).orEmpty()}"
                    if (telemetrySeen.add(key)) log("telemetry blocked: $key")
                    return emptySuccess(request)
                }
                // The sign-in flow's steps (path only), so a login that goes nowhere leaves a trail.
                if (WebPolicy.httpsHost(url) == "accounts.spotify.com") {
                    loginSteps++
                    log("login step #$loginSteps ${request.method} ${request.url.path.orEmpty().take(90)}")
                }
                if (!AdBlock.isAd(url)) return null
                log("blocked ad request ${request.url.host}")
                // Refused like a blocked request: the player reports a failed ad and moves on.
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                log("page start ${url.take(120)}")
                pageStarts++
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) onPageStarting?.invoke()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (WebPolicy.isSpotifyApi(request.url.toString()))
                    log("load error ${error.errorCode} ${error.description} ${request.url.toString().take(120)}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (WebPolicy.isSpotifyApi(request.url.toString()))
                    log("http ${response.statusCode} ${request.method} ${request.url.toString().substringBefore('?').take(120)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                log("page finished ${url.take(120)}")
                // A secure-spawned process (GrapheneOS) can lose the first navigation: it then
                // reports a finish without any start. Asked again at once, once per load, instead
                // of after the watchdog's first delay; the watchdog stays as the fallback.
                if (pageStarts == 0 && loadAsked && !quickRetried && url.startsWith(PLAYER_URL)) {
                    quickRetried = true
                    log("page finished without a start: loading again now")
                    view.loadUrl(PLAYER_URL)
                    return
                }
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) onPageLoaded?.invoke()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Stay on Spotify's own pages and the sign-in providers, by exact HTTPS host.
                val allowed = WebPolicy.isNavigation(request.url.toString())
                if (!allowed) log("navigation blocked: ${request.url.scheme}://${request.url.host}")
                return !allowed
            }

            /**
             * The renderer died (the reported "the app crashed", "playback stops until the player
             * is restarted"). A WebView whose renderer is gone can never be reused, and Android
             * kills the whole process if one is still used after this returns false: the page is
             * rebuilt on a new WebView, and true says "handled, do not kill the app".
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Logbook.log("renderer gone (crashed=${detail.didCrash()}): rebuilding the page")
                rebuild()
                return true
            }
        }
    }

    /** The container the page is attached to, so a rebuilt page goes back to the same place. */
    private var container: ViewGroup? = null

    /** Whether the page was shown full screen at the last [attach] (login). */
    private var lastVisible = false

    /**
     * Replaces the page after its renderer died: the dead WebView is destroyed rather than reused,
     * the new one takes its place in the same container, and the page loads again. Without this
     * the app kept a dead page — a blank screen with the sound gone — until it was restarted.
     */
    private fun rebuild() = main.post {
        val wasLoaded = loadAsked
        val box = container
        box?.let { runCatching { it.removeView(view) } }
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
        view = createView()
        pageStarts = 0
        _url.value = ""
        // attach() loads the page itself when a load is pending; the pending load is asked again
        // just after, once, so a rebuilt page is loaded exactly once.
        loadAsked = false
        box?.let { attach(it, lastVisible) }
        if (wasLoaded) load() else view.loadUrl("about:blank")
    }

    private fun isLoginPage(url: String?) = WebPolicy.isLoginPage(url)

    /** Telemetry gets an empty success (with CORS), so the page neither breaks nor retries. */
    private fun emptySuccess(request: WebResourceRequest): WebResourceResponse {
        val origin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value ?: WebPolicy.PLAYER_ORIGIN
        val headers = hashMapOf("Access-Control-Allow-Origin" to origin, "Access-Control-Allow-Credentials" to "true")
        if (request.method.equals("OPTIONS", true)) {
            headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            headers["Access-Control-Allow-Headers"] = request.requestHeaders.entries
                .firstOrNull { it.key.equals("Access-Control-Request-Headers", true) }?.value ?: "content-type"
            headers["Access-Control-Max-Age"] = "600"
            return WebResourceResponse("text/plain", "utf-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream("{}".toByteArray()))
    }

    fun load() = main.post {
        pageStarts = 0
        quickRetried = false
        loadAsked = true
        view.loadUrl(PLAYER_URL)
        watchLoad(PLAYER_URL, attempt = 1)
    }

    /** Page starts since the last [load]; the load watchdog looks at it. */
    private var pageStarts = 0
    /** The lost-first-navigation retry ran for this load (see onPageFinished). */
    private var quickRetried = false
    /** Telemetry routes already logged this session. */
    private val telemetrySeen = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Sign-in requests seen (accounts.spotify.com): a trail for a login that goes nowhere. */
    private var loginSteps = 0
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
            log("page never started: loading again (attempt $attempt)")
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

    /**
     * Switches to a saved account (Spotilol profiles): all cookies and Web storage go, the
     * profile's cookies come back (validated by [WebPolicy.restoreCookie], Secure and the auth
     * cookie HttpOnly), then the player loads. [done] gets false when nothing was restored.
     */
    fun switchSession(cookies: Map<String, String>, done: (Boolean) -> Unit) = main.post {
        val manager = CookieManager.getInstance()
        // The old account's page goes first, so it cannot write its cookies back meanwhile.
        loadAsked = false
        view.loadUrl("about:blank")
        manager.removeAllCookies {
            val pairs = cookies.flatMap { (origin, header) ->
                header.split(';').mapNotNull { WebPolicy.restoreCookie(it.trim())?.let { cookie -> origin to cookie } }
            }
            if (pairs.isEmpty()) { done(false); return@removeAllCookies }
            var left = pairs.size
            pairs.forEach { (origin, cookie) ->
                manager.setCookie(origin, cookie) {
                    if (--left > 0) return@setCookie
                    manager.flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    view.clearCache(true)
                    pageStarts = 0
                    loadAsked = true
                    view.loadUrl(PLAYER_URL)
                    done(true)
                }
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
        this.container = container
        lastVisible = visible
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
        private const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024

        /** Logcat through the shared redactor (diagnostics exports read these lines). */
        fun log(message: String) = Logbook.log(message)
        const val PLAYER_URL = "https://open.spotify.com/"
        private const val LOAD_WATCH_MS = 4_000L
        private const val LOAD_RETRIES = 4
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}
