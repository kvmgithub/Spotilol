package app.myspoti.spotify.bridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Audio ads. Spotify's server puts them in the player's queue: the track-playback state machine
 * it sends to the page about three minutes ahead, where the song before the ad `advance`s to an
 * ad state at its end. [withoutAds] sends that end to the song after the ad instead, before the
 * page sees the machine, so the page never reaches an ad (Myspoti 2.0.0). The page's own ad
 * managers are switched off from the bridge (`ads.off`), the ad media and Spotify's ad hosts are
 * refused here, and an ad that still shows up is muted and skipped by
 * [app.myspoti.spotify.SpotifyPlayer]. The device registration is left as the Web Player sends
 * it: removing the ad formats from it was the suspected cause of `402 COMMAND_FAILED` refusals
 * for free accounts (Myspoti 2.0.0-beta.9).
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

    /**
     * A track-playback reply (a state machine) with its ads left out, or null when it leads to no
     * ad. Measured on 20/10 (desktop, free account): a refused ad now stays current for good, and
     * every skip or play is refused while it is ("ad_disallow"); only a reload left it. Spotify's
     * state machine ties the ad to the song before it: that song's `advance` (its natural end)
     * goes to the ad, and then `advance` is sent where its `skip_next` goes, so the page plays the
     * next song at the end of this one, as after a skip. Measured: the next song started 60 ms
     * after the end, and Spotify accepted every state the page reported (200). When `skip_next`
     * leads to the ad as well (an ad already left out once), nothing here can help and the reply
     * is left as it is; an ad that is already the current state cannot be left out either.
     */
    fun withoutAds(body: String): Pair<String, List<String>>? {
        if ("\"AD\"" !in body) return null
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val notes = mutableListOf<String>()
        val out = root.toMutableMap()
        for (key in listOf("state_machine", "previous_state_machine")) {
            val machine = root[key] as? JsonObject ?: continue
            machineWithoutAds(machine, notes)?.let { out[key] = it }
        }
        if (out == root) return null
        return JsonObject(out).toString() to notes
    }

    /**
     * A song whose two ways on — its end and its skip — both lead to an ad: Spotify does that once
     * an ad was left out by [withoutAds]. [nextUri] (the song the page shows as next) is then
     * played just before the song ends: a plain play command, which Spotify answers with a new
     * queue the ad is no longer reached from. Measured on 20/10 (desktop, free account): played
     * 1 s before the end, the next song started at once, every report was answered 200, and the
     * songs after it went on without an ad.
     */
    data class AdAhead(val songUid: String?, val songUri: String?, val nextUri: String)

    /** The last [AdAhead] the host saw in Spotify's replies; the player plays on and clears it. */
    val ahead = MutableStateFlow<AdAhead?>(null)

    /** The [AdAhead] in a track-playback reply (after [withoutAds]), or null. */
    fun adAhead(body: String): AdAhead? = runCatching {
        if ("\"AD\"" !in body) return null
        val root = Json.parseToJsonElement(body).jsonObject
        val machine = root["state_machine"] as? JsonObject ?: return null
        val tracks = machine["tracks"] as? JsonArray ?: return null
        val states = machine["states"] as? JsonArray ?: return null
        fun index(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        fun trackOf(state: Int) = index(states.getOrNull(state) as? JsonObject, "track")?.let { tracks.getOrNull(it) as? JsonObject }
        fun isAd(state: Int) = (trackOf(state)?.get("content_type") as? JsonPrimitive)?.contentOrNull == "AD"
        fun uriOf(state: Int) = ((trackOf(state)?.get("metadata") as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull
        val current = index(root["updated_state_ref"] as? JsonObject, "state_index") ?: return null
        if (isAd(current)) return null
        val state = states.getOrNull(current) as? JsonObject ?: return null
        val transitions = state["transitions"] as? JsonObject ?: return null
        fun to(key: String) = index(transitions[key] as? JsonObject, "state_index")
        val end = to("advance") ?: return null
        if (!isAd(end) || to("skip_next")?.let { !isAd(it) } == true) return null
        val next = to("show_next")?.takeIf { !isAd(it) }?.let(::uriOf) ?: return null
        AdAhead((state["track_uid"] as? JsonPrimitive)?.contentOrNull, uriOf(current), next)
    }.getOrNull()

    private fun machineWithoutAds(machine: JsonObject, notes: MutableList<String>): JsonObject? {
        val tracks = machine["tracks"] as? JsonArray ?: return null
        val states = machine["states"] as? JsonArray ?: return null
        fun trackOf(state: Int) = ((states.getOrNull(state) as? JsonObject)?.get("track") as? JsonPrimitive)
            ?.contentOrNull?.toIntOrNull()?.let { tracks.getOrNull(it) as? JsonObject }
        fun isAd(state: Int) = (trackOf(state)?.get("content_type") as? JsonPrimitive)?.contentOrNull == "AD"
        fun nameOf(state: Int) = ((trackOf(state)?.get("metadata") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        fun indexOf(transition: JsonObject) = (transition["state_index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        var changed = false
        val rewritten = states.mapIndexed { i, element ->
            val state = element as? JsonObject ?: return@mapIndexed element
            val transitions = state["transitions"] as? JsonObject ?: return@mapIndexed element
            if (isAd(i)) return@mapIndexed element
            // Only a skip is a state the server knows: `show_next` points at a display-only state,
            // and a page sent there had every report refused (502 unknown_state_ref) and stopped.
            val target = (transitions["skip_next"] as? JsonObject)?.takeIf { t -> indexOf(t)?.let { !isAd(it) } == true }
            val next = transitions.toMutableMap()
            for (key in listOf("advance", "skip_next")) {
                val t = transitions[key] as? JsonObject ?: continue
                if (indexOf(t)?.let(::isAd) != true) continue
                if (target == null) { notes += "$key after ${nameOf(i)}: an ad, and no song to send it to"; continue }
                next[key] = JsonObject(target.toMutableMap().apply { t["paused"]?.let { put("paused", it) } })
                notes += "$key after ${nameOf(i)}: ${indexOf(target)?.let(::nameOf)} instead of an ad"
            }
            if (next == transitions) element else { changed = true; JsonObject(state.toMutableMap().apply { put("transitions", JsonObject(next)) }) }
        }
        return if (changed) JsonObject(machine.toMutableMap().apply { put("states", JsonArray(rewritten)) }) else null
    }

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
