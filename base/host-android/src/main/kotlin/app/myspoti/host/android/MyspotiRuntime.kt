package app.myspoti.host.android

import android.content.Context
import app.myspoti.api.MyspotiApi
import app.myspoti.spotify.SpotifyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The app's single Spotify session: one WebView host and one [SpotifyApi] for the process.
 * Created on the main thread (WebView requirement), usually from Application.onCreate.
 */
object MyspotiRuntime {
    lateinit var host: WebPlayerHost
        private set
    lateinit var spotify: SpotifyApi
        private set
    val api: MyspotiApi get() = spotify
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val isStarted: Boolean get() = ::host.isInitialized
    val loginRequired: StateFlow<Boolean> get() = host.loginRequired

    /** [loadPage] false (data saver on a metered network): nothing goes out until [resume]. */
    fun start(context: Context, loadPage: Boolean = true) {
        if (isStarted) return
        val updater = app.myspoti.spotify.gql.GqlOpsUpdater(okhttp3.OkHttpClient(), java.io.File(context.filesDir, "spotify/gql-ops.json"))
        host = WebPlayerHost(context)
        spotify = SpotifyApi(host, scope, language = Locale.getDefault().language, ops = updater.load())
        spotify.bridge.logger = { line -> android.util.Log.d(WebPlayerHost.LOG, line) }
        host.onPageLoaded = { scope.launch { spotify.onPageLoaded() } }
        host.onPageStarting = { spotify.onPageStarting() }
        spotify.player.reloadHost = { host.reload() }
        // The bridge, the page and the player in Logcat (diagnostics of a bug report): what
        // changes is logged when it changes, not once every 10 s whether or not anything happened.
        scope.launch {
            var last = ""
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val s = spotify.player.state.value
                val line = "status ready=${spotify.ready.value} login=${host.loginRequired.value} " +
                    "track=${s?.item?.name} paused=${s?.isPaused} buffering=${s?.isBuffering} url=${host.url.value.take(60)}"
                if (line != last) {
                    last = line
                    android.util.Log.d(WebPlayerHost.LOG, line)
                }
            }
        }
        if (!loadPage) return
        host.load()
        // New Spotify build: pick up its operation hashes (public bundles, no account).
        scope.launch {
            runCatching { updater.refresh(spotify.opsInUse) }.getOrNull()?.let(spotify::updateOps)
        }
    }

    /** True when the Web Player holds a signed-in user's token (not an anonymous one). */
    suspend fun isSignedIn(): Boolean = spotify.isSignedIn()

    /** Data saver: frees the page; [resume] loads it again. */
    fun suspendPlayer() = host.unload()
    fun resume() = host.load()
}
