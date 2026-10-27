package com.music.spotui.data.api

import android.util.Log
import app.myspoti.host.android.MyspotiRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared Spotify links that need more than opening: spotify.link / spoti.fi short links, resolved
 * here, and the page of a shared list, opened by App.kt. MySpotilol has no social features
 * (collaborative-playlist invitations, Blends, Jams): a playlist link carrying an invitation
 * token opens that playlist like any other link.
 */
object InviteLinks {
    private const val TAG = "InviteLinks"

    /** A shared playlist / album / artist / podcast link: its page, opened by App.kt (not played). */
    val page = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Spotilol: no automatic redirects, bounded calls (see resolve).
    private val http by lazy { app.myspoti.spotify.security.BoundedHttp.client(callTimeoutSeconds = 15) }
    /** Short-link hosts that are fetched; a hop anywhere else ends the walk without a request. */
    private val LINK_HOSTS = setOf("spotify.link", "spotify.app.link", "spoti.fi")
    private const val MAX_HOPS = 4
    private const val MAX_PAGE_BYTES = 512 * 1024

    private val PLAYLIST = Regex("""(?:open|www|play)\.spotify\.com/(?:intl-[a-z-]+/)?playlist/([A-Za-z0-9]{22})([^\s"'<>]*)""")
    private val SHORT = Regex("""https?://(?:spotify\.link|spoti\.fi)/[^\s"'<>]+""")

    /**
     * An incoming link. True when it is taken here: a short link, resolved on a background thread,
     * whose item then plays like any Spotify link.
     */
    fun handle(link: String): Boolean {
        val short = SHORT.find(link)?.value ?: return false
        scope.launch {
            val target = resolve(short) ?: return@launch
            val m = Regex("""spotify\.com/(?:intl-[a-z-]+/)?(track|album|playlist|artist|show|episode)/([A-Za-z0-9]{22})""").find(target) ?: return@launch
            runCatching {
                MyspotiRuntime.api.ready.first { it }
                MyspotiRuntime.api.player.play("spotify:${m.groupValues[1]}:${m.groupValues[2]}")
            }
        }
        return true
    }

    /**
     * Where a short link leads: the first address off the short-link hosts, or the playlist link
     * inside the page a short-link host answers with. Spotilol: HTTPS only, redirects followed by
     * hand for at most [MAX_HOPS] hops and only on [LINK_HOSTS], pages read up to [MAX_PAGE_BYTES].
     */
    private suspend fun resolve(link: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            var url = link.replaceFirst(Regex("^http://"), "https://")
            repeat(MAX_HOPS) {
                val host = app.myspoti.spotify.security.WebPolicy.httpsHost(url) ?: return@runCatching null
                if (host !in LINK_HOSTS) return@runCatching url
                http.newCall(okhttp3.Request.Builder().url(url).header("user-agent", "Mozilla/5.0").build()).execute().use { r ->
                    if (r.isRedirect) {
                        url = r.header("Location")?.let { r.request.url.resolve(it)?.toString() } ?: return@runCatching null
                    } else {
                        val body = app.myspoti.spotify.security.BoundedHttp.text(r.body, MAX_PAGE_BYTES).replace("\\/", "/")
                        return@runCatching PLAYLIST.find(body.replace("&amp;", "&").replace("\\u0026", "&"))?.value?.let { "https://$it" }
                    }
                }
            }
            null
        }.onFailure { Log.w(TAG, "short link not resolved: ${it.message}") }.getOrNull()
    }
}
