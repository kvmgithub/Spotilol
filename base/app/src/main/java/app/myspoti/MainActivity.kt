package app.myspoti

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.myspoti.engine.R
import app.myspoti.host.android.LoginLoad
import app.myspoti.host.android.Logbook
import app.myspoti.host.android.MyspotiRuntime
import app.myspoti.host.android.SessionWait
import com.music.spotui.App
import com.music.spotui.ui.screens.WelcomeScreen
import com.music.spotui.ui.theme.SpotuiTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How long the player page is given to prove the session (a non-anonymous token) once it comes back.
 * The sign-in page stays in front while this runs — the address alone is never taken for a session —
 * and only a real token hands the screen to the player (see [MainActivity.watchLoginReturn]).
 */
private const val SESSION_WAIT_MS = 6_000L

/** The most a returning page may take to get its bridge ready before the wait gives up anyway. */
private const val SESSION_LOAD_MAX_MS = 60_000L

/**
 * The app's only screen host: the Compose UI, with the Web Player page attached behind it.
 * The page comes to the front only to sign in, on Spotify's own login pages.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var signedIn by mutableStateOf(false)
    private var showingLogin by mutableStateOf(false)
    /** Back from Spotify's login, waiting for the session: a native loading screen, not the web page. */
    private var connecting by mutableStateOf(false)
    /** A pending wait for the session to prove itself after the login page came back. */
    private var loginWait: kotlinx.coroutines.Job? = null
    /** The wait ended with a player that never became ready: an error with Try again, not another spinner. */
    private var loginProblem by mutableStateOf(false)
    /** The one-time setup screen (permissions, battery) after signing in. */
    private var setupNeeded by mutableStateOf(false)

    private val prefs get() = getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every Android version (15+ enforces it, 14 and older do not): the pages draw
        // under the status bar and handle its inset, otherwise a band of the window's colour sits
        // above their gradient on 14 and older.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The user's rotation choice (portrait by default); changes are applied from the UI below.
        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
        signedIn = prefs.getBoolean("LoggedIn", false)
        setupNeeded = com.music.spotui.ui.screens.Setup.needed(this)
        root = FrameLayout(this)
        val compose = ComposeView(this).apply {
            setContent {
                SpotuiTheme {
                    val orientation = com.music.spotui.data.preferences.OrientationPref.mode
                    androidx.compose.runtime.LaunchedEffect(orientation) {
                        requestedOrientation = com.music.spotui.data.preferences.OrientationPref.requested()
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        when {
                            signedIn && setupNeeded -> com.music.spotui.ui.screens.SetupScreen(
                                onDone = { setupNeeded = false },
                            )
                            signedIn -> App()
                            connecting -> ConnectingScreen()
                            else -> WelcomeScreen(onLogin = { showLogin() })
                        }
                        // The sign-in page opening (the app's own screen, never a white page) or not coming (an
                        // error with Try again, never a page that stays blank): over whatever is under it.
                        if (showingLogin) {
                            val load by MyspotiRuntime.host.loginLoad.collectAsState()
                            when {
                                load == LoginLoad.FAILED || loginProblem -> LoginProblemScreen(onRetry = {
                                    if (load == LoginLoad.FAILED) MyspotiRuntime.host.retryLogin()
                                    else { loginProblem = false; MyspotiRuntime.host.openLogin() }
                                })
                                load == LoginLoad.LOADING -> ConnectingScreen()
                            }
                        }
                    }
                }
            }
        }
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Haptics (buttons, likes, the debug icon) go through this window.
        com.music.spotui.di.Haptics.attach(compose)
        // Debug builds: `am start ... --ez showPlayer true` shows the Web Player page full screen.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        MyspotiRuntime.host.attach(root, visible = debuggable && intent.getBooleanExtra("showPlayer", false))
        // A new activity while the sign-in page is open (the system let the old one go while the user
        // read the e-mail code): the page is still there and is put back in front as it was left. It
        // used to stay hidden behind the welcome screen, and "Log in" started the flow over.
        if (!signedIn && MyspotiRuntime.loginRequired.value) {
            showingLogin = true
            MyspotiRuntime.host.attach(root, visible = true)
        }
        watchSession()
        watchLoginReturn()
        watchLogout()
        // Only on a fresh start: on recreation (theme, locale, process restore)
        // the same intent would play its link again. New links arrive in onNewIntent.
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // Background lookups (lyrics prefetch) only while the app is on screen.
    override fun onStart() {
        app.myspoti.host.android.MainThreadWatch.start()
        super.onStart()
        com.music.spotui.di.SongPlayer.inForeground = true
        // The song shown may have been liked or unliked in another app meanwhile.
        com.music.spotui.di.SongPlayer.refreshLikedIfStale()
        // Asked for over adb (TestControlReceiver's "screenon"): One UI ignores the huge
        // `screen_off_timeout`, so a long unattended run holds the screen on from in here.
        if (prefs.getBoolean("keep_screen_on", false)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        // The sign-in is only in memory until the WebView saves it: leaving the app (and being swiped away) must not lose it.
        MyspotiRuntime.host.flushCookies()
        app.myspoti.host.android.MainThreadWatch.stop()
        if (!isChangingConfigurations) com.music.spotui.di.SongPlayer.inForeground = false
    }

    override fun onDestroy() {
        // The process-wide WebView lives in this activity's root while attached:
        // detach it, or view.parent -> root keeps the destroyed activity (and its
        // whole view tree) alive for the rest of the playback session.
        MyspotiRuntime.host.detach()
        super.onDestroy()
    }

    /** Spotify's login page, full screen, until the Web Player comes back signed in. */
    private fun showLogin() {
        showingLogin = true
        MyspotiRuntime.host.attach(root, visible = true)
        MyspotiRuntime.host.openLogin()
    }

    private fun hideLogin() {
        showingLogin = false
        loginProblem = false
        MyspotiRuntime.host.attach(root, visible = false)
    }

    /**
     * The token says signed in: the screens take over from the sign-in page. One door for the three
     * places that find the session (the wait after the page came back, the return from the e-mail
     * app, the player becoming ready). The wait used to hand the screen over without telling the
     * app it was signed in, which only the player's ready signal did: when that signal had already
     * come and gone, the loading screen stayed for ever (nothing else sets [signedIn]).
     */
    private fun sessionConfirmed() {
        // Another door already started the screens over: this activity is on its way out.
        if (isFinishing) return
        loginProblem = false
        if (com.music.spotui.di.SessionActions.signedOut.value) {
            // Signed in again after a logout, maybe as someone else: start the
            // screens over (a new activity also drops their view models).
            com.music.spotui.di.SessionActions.signedIn()
            prefs.edit().putBoolean("LoggedIn", true).apply()
            MyspotiRuntime.host.flushCookies()
            MyspotiRuntime.host.attach(root, visible = false)
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return
        }
        // Whose session this is, read now when not known (a session lost before may have been
        // another account's): the likes kept for later go by it.
        com.music.spotui.data.api.ProfileCache.ensure(applicationContext)
        // The player reports ready on every page load: only a change is a session being established.
        if (!signedIn || showingLogin || connecting) {
            Logbook.log("session established: player shown")
            MyspotiRuntime.host.flushCookies()
        }
        if (!signedIn) {
            prefs.edit().putBoolean("LoggedIn", true).apply()
            signedIn = true
        }
        connecting = false
        if (showingLogin) hideLogin()
    }

    /** Logout: leave the app's screens for Spotify's login page. */
    private fun watchLogout() {
        lifecycleScope.launch {
            com.music.spotui.di.SessionActions.signedOut.collect { out ->
                if (out && signedIn) {
                    signedIn = false
                    if (com.music.spotui.di.SessionActions.switching.value) {
                        // A saved profile is being applied: wait on the loading screen.
                        connecting = true
                    } else {
                        showingLogin = true
                        MyspotiRuntime.host.attach(root, visible = true)
                    }
                }
            }
        }
    }

    /**
     * Spotify sent the user back to the player: the page is hidden now, the session follows.
     *
     * The address is not a session. The page comes back to open.spotify.com while its token is
     * still anonymous when the login never finished (the e-mail step that hangs — the reported
     * "stuck on the e-mail screen"), and hiding the page on that alone left a spinner that never
     * stopped. The return is bounded: no token after [SESSION_WAIT_MS], the login page comes back.
     */
    private fun watchLoginReturn() {
        lifecycleScope.launch {
            MyspotiRuntime.host.url.collect { url ->
                val backOnPlayer = app.myspoti.spotify.security.WebPolicy.isPlayerPage(url)
                if (!(showingLogin && backOnPlayer)) {
                    // Spotify sent the page to its sign-in again while the waiting screen covered it: the
                    // wait is over and the page is shown. It stayed hidden, with the loading screen on, until
                    // the old wait ran out and reloaded the sign-in page under the user.
                    if (showingLogin && connecting && MyspotiRuntime.host.loginRequired.value) {
                        loginWait?.cancel()
                        connecting = false
                        MyspotiRuntime.host.veil(false)
                    }
                    return@collect
                }
                // Spotify's desktop player is not shown while the session is confirmed: the app's
                // waiting screen is, behind the page made transparent (reported: it flashed).
                connecting = true
                loginProblem = false
                MyspotiRuntime.host.veil(true)
                // The address is not a session — and it comes back to open.spotify.com as soon as the
                // user leaves the app to read the sign-in e-mail. Hiding the page here showed an
                // anonymous player: an app that looked signed in with nothing playable (reported).
                // The login page therefore stays in FRONT until a real token exists.
                loginWait?.cancel()
                loginWait = launch {
                    val wait = SessionWait(SESSION_WAIT_MS, SESSION_LOAD_MAX_MS)
                    var last = android.os.SystemClock.elapsedRealtime()
                    while (!wait.expired) {
                        if (runCatching { MyspotiRuntime.isSignedIn() }.getOrDefault(false)) {
                            sessionConfirmed()
                            return@launch
                        }
                        kotlinx.coroutines.delay(500)
                        // Real time, counted only while the user is looking at the app: away to read the
                        // e-mail, the sign-in page must not be reset under them. Nor while the player is
                        // still loading: it answers nothing before its bridge is ready, which takes 10 s
                        // and more on a slow device (a DAP, a low-end Xiaomi). Counted from the address
                        // alone, the wait ran out under a page that was still booting and put the sign-in
                        // page back: the reported loop to the login page. And by turns of this loop, not
                        // by the clock, a page that left each token request waiting its 20 s made the
                        // minute last 40.
                        val now = android.os.SystemClock.elapsedRealtime()
                        wait.tick(now - last, lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),
                            MyspotiRuntime.spotify.ready.value)
                        last = now
                    }
                    connecting = false
                    if (wait.playerNeverReady) {
                        // Not signed out: the player never started. The sign-in page would send it straight
                        // back here (the cookies are good), minute after minute: the reported loop.
                        Logbook.log("no session after ${wait.elapsedMs / 1000} s: the player never became ready (waited ${wait.waitedMs / 1000} s with it ready)")
                        loginProblem = true
                    } else {
                        Logbook.log("no session ${SESSION_WAIT_MS / 1000} s after the player came back: sign-in page restored")
                        // The page may now be the anonymous player: send it back to the e-mail step so the
                        // user is not left where nothing plays.
                        if (!showingLogin) showLogin() else MyspotiRuntime.host.openLogin()
                    }
                }
            }
        }
    }

    /**
     * Back from the e-mail app: the session may have been established while away, or the page may
     * have reloaded into the anonymous player. Only the token decides — never the address.
     */
    override fun onResume() {
        super.onResume()
        if (!showingLogin) return
        lifecycleScope.launch {
            if (MyspotiRuntime.awaitSignedIn(1_500)) {
                Logbook.log("back with a session")
                sessionConfirmed()
            } else if (MyspotiRuntime.spotify.ready.value &&
                MyspotiRuntime.host.url.value.let { it.startsWith("https://open.spotify.com") && !it.contains("/login") }) {
                // Ready: a player still booting has not answered yet, and is left to watchLoginReturn.
                Logbook.log("back without a session: sign-in page restored")
                MyspotiRuntime.host.openLogin()
            }
        }
    }

    /**
     * Signed in = the Web Player is ready with a user token. A Spotify login page while
     * signed in (expired session) brings the page back to the front.
     */
    private fun watchSession() {
        val api = MyspotiRuntime.spotify
        lifecycleScope.launch {
            combine(api.ready, MyspotiRuntime.loginRequired) { ready, login -> ready to login }.collect { (ready, login) ->
                when {
                    login && !showingLogin && (signedIn || connecting) -> {
                        // The session expired: whoever signs in on that page may be another account.
                        com.music.spotui.di.SessionActions.sessionLost()
                        showLogin()
                    }
                    ready && !login -> {
                        val user = runCatching { MyspotiRuntime.isSignedIn() }.getOrDefault(false)
                        if (user) {
                            sessionConfirmed()
                        } else if (signedIn || connecting) {
                            // The pref said "signed in", the token says anonymous: only the token
                            // counts. This is what left the app on its full UI (or on Spotify's
                            // anonymous page) with nothing playable — reported after leaving the app
                            // to read the sign-in e-mail, where the session never completed.
                            Logbook.log("anonymous token while the app thought it was signed in: back to the sign-in page")
                            prefs.edit().putBoolean("LoggedIn", false).apply()
                            signedIn = false
                            connecting = false
                            com.music.spotui.di.SessionActions.sessionLost()
                            showLogin()
                        }
                    }
                }
            }
        }
    }


    /** Spotify links (open.spotify.com/...) play their item; voice search plays its best match. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        // Spotify links only, by exact HTTPS host (WebPolicy), never other schemes.
        val link = intent.data?.toString()?.takeIf { app.myspoti.spotify.security.WebPolicy.isDeepLink(it) } ?: return
        // A spotify.link / spoti.fi short link: resolved there.
        if (com.music.spotui.data.api.InviteLinks.handle(link)) return
        val uri = spotifyUri(link) ?: return
        // A shared list (or artist, podcast) opens on its page, as in the official app; a song or
        // an episode still plays (Myspoti 2.0.0).
        if (uri.split(':').getOrNull(1) in setOf("playlist", "album", "artist", "show")) {
            com.music.spotui.data.api.InviteLinks.page.value = uri
            return
        }
        lifecycleScope.launch {
            MyspotiRuntime.api.ready.first { it }
            runCatching { MyspotiRuntime.api.player.play(uri) }
        }
    }

    /** `https://open.spotify.com/intl-fr/album/{id}?si=…` -> `spotify:album:{id}`. */
    private fun spotifyUri(link: String): String? {
        val m = Regex("(?:open|www|play)\\.spotify\\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{22})").find(link) ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }
}

/** Shown between Spotify's login page and the first screen. */
@androidx.compose.runtime.Composable
private fun ConnectingScreen() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = com.music.spotui.ui.theme.AppAccent)
    }
}

/** The sign-in page did not come (or the player did not start after it): what happened, and a way to go again. */
@androidx.compose.runtime.Composable
private fun LoginProblemScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(com.music.spotui.ui.theme.AppBackground).systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.login_failed_title), color = com.music.spotui.ui.theme.AppText, fontFamily = com.music.spotui.ui.theme.AppSans,
            fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(stringResource(R.string.login_failed_detail), color = com.music.spotui.ui.theme.AppText.copy(alpha = 0.6f), fontFamily = com.music.spotui.ui.theme.AppSans,
            fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, bottom = 28.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50))
                .background(com.music.spotui.ui.theme.AppAccent).clickable(onClick = onRetry),
        ) {
            Text(stringResource(R.string.retry_button), color = Color.Black, fontFamily = com.music.spotui.ui.theme.AppSans,
                fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}
