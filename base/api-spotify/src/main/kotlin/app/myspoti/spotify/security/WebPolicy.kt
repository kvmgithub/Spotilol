package app.myspoti.spotify.security

import java.net.URI
import java.util.Locale

/**
 * Central trust decisions, ported from Spotilol's WebSecurityPolicy.
 * Authority is never decided by URL substring matching: every check parses the URL and
 * compares the exact HTTPS host on the default port.
 */
object WebPolicy {
    const val PLAYER_ORIGIN = "https://open.spotify.com"

    /** Spotify pages the main frame may show (player, links, account pages). */
    private val LINKS = setOf("open.spotify.com", "spotify.com", "www.spotify.com", "play.spotify.com", "spotify.link")

    /** Sign-in pages, including the identity providers Spotify's login offers. */
    private val LOGIN = setOf(
        "accounts.spotify.com", "challenge.spotify.com",
        "accounts.google.com", "consent.google.com",
        "www.facebook.com", "m.facebook.com", "appleid.apple.com",
    )

    private val SPCLIENT = Regex("[a-z0-9]+-spclient\\.spotify\\.com")
    private val RELAY_PATHS = listOf("/connect-state/v1/", "/track-playback/v1/")

    /** The lower-case host of a strict HTTPS URL on the default port, else null. */
    fun httpsHost(url: String?): String? {
        if (url == null || url.length > 8192 || url.indexOf('\\') >= 0) return null
        if (url.any { it.code <= 32 || it.code == 127 }) return null
        val lower = url.lowercase(Locale.ROOT)
        if ("%0a" in lower || "%0d" in lower || "%00" in lower) return null
        return try {
            val uri = URI(url)
            if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host.endsWith(".") || !host.matches(Regex("[a-z0-9.-]+"))) return null
            // Alternate port spellings and encoded authorities are parser-difference bait.
            val authority = uri.rawAuthority.lowercase(Locale.ROOT)
            if (authority != host && authority != "$host:443") return null
            host
        } catch (_: Exception) {
            null
        }
    }

    private fun path(url: String): String = runCatching { URI(url).rawPath.orEmpty() }.getOrDefault("")

    fun isPlayer(url: String?): Boolean = httpsHost(url) == "open.spotify.com"

    /** Main-frame navigation target the hidden player may load. */
    fun isNavigation(url: String?): Boolean = httpsHost(url).let { it in LINKS || it in LOGIN }

    /**
     * A sign-in page: Spotify's account or challenge pages, a /login route on Spotify, or an
     * identity provider's page while "Continue with Google / Facebook / Apple" runs.
     */
    fun isLoginPage(url: String?): Boolean {
        val host = httpsHost(url) ?: return false
        if (host in LOGIN) return true
        return (host == "spotify.com" || host.endsWith(".spotify.com")) && "/login" in path(url!!)
    }

    /** The player is back on its own page (not a login route). */
    fun isPlayerPage(url: String?): Boolean = isPlayer(url) && !isLoginPage(url)

    /** Playback control requests the app repeats for the page (see ControlRelay). */
    fun isRelayTarget(url: String?): Boolean {
        val host = httpsHost(url) ?: return false
        if (host != "spclient.wg.spotify.com" && !SPCLIENT.matches(host)) return false
        val p = path(url!!)
        return RELAY_PATHS.any { p.startsWith(it) } && ".." !in p
    }

    /** Direct API calls with the account's token: HTTPS Spotify hosts only. */
    fun isSpotifyApi(url: String?): Boolean {
        val host = httpsHost(url) ?: return false
        return host == "spotify.com" || host.endsWith(".spotify.com")
    }

    /** Protected media (Widevine) is granted to the player page only. */
    fun isDrmOrigin(origin: String?): Boolean = isPlayer(origin)

    /** Spotify links the app opens itself, spoti.fi short links included (resolved by the app, not navigated to). */
    fun isDeepLink(url: String?): Boolean = httpsHost(url).let { it in LINKS || it == "spoti.fi" }

    /**
     * A cookie pair safe to restore into the WebView: no attributes, no header injection.
     * getCookie() drops attributes, so the auth cookie gets Secure and HttpOnly back.
     */
    fun restoreCookie(pair: String?): String? {
        if (pair == null || pair.indexOf('=') <= 0 || ';' in pair || '\r' in pair || '\n' in pair) return null
        val name = pair.substring(0, pair.indexOf('=')).trim()
        if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) return null
        if (name.lowercase(Locale.ROOT) in setOf("domain", "path", "expires", "max-age", "samesite")) return null
        return "$pair; Path=/; Secure" + if (name == "sp_dc") "; HttpOnly" else ""
    }
}
