package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdBlockTest {
    @Test
    fun `blocks the ad audio host only`() {
        assertTrue(AdBlock.isBlocked("https://adstudio-assets.scdn.co/mp3/d0448345e619692e0b13626ad4994d4b824f7107.mp3"))
        assertFalse(AdBlock.isBlocked("https://audio-fa.scdn.co/audio/abc"))
        assertFalse(AdBlock.isBlocked("https://i.scdn.co/image/ab67616d0000b273"))
        assertFalse(AdBlock.isBlocked("https://evil.com/adstudio-assets.scdn.co/x.mp3"))
    }

    @Test
    fun `blocks ad servers and Spotify's ad hosts (Spotilol scope)`() {
        assertTrue(AdBlock.isAd("https://pubads.g.doubleclick.net/gampad/ads?x=1"))
        assertTrue(AdBlock.isAd("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
        assertTrue(AdBlock.isAd("https://audio-ads.spotify.com/x"))
        assertTrue(AdBlock.isAd("https://audio-fa.scdn.co/mp3-ad/abc"))
        assertFalse(AdBlock.isAd("https://gew4-spclient.spotify.com/track-playback/v1/devices"))
        assertFalse(AdBlock.isAd("https://gew4-spclient.spotify.com/metadata/4/track/abc?ads=1"))
        assertFalse(AdBlock.isAd("https://api-partner.spotify.com/pathfinder/v2/query"))
        assertFalse(AdBlock.isBlocked("https://open.spotify.com/ads/"))
        assertFalse(AdBlock.isBlocked("https://evilads.spotify.com.example/x"))
    }

    @Test
    fun `telemetry is answered empty, playback traffic is untouched`() {
        assertTrue(AdBlock.isTelemetry("https://gew4-spclient.spotify.com/gabo-receiver-service/public/v3/events"))
        assertTrue(AdBlock.isTelemetry("https://spclient.wg.spotify.com/gabo-receiver-service/v3/events?x=1"))
        assertTrue(AdBlock.isTelemetry("https://adeventtracker.spotify.com/x"))
        assertTrue(AdBlock.isTelemetry("https://o1.ingest.sentry.io/api/1/envelope/"))
        assertTrue(AdBlock.isTelemetry("https://www.google-analytics.com/g/collect"))
        assertFalse(AdBlock.isTelemetry("https://evil.test/gabo-receiver-service/x"))
        assertFalse(AdBlock.isTelemetry("https://gew4-spclient.spotify.com/connect-state/v1/devices/hobs_1"))
        assertFalse(AdBlock.isTelemetry("https://gew4-spclient.spotify.com/track-playback/v1/devices"))
        assertFalse(AdBlock.isBlocked("https://i.scdn.co/image/ab67616d0000b273"))
        assertFalse(AdBlock.isBlocked("https://open.spotifycdn.com/cdn/build/web-player/web-player.js"))
    }

    @Test
    fun `promotion shelves and sponsored cards are told apart`() {
        assertTrue(app.myspoti.spotify.isPromotion("HomePromotionSectionData"))
        assertTrue(app.myspoti.spotify.isPromotion("HomePromotionShelf"))
        assertTrue(app.myspoti.spotify.isPromotion("SponsoredPlaylist"))
        assertTrue(app.myspoti.spotify.isPromotion("EmbeddedAdVisual"))
        assertFalse(app.myspoti.spotify.isPromotion("HomeGenericSectionData"))
        assertFalse(app.myspoti.spotify.isPromotion("PlaylistResponseWrapper"))
        assertFalse(app.myspoti.spotify.isPromotion("AlbumResponseWrapper"))
        assertFalse(app.myspoti.spotify.isPromotion("Audiobook"))
    }

    @Test
    fun `base62 ids convert to metadata gids`() {
        // Pair checked against a captured metadata/4 request.
        assertTrue(app.myspoti.spotify.base62ToHex("55vPnuXfuzXNW75JpuSczF") == "a734c1d4e4d84c4093c32d5bce9797a3")
    }

    @Test
    fun `bridge script is bundled`() {
        assertTrue(BridgeScript.source.contains("window.Myspoti"))
    }
}
