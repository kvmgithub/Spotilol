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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.App
import com.music.spotui.ui.screens.WelcomeScreen
import com.music.spotui.ui.theme.SpotuiTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    /** The one-time setup screen (permissions, battery) after signing in. */
    private var setupNeeded by mutableStateOf(false)

    private val prefs get() = getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        // Diagnostics switched on in Settings: hold the faint dot to copy the redacted logs.
                        if (com.music.spotui.data.BetaLogs.enabled.value || app.myspoti.engine.BuildConfig.DEBUG)
                            com.music.spotui.ui.components.BetaLogButton(
                                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd))
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
        super.onStart()
        com.music.spotui.di.SongPlayer.inForeground = true
    }

    override fun onStop() {
        super.onStop()
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
        MyspotiRuntime.host.attach(root, visible = false)
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

    /** Spotify sent the user back to the player: hide the page now, the session follows. */
    private fun watchLoginReturn() {
        lifecycleScope.launch {
            MyspotiRuntime.host.url.collect { url ->
                val backOnPlayer = app.myspoti.spotify.security.WebPolicy.isPlayerPage(url)
                if (showingLogin && backOnPlayer) {
                    hideLogin()
                    if (!signedIn) connecting = true
                }
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
                    login && !showingLogin && (signedIn || connecting) -> showLogin()
                    ready && !login -> {
                        val user = runCatching { MyspotiRuntime.isSignedIn() }.getOrDefault(false)
                        if (user) {
                            if (com.music.spotui.di.SessionActions.signedOut.value) {
                                // Signed in again after a logout, maybe as someone else: start the
                                // screens over (a new activity also drops their view models).
                                com.music.spotui.di.SessionActions.signedIn()
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                MyspotiRuntime.host.attach(root, visible = false)
                                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                finish()
                                return@collect
                            }
                            if (!signedIn) {
                                prefs.edit().putBoolean("LoggedIn", true).apply()
                                signedIn = true
                            }
                            connecting = false
                            if (showingLogin) hideLogin()
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
        val uri = spotifyUri(link) ?: return
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
