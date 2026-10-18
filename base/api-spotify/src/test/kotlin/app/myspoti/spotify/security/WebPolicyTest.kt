package app.myspoti.spotify.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spotilol's policy regressions, adapted to the Myspoti host. */
class WebPolicyTest {
    @Test
    fun `strict https host parsing`() {
        assertEquals("open.spotify.com", WebPolicy.httpsHost("https://OPEN.SPOTIFY.COM:443/"))
        listOf(
            "http://open.spotify.com/", "https://open.spotify.com@evil.test/", "https://user@open.spotify.com/",
            "https://open.spotify.com:444/", "https://open.spotify.com:0443/", "javascript://open.spotify.com/alert(1)",
            "https://open.spotify.com\\@evil.test/", "https://open.spotify.com./", "https://open.spotify.com/%0a",
            "file:///etc/passwd", "https://open.spotify.com/ x",
        ).forEach { assertNull(WebPolicy.httpsHost(it), it) }
    }

    @Test
    fun `navigation stays on Spotify and the sign-in providers`() {
        listOf(
            "https://open.spotify.com/track/abc", "https://www.spotify.com/", "https://accounts.spotify.com/login",
            "https://challenge.spotify.com/c/session/challenge/recaptcha", "https://accounts.google.com/o/oauth2",
            "https://consent.google.com/", "https://www.facebook.com/login", "https://appleid.apple.com/auth",
        ).forEach { assertTrue(WebPolicy.isNavigation(it), it) }
        listOf(
            "https://evilspotify.com/", "https://open.spotify.com.evil.test/", "https://evil.google.com/",
            "https://mail.google.com/", "https://apple.com/", "https://challenge.spotify.com:8443/",
            "http://accounts.spotify.com/", "intent://open.spotify.com#Intent;end", "spotify:track:abc",
            "https://evilchallenge.spotify.com/", "https://spotify.net/",
        ).forEach { assertFalse(WebPolicy.isNavigation(it), it) }
    }

    @Test
    fun `login pages are recognised by host and route`() {
        assertTrue(WebPolicy.isLoginPage("https://accounts.spotify.com/de/login"))
        assertTrue(WebPolicy.isLoginPage("https://challenge.spotify.com/c/x/y/email"))
        assertTrue(WebPolicy.isLoginPage("https://open.spotify.com/login?continue=x"))
        assertFalse(WebPolicy.isLoginPage("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(WebPolicy.isLoginPage("https://evil.test/accounts.spotify.com/login"))
        assertTrue(WebPolicy.isPlayerPage("https://open.spotify.com/"))
        assertFalse(WebPolicy.isPlayerPage("https://open.spotify.com/login"))
    }

    @Test
    fun `relay only reaches spclient playback routes`() {
        listOf(
            "https://gew4-spclient.spotify.com/connect-state/v1/devices/hobs_1",
            "https://spclient.wg.spotify.com/track-playback/v1/devices",
            "https://gue1-spclient.spotify.com:443/connect-state/v1/player/command/from/a/to/b",
        ).forEach { assertTrue(WebPolicy.isRelayTarget(it), it) }
        listOf(
            "https://gew4-spclient.spotify.com/gabo-receiver-service/public/v3/events",
            "https://api-partner.spotify.com/connect-state/v1/x", "https://evil-spclient.spotify.com.evil.test/connect-state/v1/",
            "http://gew4-spclient.spotify.com/connect-state/v1/", "https://gew4-spclient.spotify.com:8443/connect-state/v1/",
            "https://gew4-spclient.spotify.com/connect-state/v1/../../melody/v1/x", "https://a.b-spclient.spotify.com/connect-state/v1/",
            "https://evil.test/?https://gew4-spclient.spotify.com/connect-state/v1/",
        ).forEach { assertFalse(WebPolicy.isRelayTarget(it), it) }
    }

    @Test
    fun `token calls and DRM stay with Spotify`() {
        assertTrue(WebPolicy.isSpotifyApi("https://api-partner.spotify.com/pathfinder/v2/query"))
        assertFalse(WebPolicy.isSpotifyApi("http://api.spotify.com/"))
        assertFalse(WebPolicy.isSpotifyApi("https://api.spotify.com.evil.test/"))
        assertFalse(WebPolicy.isSpotifyApi("https://lrclib.net/api/get"))
        assertTrue(WebPolicy.isDrmOrigin("https://open.spotify.com"))
        assertFalse(WebPolicy.isDrmOrigin("https://www.google.com"))
        assertFalse(WebPolicy.isDrmOrigin("https://accounts.spotify.com"))
    }

    @Test
    fun `restored cookies keep their protection and refuse injection`() {
        assertTrue(WebPolicy.restoreCookie("sp_dc=secret")!!.contains("HttpOnly"))
        assertTrue(WebPolicy.restoreCookie("sp_t=preference")!!.contains("Secure"))
        assertNull(WebPolicy.restoreCookie("Domain=evil.test"))
        assertNull(WebPolicy.restoreCookie("a=b; Domain=evil.test"))
        assertNull(WebPolicy.restoreCookie("a=b\r\nSet-Cookie: x=y"))
        assertNull(WebPolicy.restoreCookie("=novalue"))
    }

    @Test
    fun `bounded reads stop at the limit`() {
        assertEquals(6, BoundedHttp.readBytes("héllo".byteInputStream(), 6).size)
        var exceeded = false
        try { BoundedHttp.readBytes(ByteArray(7).inputStream(), 6) } catch (_: java.io.IOException) { exceeded = true }
        assertTrue(exceeded)
    }
}
