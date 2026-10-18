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
 * Audio ads. Spotify's server puts them in the player's queue (track-playback state machine),
 * each with an audio file in a format of its own. The player registers itself without those
 * formats ([withoutAdFormats]), so an ad comes with nothing to play; the ad audio host is
 * refused as well. The player then halts at the end of the song before the ad, and
 * [app.myspoti.spotify.SpotifyPlayer] starts the next song.
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

    /** Formats only ads use: the ad mp3 links and the ad manifests. Songs and episodes use others. */
    val adFormats = setOf("manifest_urls_audio_ad", "file_urls_mp3")

    /** The player's registration (POST track-playback/v1/devices), to rewrite with [withoutAdFormats]. */
    fun isDeviceRegistration(method: String, url: String): Boolean =
        method.equals("POST", ignoreCase = true) && url.substringBefore('?').endsWith("/track-playback/v1/devices")

    /** [body] (the registration JSON) without [adFormats] in its manifest formats; unchanged when unreadable. */
    fun withoutAdFormats(body: String): String = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val device = root["device"]?.jsonObject ?: return body
        val caps = device["capabilities"]?.jsonObject ?: return body
        val formats = caps["manifest_formats"]?.jsonArray ?: return body
        val kept = JsonArray(formats.filter { (it as? JsonPrimitive)?.contentOrNull !in adFormats })
        JsonObject(root + ("device" to JsonObject(device + ("capabilities" to JsonObject(caps + ("manifest_formats" to kept)))))).toString()
    }.getOrDefault(body)
}
