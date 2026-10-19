package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Where the Web Player page runs: an Android WebView or desktop Chrome over CDP.
 *
 * A host loads open.spotify.com with the user's session, injects [BridgeScript] after each
 * page load, blocks ad audio ([AdBlock.blockedHosts]) and relays the page's messages.
 */
interface BridgeHost {
    /** Runs [script] in the page. Fire and forget: answers come back through [messages]. */
    suspend fun evaluate(script: String)

    /** JSON strings posted by the bridge (`MyspotiHost.postMessage` / `__mspHostPost`). */
    val messages: Flow<String>
}

/** The bridge's source, bundled as a resource of this module. */
object BridgeScript {
    val source: String by lazy {
        BridgeScript::class.java.getResourceAsStream("/bridge/player-bridge.js")!!
            .bufferedReader().use { it.readText() }
    }
}

/**
 * Audio ads. Spotify's server puts them in the player's queue (track-playback state machine).
 * The page's own ad managers are switched off from the bridge (`ads.off`), the ad media and
 * Spotify's ad hosts are refused here, and an ad that still shows up is muted and skipped by
 * [app.myspoti.spotify.SpotifyPlayer]. The device registration is left as the Web Player
 * sends it: removing the ad formats from it was the suspected cause of `402 COMMAND_FAILED`
 * refusals for free accounts (Myspoti 2.0.0-beta.9).
 */
object AdBlock {
    /**
     * Spotilol's scope: ad media, third-party ad servers and trackers, Spotify's own ad hosts
     * and its event telemetry are refused. (Upstream Myspoti leaves Spotify's ad and event
     * endpoints reachable so the client looks ordinary; this variant prefers less telemetry,
     * as Spotilol does.) Ads that come anyway are muted and skipped by the player.
     */
    val adHosts = listOf(
        // Ad audio and ad media.
        "adstudio-assets.scdn.co", "mp3ad.scdn.co", "amillionads.com", "2mdn.net", "adxcel.com",
        // Spotify's ad hosts.
        "audio-ads.spotify.com", "ads-akp.spotify.com", "ads-fa.spotify.com", "adstudio.spotify.com", "ads.spotify.com",
        // Google's ad servers (audio and video ads served in other markets).
        "doubleclick.net", "googlesyndication.com", "imasdk.googleapis.com",
    )

    /** Tracking and event hosts: answered with an empty success so the page does not retry. */
    val telemetryHosts = listOf(
        "adeventtracker.spotify.com", "pixel-static.spotify.com", "pixel.spotify.com", "aet.spotify.com",
        "googletagmanager.com", "google-analytics.com", "scorecardresearch.com", "fastly-insights.com",
        "sentry.io", "t.6sc.co", "tracker.samplicio.us", "adsrvr.org",
    )

    /** Kept for callers listing everything refused. */
    val blockedHosts = adHosts + telemetryHosts

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()

    private fun pathOf(url: String): String =
        "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')

    private fun spotifyOwned(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com") || host == "scdn.co" || host.endsWith(".scdn.co")

    private fun List<String>.matchesHost(host: String) = any { host == it || host.endsWith(".$it") }

    /** Ad media: refused (403), the player reports a failed ad and moves on. */
    fun isAd(url: String): Boolean {
        val host = hostOf(url)
        return adHosts.matchesHost(host) || (spotifyOwned(host) && "/mp3-ad/" in pathOf(url))
    }

    /** Event telemetry and trackers (Spotify's gabo event sender included). */
    fun isTelemetry(url: String): Boolean {
        val host = hostOf(url)
        if (telemetryHosts.matchesHost(host) || "retargeting-pixels" in url) return true
        return spotifyOwned(host) && pathOf(url).startsWith("/gabo-receiver-service/")
    }

    fun isBlocked(url: String): Boolean = isAd(url) || isTelemetry(url)

    /** The player's registration (POST track-playback/v1/devices); see [withDeviceName]. */
    fun isDeviceRegistration(method: String, url: String): Boolean =
        method.equals("POST", ignoreCase = true) && url.substringBefore('?').endsWith("/track-playback/v1/devices")

    /**
     * The name the player registers itself under is the name Spotify shows on every other device.
     * The Web Player's own ("Web Player (Chrome)") is replaced by the name the phone already has,
     * so this client appears as the phone it runs on; **nothing else is touched** — capabilities,
     * platform, versions stay byte-for-byte what the real Web Player sends.
     *
     * An earlier version also removed `manifest_urls_audio_ad` from the manifest formats, which made
     * this registration the only one of its kind (the real one declares it twice) and was the prime
     * suspect for the `402 COMMAND_FAILED` refusals. Ads are blocked at the URL level instead
     * ([blockedHosts]), not by lying about what this client can play.
     */
    fun withDeviceName(body: String, name: String): String = runCatching {
        val device = Json.parseToJsonElement(body).jsonObject["device"]?.jsonObject ?: return body
        val raw = device["name"] as? kotlinx.serialization.json.JsonPrimitive ?: return body
        if (raw is kotlinx.serialization.json.JsonNull) return body
        val old = raw.content
        if (old.isBlank() || old == name || name.isBlank()) return body
        // A plain string swap, not a re-serialisation: the registration keeps its exact shape,
        // duplicate array entries included.
        val quoted = name.replace("\\", "\\\\").replace("\"", "\\\"")
        body.replace("\"$old\"", "\"$quoted\"")
    }.getOrDefault(body)
}
