package app.myspoti.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.Player
import app.myspoti.api.PlayerState
import app.myspoti.api.Queue
import app.myspoti.api.Repeat
import app.myspoti.api.Uri
import app.myspoti.spotify.bridge.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Web Player's own player, driven through the bridge.
 *
 * Ads: hosts refuse the ad audio they know. Such an ad then stays current, buffering at
 * 0:00, and the page never moves on by itself: after [AD_STUCK_MS] the song after the last
 * one is played. An ad that "plays" (it came without a file, or from another market's ad
 * server) is muted at once and, as it refuses skips, the page is reloaded after
 * [AD_PLAYING_SKIP_MS]; if the page still holds on to it after that reload, it stays muted
 * until it ends. [adPlaying] says an ad is
 * on; ads are never exposed in [state] (the last song stays there, buffering).
 *
 * Stalls: on some phones the page stops between two songs (stuck past the end, paused at
 * the end with a next song, buffering for ever). After [STUCK_MS] the next song is played;
 * if that does not help either, [reloadHost] reloads the page and playback resumes there.
 */
class SpotifyPlayer(private val bridge: Bridge, private val scope: CoroutineScope) : Player {
    private val _state = MutableStateFlow<PlayerState?>(null)
    override val state: StateFlow<PlayerState?> = _state
    private val _queue = MutableStateFlow<Queue?>(null)
    override val queue: StateFlow<Queue?> = _queue

    private var lastTrackState: PlayerState? = null
    private val _adPlaying = MutableStateFlow(false)
    /** An ad is playing (the page plays it to its end). */
    val adPlaying: StateFlow<Boolean> = _adPlaying
    @Volatile private var adSince = 0L
    @Volatile private var adState: PlayerState? = null
    /**
     * An ad is muted: the page's own media element is set to an unheard volume ("media.mute"),
     * not the player's volume, which goes through Spotify Connect and is refused on some
     * accounts (DEVICE_DOES_NOT_SUPPORT_COMMAND), leaving the ad audible.
     */
    @Volatile private var adMuted = false
    /** When [muteForAd] was applied, so a mute outliving the break is lifted (never stuck). */
    @Volatile private var adMutedAt = 0L
    /** When the page last reported the ad break (it never reports the end of one). */
    @Volatile private var adStateAt = 0L
    /** The page's own ad managers were switched off for this break (see [BridgeHost]). */
    @Volatile private var adsOffSent = false
    /** The page's in-stream ad manager was asked to end this break (a lighter lever than a reload). */
    @Volatile private var adSkipped = false
    /** How many times the ad mute was sent this break: the media element may appear late. */
    @Volatile private var adMuteTries = 0
    /** A pause asked through [pause]; it holds until playback is asked for again, never expires. */
    @Volatile private var pausedByUser = false
    /** The order given to the last [play] (context uri to songs), kept for a stall restart. */
    @Volatile private var lastOrder: Pair<Uri, List<Uri>>? = null

    /** The volume the user wants (0..1), put back when the page is left silent by mistake. */
    @Volatile private var wantedVolume: Double? = null
    /** The last [play] sent: (context, track, when), for a refused command's retry. */
    @Volatile private var lastPlay: Triple<Uri, Uri?, Long>? = null
    @Volatile private var refusedRetried = false
    private val _refused = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Spotify refused a play even after the retry (free accounts in some countries can't
     * pick a song): the UI says so.
     */
    val refused: kotlinx.coroutines.flow.SharedFlow<Unit> = _refused

    /** Reloads the Web Player page (set by the host); used when a stalled player does not recover. */
    @Volatile var reloadHost: (suspend () -> Unit)? = null

    /**
     * Whether Spotify is reachable (set by the host). Offline, a song stuck at its end cannot
     * be helped: the stall recovery waits instead of replaying and reloading every few seconds,
     * and tries once when the network is back.
     */
    @Volatile var online: () -> Boolean = { true }

    /** Streaming quality ("auto", "low", "normal", "high", "very_high"), kept for every page load. */
    @Volatile var audioQuality: String = "auto"
        private set

    /**
     * Wakes the watcher before its next tick (an ad has just started). Declared before init:
     * the watcher starts there, at once on the main dispatcher.
     */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch {
            bridge.events.collect { (type, payload) ->
                when (type) {
                    "state" -> onState(playerState(payload))
                    "queue" -> queue(payload)?.let { q -> _queue.value = q.copy(nextUp = q.nextUp.filter { it.kind != ItemKind.AD }) }
                    "refused" -> onRefused()
                    "boost" -> (payload as? JsonObject)?.let(::onBoost)
                }
            }
        }
        scope.launch { watchStalls() }
        // One collector, so a mute and the unmute after it never run out of order.
        scope.launch { _adPlaying.collect { if (it) muteForAd() else unmuteAfterAd() } }
        // A reloaded page starts on "auto": the chosen quality is set again.
        scope.launch {
            bridge.ready.collect {
                if (!it) {
                    // The page went away (reloaded by Android, the WebView or the host): what
                    // played then is picked up again once the new page is there.
                    // A page that unloads reports "nothing plays" first: the song before that counts.
                    val now = System.currentTimeMillis()
                    val last = lastTrackState ?: lastSong?.takeIf { now - lastSongUntil < UNLOAD_NULL_MS }
                    if (playingAtLoss == null) playingAtLoss = last?.takeIf { s -> !s.isPaused && !pausedByUser && s.item != null }
                        ?.let { s -> s.copy(positionMs = s.positionAt(now), timestampMs = now) }
                    return@collect
                }
                val lost = playingAtLoss
                playingAtLoss = null
                if (!ownReload && (lost != null || liveIntent() != null)) scope.launch {
                    runCatching { if (lost != null) resumeAfterReload(lost) else { delay(RESTORE_GAP_MS); playIntent() } }
                }
                if (audioQuality != "auto") applyAudioQuality()
                if (boost > 1.0) applyBoost()
                // The page keeps its volume: an app closed during a muted ad must not stay silent.
                runCatching { if (volume() == 0.0) setVolume(wantedVolume?.takeIf { v -> v > 0.0 } ?: 1.0) }
                // A reloaded page during an ad break plays the ad again: muted again at once.
                if (_adPlaying.value) muteForAd()
            }
        }
    }

    /** Set while [reloadAndPlayOn] runs: that reload says itself what plays next. */
    @Volatile private var ownReload = false
    /** What was playing when the page went away; null when nothing was. */
    @Volatile private var playingAtLoss: PlayerState? = null
    /** The last state with a song, and when the page stopped reporting it (a null state). */
    @Volatile private var lastSong: PlayerState? = null
    @Volatile private var lastSongUntil = 0L
    /** Bumped at every state the page reports. */
    @Volatile private var stateSeq = 0

    /**
     * Reloads the page, waits for it, then plays from [s]: the song after it ([next], a song
     * stuck at its end or an ad) or [s] itself where it was (a frozen page).
     */
    private suspend fun reloadAndPlayOn(s: PlayerState?, next: Boolean = true) {
        val reload = reloadHost ?: return
        ownReload = true
        try {
            runCatching {
                reload()
                withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it } } ?: return@runCatching
                if (s == null) return@runCatching
                if (next) { delay(1_500); playOn(s) } else resumeAfterReload(s)
            }
        } finally {
            ownReload = false
            playingAtLoss = null
        }
    }

    /**
     * Reloads the page and waits until it is ready again; nothing is played. For a page whose
     * Spotify Connect layer hangs (a transfer from a device that went away never ends).
     */
    suspend fun reloadPage(): Boolean {
        val reload = reloadHost ?: return false
        ownReload = true
        try {
            reload()
            return withTimeoutOrNull(45_000) { delay(1_500); bridge.ready.first { it } } != null
        } finally {
            ownReload = false
            playingAtLoss = null
        }
    }

    /**
     * A new page after [lost] was playing: the page restores the session paused, or not at
     * all, and takes commands only once its device is registered again. The song goes on from
     * where it was, checked and tried again, unless the user paused or played something since.
     */
    private suspend fun resumeAfterReload(lost: PlayerState) {
        // Skipped during the reload: that song, not the one that played before it.
        if (liveIntent() != null) {
            val seq = stateSeq
            withTimeoutOrNull(RESTORE_WAIT_MS) { while (stateSeq == seq) delay(100) }
            playIntent()
            return
        }
        val item = lost.item ?: return
        val uri = item.uri
        val at = lost.positionMs
        // A play newer than [since] is the user's: the restore stops there.
        var since = lastPlay?.third ?: 0L
        val seq = stateSeq
        // The restored session (or nothing, when the page has none): a real state, or a while.
        withTimeoutOrNull(RESTORE_WAIT_MS) { while (stateSeq == seq || _state.value?.item == null) delay(100) }
        bridge.logger?.invoke("page back after a reload: ${item.name} goes on at ${at / 1000} s")
        for (attempt in 1..RESTORE_ATTEMPTS) {
            if (pausedByUser || (lastPlay?.third ?: 0L) != since) return
            val s = _state.value
            if (s != null && s.item?.uri == uri && !s.isPaused) return
            runCatching {
                if (s?.item?.uri == uri) {
                    resume()
                    if (kotlin.math.abs(s.positionMs - at) > 5_000) seek(at)
                } else {
                    val ctx = lost.contextUri ?: uri
                    play(ctx, trackUri = uri.takeIf { ctx != it }, positionMs = at, order = lastOrder?.takeIf { it.first == ctx }?.second)
                }
            }.onFailure { bridge.logger?.invoke("restore attempt $attempt failed: ${it.message}") }
            // The play just sent is ours, not the user's.
            since = lastPlay?.third ?: 0L
            val ok = withTimeoutOrNull(RESTORE_CHECK_MS) { _state.first { it?.item?.uri == uri && !it.isPaused } }
            if (ok != null) return
            delay(RESTORE_GAP_MS * attempt)
        }
        bridge.logger?.invoke("page back after a reload: ${item.name} did not start again")
    }

    /** The media element as the watcher last read it. */
    @Volatile private var lastMedia: Media? = null

    /** Loads the song after the current one in advance (see "player.preloadNext"); on by default. */
    @Volatile var preloadNext = true
    /** "current|next" already preloaded. */
    private var preloadedFor: String? = null

    /**
     * Once a song has played a couple of seconds here, the next one is loaded: a skip then
     * starts ~0.3 s sooner. The page does it itself only 10 s before the end, so the bytes are
     * the same when the song plays out; again when the next song changes (queue edits).
     */
    private suspend fun preloadNextSong(s: PlayerState?, media: Media?) {
        if (!preloadNext || s == null || s.isPaused || s.isBuffering || _adPlaying.value || media == null || media.active == false) return
        val next = s.next?.takeIf { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE } ?: return
        if (s.positionAt(System.currentTimeMillis()) < PRELOAD_AFTER_MS) return
        val key = "${s.item?.uri}|${next.uri}"
        if (key == preloadedFor) return
        preloadedFor = key
        runCatching { bridge.call("player.preloadNext", timeoutMs = 5_000, quiet = true) }
    }

    /** The page's media element as `media.state` reads it. [active]: null when unknown. */
    private data class Media(
        val paused: Boolean,
        val muted: Boolean,
        val level: Double,
        val own: Double,
        val time: Double,
        /** What the page player itself is set to; null when the page did not say. */
        val playerVolume: Double?,
        val active: Boolean?,
    )

    private suspend fun readMedia(): Media? {
        val o = bridge.call("media.state", timeoutMs = MEDIA_TIMEOUT_MS, quiet = true) as? JsonObject ?: return null
        fun p(k: String) = o[k] as? kotlinx.serialization.json.JsonPrimitive
        return Media(
            paused = p("paused")?.booleanOrNull ?: true,
            muted = p("muted")?.booleanOrNull ?: false,
            level = p("level")?.doubleOrNull ?: 1.0,
            own = p("own")?.doubleOrNull ?: 1.0,
            time = p("time")?.doubleOrNull ?: 0.0,
            playerVolume = p("playerVolume")?.doubleOrNull,
            // The device list is read at most every few seconds in the page: what it did not
            // answer this time is what it answered last time.
            active = p("active")?.booleanOrNull ?: lastMedia?.active,
        )
    }

    /**
     * Every [WATCH_TICK_MS]: the page answers (else it is frozen and reloaded), the song it
     * says it plays is heard (its media element moves), the sound is on, ads are got past,
     * and a song stuck between two others moves on.
     */
    private suspend fun watchStalls() {
        var key: String? = null
        var since = 0L
        var attempts = 0
        var adAttempts = 0
        var adReloaded = false
        var frozenReloadAt = 0L
        /** Consecutive ticks where the page answered nothing (see FROZEN_MISSES). */
        var frozenMisses = 0
        var tick = 0
        var lastMediaTime = -1.0
        var silentSince = 0L
        var waitingForNetwork = false
        while (true) {
            // A song plays (or an ad is on): the watcher looks every [WATCH_TICK_MS] to hear a
            // stall in seconds. Nothing plays: there is less to see, so it looks less often.
            val quiet = !_adPlaying.value && _state.value.let { it == null || it.isPaused }
            // An ad starting wakes it at once: its recovery starts AD_PLAYING_SKIP_MS after it.
            if (withTimeoutOrNull(if (quiet) WATCH_IDLE_TICK_MS else WATCH_TICK_MS) { wake.receive() } != null)
                delay(AD_PLAYING_SKIP_MS + 50)
            tick++
            if (!bridge.ready.value || ownReload) { key = null; attempts = 0; silentSince = 0L; continue }
            if (!online()) {
                if (!waitingForNetwork) bridge.logger?.invoke("offline: stall recovery waits for the network")
                waitingForNetwork = true
                key = null; attempts = 0; silentSince = 0L
                continue
            }
            if (waitingForNetwork) { waitingForNetwork = false; bridge.logger?.invoke("network back: stall recovery resumes") }
            val now = System.currentTimeMillis()
            // The page answers nothing, not even a ping: frozen (a stuck renderer). Reloaded
            // at once, the song going on where it was.
            val read = runCatching { readMedia() }
            if (read.isFailure && bridge.ready.value && !bridge.ping() && bridge.ready.value) {
                // One silent page is not a dead one: a busy main thread (a long render, a reload
                // still settling, an ad break starting) also holds the bridge for seconds, and
                // reloading on the first miss turned a short hiccup into a full page reload — the
                // one the user actually sees. The reload needs two misses in a row
                // ([FROZEN_MISSES] ticks of WATCH_TICK_MS, so about four seconds of real silence).
                frozenMisses++
                if (frozenMisses < FROZEN_MISSES) { key = null; silentSince = 0L; continue }
                if (now - frozenReloadAt > FROZEN_RELOAD_GAP_MS) {
                    frozenReloadAt = now
                    frozenMisses = 0
                    bridge.logger?.invoke("page not answering ($FROZEN_MISSES misses in a row): reloading the player")
                    val playing = lastTrackState?.takeIf { !pausedByUser && !it.isPaused }
                    reloadAndPlayOn(playing?.copy(positionMs = playing.positionAt(now), timestampMs = now), next = false)
                    key = null; attempts = 0; silentSince = 0L
                }
                continue
            }
            // The page answered: it is alive, whatever happened before.
            frozenMisses = 0
            val media = read.getOrNull()
            lastMedia = media
            val s = _state.value
            preloadNextSong(s, media)
            tickCrossfade(s, media, now)
            if (tick % VOLUME_CHECK_TICKS == 0) guardVolume(s, media)
            if (_adPlaying.value) {
                val ad = adState
                // The break just started: the page's own ad managers are switched off, so the
                // *next* break never starts (SpotX's adsEnabled patch, done at runtime instead of
                // by patching the bundle). The bridge repeats it on every state push.
                if (!adsOffSent) {
                    adsOffSent = true
                    runCatching { bridge.call("ads.off") }
                }
                // The mute ran once when the break started, but the player's media element lives in
                // the player's own objects (not the DOM) and can appear late: sending it again here,
                // bounded, is what keeps an ad from being heard.
                if (adMuteTries < AD_MUTE_TRIES) {
                    adMuteTries++
                    runCatching { bridge.call("media.mute", buildJsonObject { put("on", true) }) }
                }
                // The mute outlived the break: the page went quiet without ever publishing the
                // next song, so nothing clears the ad flag and the phone stays silent for good
                // (reported as "no sound until the app is restarted"). The sound comes back
                // anyway, and the state is read again.
                if (adMutedAt > 0 && now - adMutedAt > AD_MUTE_MAX_MS) {
                    bridge.logger?.invoke("ad mute older than ${AD_MUTE_MAX_MS / 1000}s: unmuted")
                    runCatching { unmuteAfterAd() }
                    if (adStateAt > 0 && now - adStateAt > AD_MUTE_MAX_MS) {
                        adState = null; adStateAt = 0L
                        adAttempts = 0; adReloaded = false; adsOffSent = false
                        _adPlaying.value = false
                        runCatching { refresh() }
                    }
                    key = null; silentSince = 0L
                    continue
                }
                // A refused ad sits at 0:00, buffering; a real one plays to its end.
                val refused = ad != null && (ad.isBuffering || ad.speed == 0.0) && now - adSince > AD_STUCK_MS
                // A video ad reports neither a position nor a speed the way an audio one does: it
                // is taken as playing from the start, so it is got past instead of waited on.
                val videoAd = ad?.item?.mediaType == "video"
                val playing = ad != null && !refused && (videoAd || now - adSince > AD_PLAYING_SKIP_MS) && !adReloaded
                val stuck = refused || playing || now - adSince > AD_BREAK_MAX_MS
                // The page's in-stream ad manager can end its own break. Asked once per break,
                // before the skip/reload recovery, because that recovery is what the user sees: a
                // "Test ad" used to cost a full reload of the page. The recovery below still runs
                // if the ad is still there on the next turn.
                if (stuck && !adSkipped) {
                    adSkipped = true
                    val lever = runCatching { bridge.call("ads.skip") }.getOrNull()
                    bridge.logger?.invoke("ad ${ad?.item?.name}: asked the page to end the break ($lever)")
                    adSince = System.currentTimeMillis()
                    key = null; silentSince = 0L
                    continue
                }
                if (stuck) {
                    adAttempts++
                    val last = lastTrackState
                    // Playing on can land on the same refused ad again: after a few tries the
                    // page itself is reloaded, which drops the ad break. An ad that "plays" (it
                    // has no file: silence on the page's clock) refuses every skip ("Skipping is
                    // not allowed with current content"): straight to the reload.
                    if (playing) adAttempts = AD_SKIPS_BEFORE_RELOAD + 1
                    if (adRecovery(adAttempts) == "next") {
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: playing the next song (attempt $adAttempts)")
                        last?.let { runCatching { playOn(it) } }
                    } else {
                        adAttempts = 0
                        adReloaded = true
                        bridge.logger?.invoke("ad stuck on ${ad?.item?.name}: reloading the player")
                        if (reloadHost != null) reloadAndPlayOn(last) else last?.let { runCatching { playOn(it) } }
                    }
                    adSince = System.currentTimeMillis()
                }
                key = null; silentSince = 0L
                continue
            }
            // A song really playing again: the ad break is over.
            if (s != null && !s.isPaused && !s.isBuffering) { adAttempts = 0; adReloaded = false }
            // Silent: the state says this phone plays, yet its media element stands still.
            val fading = fadeBackTo != null || fadeBack?.isActive == true
            val playingHere = s != null && !s.isPaused && !s.isBuffering && media != null && media.active != false && !fading
            val moved = media != null && !media.paused && kotlin.math.abs(media.time - lastMediaTime) > 0.25
            lastMediaTime = media?.time ?: -1.0
            silentSince = if (playingHere && !moved) (if (silentSince == 0L) now else silentSince) else 0L
            val silent = silentSince != 0L && now - silentSince >= SILENT_MS
            val reason = s?.let { stallOf(it, now, pausedByUser) } ?: (if (silent) SILENT else null)
            if (s == null || reason == null) { key = null; attempts = 0; continue }
            val k = "${s.item?.uri}|$reason"
            if (k != key) { key = k; since = now; attempts = 0; if (reason != SILENT) continue }
            val wait = when (reason) { "past the end" -> END_STUCK_MS; SILENT -> 0L; else -> STUCK_MS }
            if (now - since < wait) continue
            since = now
            attempts++
            silentSince = 0L
            bridge.logger?.invoke("player stalled ($reason) on ${s.item?.name}, attempt $attempts")
            // A song stuck at its end moves on; one stuck in its middle goes on where it was.
            val onward = reason == "past the end" || reason == "paused at the end"
            // A frozen page answers nothing: straight to the reload.
            if (attempts == 1 && !bridge.ping()) attempts = 2
            if (attempts == 1) runCatching { if (onward) playOn(s) else seek(s.positionAt(now)) }
            else {
                attempts = 0
                reloadAndPlayOn(if (onward) s else s.copy(positionMs = s.positionAt(now), timestampMs = now), next = onward)
            }
        }
    }

    /**
     * The page left silent while a song plays: a fade or an ad mute whose call was lost (page
     * busy, reloaded). The sound comes back to what the user wants.
     */
    private suspend fun guardVolume(s: PlayerState?, media: Media?) {
        if (s == null || s.isPaused || s.isBuffering || _adPlaying.value || fadeBackTo != null || fadeBack?.isActive == true) return
        // A crossfade owns the element level until it ends: the guard must not undo the ramp.
        // Past its deadline, though, it is exactly the lost unmute it exists to catch.
        if (crossfadeArmed) {
            if (System.currentTimeMillis() >= crossfadeUntil) {
                bridge.logger?.invoke("crossfade overdue: volume restored")
                endCrossfade(restore = true)
            }
            return
        }
        // The unmute after an ad was lost (page busy): the song would play silent.
        if (adMuted) unmuteAfterAd()
        else if (media?.muted == true) {
            bridge.logger?.invoke("media element muted during a song: unmuted")
            runCatching { bridge.call("media.mute", buildJsonObject { put("on", false) }) }
        }
        val wanted = wantedVolume ?: 1.0
        if (wanted <= 0.0) return
        // The page's own volume is read with the media element (one call, no second round trip).
        val v = media?.playerVolume ?: runCatching { volume() }.getOrNull() ?: return
        if (v <= 0.0) {
            bridge.logger?.invoke("page silent during a song: volume back to $wanted")
            runCatching { setVolume(wanted) }
        }
        // A fade that never came back: the element back to the page player's own volume.
        if (media != null && media.active != false && media.own > 0.001 && media.level < media.own * 0.5) {
            bridge.logger?.invoke("page player silent during a song: level ${media.level} back to ${media.own}")
            runCatching { restoreLevel() }
        }
    }

    /** Spotify refused the last play: once, the list is played from its start instead. */
    private fun onRefused() {
        val last = lastPlay ?: return
        if (System.currentTimeMillis() - last.third > REFUSED_WINDOW_MS) return
        if (refusedRetried || last.second == null) { _refused.tryEmit(Unit); return }
        refusedRetried = true
        bridge.logger?.invoke("play refused by Spotify: playing the list without picking the song")
        scope.launch { runCatching { bridge.call("player.play", buildJsonObject { put("contextUri", last.first) }, timeoutMs = PLAY_TIMEOUT_MS) } }
    }

    /** Plays the song after [s] (or [s] again when it is the last one) in its context. */
    /**
     * The song the user's skips point at, kept through what the page cannot take a skip in:
     * an ad break (skips refused, "The operation is not allowed"), a reload ("local player is
     * disabled", "not ready"). Skips then move this intent instead of reaching the page, and
     * whatever recovers playback (the ad recovery, a reload, the end of the ad) plays it.
     * [from]: the song shown when it was set (the page has not moved while it reports it).
     */
    private data class Intent(val context: Uri?, val item: NowPlaying, val from: Uri?, val at: Long, val adSeen: Boolean = false)
    @Volatile private var intent: Intent? = null
    /** The song and time the intent was last played, so that a slow page is not asked twice. */
    @Volatile private var intentPlayedAt = 0L
    @Volatile private var intentPlayedUri: Uri? = null
    /** The intent is being played after an ad or a reload: skips meanwhile only move it. */
    @Volatile private var playingIntent = false

    /** The page cannot take a skip now. */
    private val skipsHeld: Boolean get() = _adPlaying.value || ownReload || playingIntent || !bridge.ready.value

    private fun liveIntent(): Intent? = intent?.takeIf { System.currentTimeMillis() - it.at < INTENT_MAX_MS }

    /** The songs after the last real one, as the page listed them (queued first). */
    private fun upcoming(): List<NowPlaying> =
        _queue.value?.let { it.queued + it.nextUp }.orEmpty().filter { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }

    /**
     * A skip the page cannot take: the intent moves [by] songs from where it is (or from the
     * song shown: the first "next" is the song after it, the first "previous" that song again)
     * and is shown at once.
     */
    private fun stepIntent(by: Int) {
        val base = lastWithSong() ?: return
        val current = base.item ?: return
        val seq = listOfNotNull(base.previous) + current + upcoming().ifEmpty { listOfNotNull(base.next) }
        val baseIdx = if (base.previous != null) 1 else 0
        val at = liveIntent()?.let { i -> seq.indexOfFirst { it.uri == i.item.uri }.takeIf { it >= 0 } }
        // No intent yet: "next" lands on the song after the one shown, "previous" on that one.
        val idx = ((at ?: (baseIdx + if (by > 0) 0 else 1)) + by).coerceIn(0, seq.lastIndex)
        setIntent(base.contextUri, seq[idx], current.uri)
    }

    private fun setIntent(context: Uri?, item: NowPlaying, from: Uri?) {
        val now = System.currentTimeMillis()
        intent = Intent(context, item, from, now, adSeen = _adPlaying.value || liveIntent()?.adSeen == true)
        bridge.logger?.invoke("skip held (${if (_adPlaying.value) "ad" else "page reloading"}): ${item.name} goes on next")
        showIntent()
    }

    /** The screen shows the song the user is on, waiting, while the page is elsewhere. */
    private fun showIntent() {
        val i = liveIntent() ?: return
        val base = lastWithSong() ?: return
        val now = System.currentTimeMillis()
        _state.value = base.copy(item = i.item, positionMs = 0, timestampMs = now, isPaused = false, isBuffering = true)
    }

    /**
     * Plays the intent until the page is on it; skips made meanwhile move the intent and the
     * newest one is played. False when there is none.
     */
    private suspend fun playIntent(): Boolean {
        if (liveIntent() == null || playingIntent) return liveIntent() != null
        playingIntent = true
        try {
            repeat(INTENT_PLAYS) {
                val i = liveIntent() ?: return true
                val uri = i.item.uri
                if (lastTrackState?.item?.uri == uri) { intent = null; return true }
                val now = System.currentTimeMillis()
                // Asked for a moment ago (a slow page): not again, wait for it below.
                if (uri != intentPlayedUri || now - intentPlayedAt >= INTENT_REPLAY_MS) {
                    intentPlayedUri = uri
                    intentPlayedAt = now
                    bridge.logger?.invoke("playing the song the skips pointed at: ${i.item.name}")
                    val ctx = i.context
                    runCatching {
                        if (ctx != null) play(ctx, trackUri = uri, order = lastOrder?.takeIf { it.first == ctx }?.second)
                        else play(uri)
                    }.onFailure { bridge.logger?.invoke("intent not played: ${it.message}") }
                }
                // The page on it, or the user skipped again (then that song is played).
                withTimeoutOrNull(INTENT_REPLAY_MS) {
                    while (lastTrackState?.item?.uri != uri && liveIntent()?.item?.uri == uri) delay(100)
                }
                if (lastTrackState?.item?.uri == uri && liveIntent()?.item?.uri == uri) { intent = null; return true }
            }
            return true
        } finally {
            playingIntent = false
        }
    }

    private suspend fun playOn(s: PlayerState) {
        // The user skipped meanwhile: their song, not the one after the last state seen.
        if (playIntent()) return
        val ctx = s.contextUri
        val target = onwardTarget(s, _queue.value?.nextUp.orEmpty())
        when {
            ctx != null && target != null -> play(ctx, trackUri = target, order = lastOrder?.takeIf { it.first == ctx }?.second)
            target != null -> next()
            // Nothing after it: a song is started again (the page's own end-of-context behaviour);
            // an episode is left finished rather than replayed from the start.
            ctx != null && s.item?.kind == ItemKind.TRACK -> play(ctx, trackUri = s.item?.uri)
        }
    }

    private fun onState(s: PlayerState?) {
        stateSeq++
        // Another song than the one a crossfade is fading out: the ramp up, at once (the
        // watcher's next tick would leave the new song's first second at the faded level).
        if (crossfadeArmed) {
            val item = s?.item
            when {
                // An ad takes the sound over (and is muted on its own): the fade is over.
                item?.kind == ItemKind.AD -> disarmCrossfade()
                item != null && item.uri != crossfadeUri -> {
                    disarmCrossfade()
                    scope.launch { fadeInCrossfade() }
                }
            }
        }
        if (s?.item?.kind == ItemKind.AD) {
            // Keep showing the last real track, buffering, while the ad plays.
            if (!_adPlaying.value) {
                adSince = System.currentTimeMillis()
                adMuteTries = 0
            }
            adState = s
            adStateAt = System.currentTimeMillis()
            if (!_adPlaying.value) wake.trySend(Unit)
            _adPlaying.value = true
            intent = intent?.copy(adSeen = true)
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            showIntent()
            return
        }
        _adPlaying.value = false
        adsOffSent = false
        adSkipped = false
        adMuteTries = 0
        // A real song is playing: the fallback after a refusal (play the list without picking the
        // song) is armed again for the next command, not held once per five seconds for all of them.
        refusedRetried = false
        if (s?.item != null) lastSong = s
        else if (lastTrackState?.item != null) lastSongUntil = System.currentTimeMillis()
        lastTrackState = s
        _state.value = s
        val i = liveIntent() ?: run { intent = null; return }
        val uri = s?.item?.uri
        when {
            uri == i.item.uri -> intent = null
            // The page has not moved yet (or reports nothing): during an ad or a reload the
            // screen stays on the intent. On a plain skip it shows what the page plays: a
            // skip the page lost must not leave the screen on a song that is not heard.
            uri == null || uri == i.from -> if (i.adSeen || skipsHeld) showIntent()
            // Somewhere else after an ad or a reload: the user's song is asked for again.
            i.adSeen || skipsHeld -> { showIntent(); scope.launch { runCatching { if (!skipsHeld) playIntent() } } }
            // Somewhere else on a plain skip: the page knows its queue best.
            else -> intent = null
        }
    }


    // ── Fades: a song cut off at full volume pops in the phone's speaker. Each skip or play
    // lowers the sound over ~0.1 s first and brings it back once the next song plays. ──

    /** The volume a fade comes back to, while one is under way. */
    @Volatile private var fadeBackTo: Double? = null
    private var fadeBack: kotlinx.coroutines.Job? = null

    /** The element back to the page player's own volume. */
    private suspend fun restoreLevel() {
        bridge.call("media.volume", buildJsonObject { put("restore", true) }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
    }

    /**
     * One call for a whole fade: the page applies [levels] one after the other while the host
     * does not wait for them (a call per step delayed the first sound by one round trip each).
     * [restore] ends on the page player's own volume, as [restoreLevel] does.
     */
    private suspend fun fade(levels: List<Double>, restore: Boolean = false, ms: Long = FADE_STEP_MS) {
        bridge.call("media.fade", buildJsonObject {
            putJsonArray("steps") { levels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.coerceIn(0.0, 1.0))) } }
            put("ms", ms)
            put("restore", restore)
        }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
    }

    private suspend fun withFade(block: suspend () -> Unit) {
        // A crossfade under way owns the level: ended here, so its ramp (or its up-ramp, when the
        // song changes under it) cannot fight the fade below.
        endCrossfade(restore = true)
        val s = _state.value
        // Nothing heard (paused, an ad muted): nothing to fade.
        if (s == null || s.isPaused || _adPlaying.value || adMuted) { block(); return }
        // The level as the watcher last read it: no extra round trip before the skip.
        val seen = lastMedia
        if (seen?.active == false) { block(); return }
        val target = fadeBackTo ?: seen?.level?.takeIf { it > 0.01 } ?: 1.0
        val from = s.item?.uri
        fadeBackTo = target
        fadeBack?.cancel()
        // The ramp runs in the page, so it goes down while the command is already on its way.
        runCatching { fade(FADE_STEPS.map { it * target }) }
        try {
            block()
        } finally {
            fadeBack = scope.launch {
                delay(FADE_STEP_MS * 3)
                // The next song is there (or give up waiting): the sound comes back. Buffering
                // is not waited for: nothing is heard meanwhile, and a page that keeps saying
                // "buffering" while it plays would leave the song's start muted.
                withTimeoutOrNull(FADE_BACK_MAX_MS) {
                    _state.first { it != null && !it.isPaused && (it.item?.uri != from || it.positionMs < 1_500) }
                }
                val back = fadeBackTo ?: target
                // Ends on the page player's own volume: a volume change during the fade is kept.
                runCatching { fade(FADE_STEPS.reversed().drop(1).map { it * back }, restore = true) }
                    .onFailure { runCatching { restoreLevel() } }
                fadeBackTo = null
            }
        }
    }

    private suspend fun muteForAd() = runCatching {
        adMuted = true
        adMutedAt = System.currentTimeMillis()
        var muted = bridge.call("media.mute", buildJsonObject { put("on", true) })
        // No media element reached (the page is reloading, or the ad is taking the stream over):
        // once more before the count is trusted.
        if (muted.toString().trim('"').toIntOrNull() == 0) {
            delay(200)
            muted = bridge.call("media.mute", buildJsonObject { put("on", true) })
        }
        bridge.logger?.invoke("ad muted ($muted media elements)")
    }

    private suspend fun unmuteAfterAd() = runCatching {
        if (!adMuted) return@runCatching
        bridge.call("media.mute", buildJsonObject { put("on", false) })
        adMuted = false
        adMutedAt = 0L
        // The song after an ad is preloaded again: the key (current|next) is usually the same as
        // before the break, so without this the preload of that very song was skipped and the next
        // skip after an ad was slow.
        preloadedFor = null
    }

    /**
     * Crossfade length in seconds (0 = off). Only a fade is possible: the page plays one media
     * element, so two songs never overlap. Set by the app from the user's setting, at start and
     * whenever it changes.
     */
    @Volatile var crossfadeSeconds: Int = 0
        set(value) {
            val v = value.coerceIn(0, CROSSFADE_MAX_SECONDS)
            if (field == v) return
            field = v
            // Turned off while a ramp runs: the sound comes back at once rather than at the end
            // of a fade nobody wants any more.
            if (v == 0) {
                val was = crossfadeArmed
                disarmCrossfade()
                if (was) scope.launch { runCatching { restoreLevel() } }
            }
        }

    /** True while a crossfade is fading a song out, or the next one in. */
    fun isCrossfading(): Boolean = crossfadeArmed

    /**
     * The page's element level, ramped down over the last seconds of a song: one media element
     * means the next song cannot start before this one ends, so this is a fade, not an overlap.
     */
    @Volatile private var crossfadeArmed = false
    /** The song the ramp belongs to: the song after it (another uri) ends the fade-out. */
    @Volatile private var crossfadeUri: String? = null
    /** When the ramp will have reached its floor; past that the next song must have come. */
    @Volatile private var crossfadeUntil = 0L
    /** The fail-safe that puts the level back if the page never moved on. */
    private var crossfadeJob: kotlinx.coroutines.Job? = null
    /** The last song whose skipped crossfade was logged (see [tickCrossfade]). */
    @Volatile private var crossfadeNoted: String? = null

    private fun disarmCrossfade() {
        crossfadeArmed = false
        crossfadeUri = null
        crossfadeUntil = 0L
        crossfadeJob?.cancel()
        crossfadeJob = null
    }

    /** Ends a crossfade; [restore] puts the element back on the page player's own volume. */
    private suspend fun endCrossfade(restore: Boolean) {
        if (!crossfadeArmed) return
        disarmCrossfade()
        if (restore) runCatching { restoreLevel() }
    }

    /**
     * Called each watch tick: arms the fade-out when the song is inside its last
     * [crossfadeSeconds]. The ramp ends with the song (the window is what is left of it), so a
     * late tick only shortens the fade, it never runs past the next song.
     */
    private suspend fun tickCrossfade(s: PlayerState?, media: Media?, now: Long) {
        val secs = crossfadeSeconds
        // Off, or nothing to fade out of: an armed ramp is lifted (never left down).
        if (secs <= 0 || s == null || s.isPaused || _adPlaying.value) { endCrossfade(restore = true); return }
        val item = s.item ?: return
        if (item.kind == ItemKind.AD || item.durationMs <= 0) return
        val window = secs * 1000L
        val remaining = item.durationMs - s.positionAt(now)
        // Why a song inside its window gets no fade, once per song: the diagnostic log otherwise
        // shows nothing at all when a crossfade does not happen.
        fun skipped(why: String) {
            if (remaining in 1..window && crossfadeNoted != item.uri) {
                crossfadeNoted = item.uri
                bridge.logger?.invoke("crossfade skipped on ${item.name}: $why")
            }
        }
        // Another device is playing: this phone's element is not where the sound is, and a ramp
        // set on it would greet the user at 2% after a transfer back.
        if (media?.active == false) { endCrossfade(restore = true); skipped("another device is active"); return }
        if (crossfadeArmed) {
            // The next song is handled where it is reported (see [onState]). Here: the song was
            // seeked back out of the window, or the setting was shortened under the ramp.
            if (item.uri != crossfadeUri || remaining > window) endCrossfade(restore = true)
            return
        }
        if (remaining <= 0 || remaining > window) return
        // Nothing after it: a fade-out into silence is not a crossfade.
        if (s.next == null && upcoming().isEmpty()) { skipped("nothing after it in the queue"); return }
        val own = (media?.own ?: 1.0).coerceIn(0.0, 1.0)
        // Silent already (volume at 0): there is nothing to fade.
        if (own <= 0.001) { skipped("page volume at 0"); return }
        armCrossfade(item.uri, remaining, own)
    }

    private suspend fun armCrossfade(uri: String, remainingMs: Long, own: Double) {
        val steps = (remainingMs / CROSSFADE_STEP_MS).toInt().coerceIn(2, CROSSFADE_MAX_STEPS)
        val per = (remainingMs / steps).coerceAtLeast(1L)
        // A linear ramp in amplitude, ending on CROSSFADE_FLOOR of the page's own level: the last
        // step lands on the song's end, so the fade is over exactly when the song is.
        val levels = (1..steps).map { i ->
            (own * (1.0 - (1.0 - CROSSFADE_FLOOR) * i / steps)).coerceAtLeast(CROSSFADE_SILENT)
        }
        crossfadeArmed = true
        crossfadeUri = uri
        crossfadeUntil = System.currentTimeMillis() + remainingMs + CROSSFADE_FAILSAFE_MS
        crossfadeJob?.cancel()
        // The hard floor: whatever the page does (a stall, a lost state, a reload, a song that
        // repeats), the sound is back a few seconds after the ramp was due. A volume left down
        // is the one failure the user cannot work around.
        crossfadeJob = scope.launch {
            delay(remainingMs + CROSSFADE_FAILSAFE_MS)
            if (crossfadeArmed) {
                bridge.logger?.invoke("crossfade left the sound down ${(remainingMs + CROSSFADE_FAILSAFE_MS) / 1000}s: volume restored")
                endCrossfade(restore = true)
            }
        }
        bridge.logger?.invoke("crossfade: ${remainingMs / 1000.0}s out on ${_state.value?.item?.name}")
        // One call: the page runs the whole ramp itself (see [fade]).
        runCatching { fade(levels, ms = per) }
            .onFailure { endCrossfade(restore = true) }
    }

    /** The next song is on: a short ramp back to the user's volume. */
    private suspend fun fadeInCrossfade() {
        // Read the element as it is now: the page may have reset its volume with the new track,
        // and dipping it to the ramp's first step would be heard for nothing.
        val m = runCatching { readMedia() }.getOrNull()
        if (m == null || m.level >= m.own * 0.9) {
            runCatching { restoreLevel() }
            return
        }
        val own = m.own.coerceAtLeast(CROSSFADE_SILENT)
        // Ends on the page player's own volume, so a volume change during the fade is kept.
        runCatching { fade(CROSSFADE_IN.map { it * own }, restore = true, ms = CROSSFADE_IN_STEP_MS) }
            .onFailure { runCatching { restoreLevel() } }
    }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
        // A new song picked (or the intent itself played): any earlier skip is over.
        if (trackUri == null || trackUri != liveIntent()?.item?.uri) intent = null
        lastOrder = order?.let { contextUri to it }
        lastPlay = Triple(contextUri, trackUri, System.currentTimeMillis())
        refusedRetried = false
        pausedByUser = false
        withFade {
            command("player.play", timeoutMs = PLAY_TIMEOUT_MS, args = buildJsonObject {
                put("contextUri", contextUri)
                order?.let { uris -> putJsonArray("order") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
                trackUri?.let { put("trackUri", it) }
                index?.let { put("index", it) }
                positionMs?.let { put("positionMs", it) }
            })
        }
    }

    /**
     * A playback command, sent again when the page was not able to take it yet: a page just
     * (re)loaded says "ready" a moment before Spotify Connect knows its device ("No such
     * device"), and a reload under way refuses everything ("not ready").
     */
    private suspend fun command(method: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): kotlinx.serialization.json.JsonElement {
        var attempt = 0
        while (true) {
            try {
                return bridge.call(method, args, timeoutMs)
            } catch (e: app.myspoti.spotify.bridge.BridgeException) {
                val transient = e.message.orEmpty().let { m -> TRANSIENT.any { it in m } }
                if (!transient || ++attempt > COMMAND_RETRIES) throw e
                bridge.logger?.invoke("$method not taken (${e.message}): sent again")
                delay(COMMAND_RETRY_MS * attempt)
                if (!bridge.ready.value) withTimeoutOrNull(READY_WAIT_MS) { bridge.ready.first { it } }
            }
        }
    }

    override suspend fun pause() { pausedByUser = true; endCrossfade(restore = true); command("player.pause") }
    override suspend fun resume() { pausedByUser = false; command("player.resume") }
    override suspend fun seek(positionMs: Long) { endCrossfade(restore = true); command("player.seek", buildJsonObject { put("positionMs", positionMs) }) }
    override suspend fun next() {
        pausedByUser = false
        if (skipsHeld) { stepIntent(1); return }
        // Remembered: should the page go into an ad break instead, this song comes after it.
        val base = lastWithSong()
        val after = base?.next?.takeIf { it.kind == ItemKind.TRACK || it.kind == ItemKind.EPISODE }
        if (after != null && base.item?.kind != ItemKind.AD) intent = Intent(base.contextUri, after, base.item?.uri, System.currentTimeMillis())
        runCatching { withFade { command("player.next") } }.onFailure { e ->
            if (!heldError(e, base?.restrictions?.canSkipNext != false)) throw e
            stepIntent(1)
        }
    }

    /**
     * The page refused a skip because of an ad or a reload under way, not because the song
     * forbids it: "not allowed" counts only when the song's own restrictions allow the skip
     * (the ad itself is often reported seconds later).
     */
    private fun heldError(e: Throwable, allowed: Boolean): Boolean {
        val m = e.message.orEmpty()
        return skipsHeld || RELOADING.any { it in m } || (allowed && "not allowed" in m)
    }

    override suspend fun previous() {
        pausedByUser = false
        if (skipsHeld) { stepIntent(-1); return }
        intent = null
        withFade {
            val s = _state.value
            runCatching { command("player.previous") }.onFailure { e ->
                // Refused because of an ad or a reload: kept for after it, never a seek (that
                // restarted the ad).
                if (heldError(e, s?.restrictions?.canSkipPrevious == true)) { stepIntent(-1); return@onFailure }
                // Refused by the page (a restriction on the account or the context): the song
                // starts over, or the one before plays, as the button would have done.
                val pos = s?.positionAt(System.currentTimeMillis()) ?: 0L
                val before = s?.previous?.takeIf { it.kind == ItemKind.TRACK }?.uri
                val ctx = s?.contextUri
                bridge.logger?.invoke("previous refused (${e.message}): ${if (pos > 3_000 || before == null || ctx == null) "song restarted" else "song before played"}")
                if (pos > 3_000 || before == null || ctx == null) seek(0) else play(ctx, trackUri = before, order = lastOrder?.takeIf { it.first == ctx }?.second)
            }
        }
    }
    override suspend fun setShuffle(on: Boolean) { bridge.call("player.setShuffle", buildJsonObject { put("on", on) }) }

    override suspend fun setRepeat(mode: Repeat) {
        bridge.call("player.setRepeat", buildJsonObject { put("mode", mode.ordinal) })
    }

    override suspend fun addToQueue(uris: List<Uri>) {
        bridge.call("queue.add", buildJsonObject { putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
    }

    override suspend fun removeFromQueue(items: List<NowPlaying>) {
        bridge.call("queue.remove", buildJsonObject {
            putJsonArray("items") {
                items.forEach { item -> addJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } } }
            }
        })
    }

    override suspend fun clearQueue() { bridge.call("queue.clear") }

    private fun ref(item: NowPlaying) = buildJsonObject { put("uri", item.uri); item.uid?.let { put("uid", it) } }

    /** The last state with a song: a reloading page reports none for a moment. */
    private fun lastWithSong(): PlayerState? = lastTrackState?.takeIf { it.item != null } ?: lastSong

    override suspend fun skipTo(item: NowPlaying) {
        pausedByUser = false
        val base = lastWithSong()
        if (skipsHeld) { setIntent(base?.contextUri, item, base?.item?.uri); return }
        if (base?.item?.kind != ItemKind.AD) intent = Intent(base?.contextUri, item, base?.item?.uri, System.currentTimeMillis())
        runCatching { withFade { command("queue.skipTo", ref(item)) } }.onFailure { e ->
            if (!heldError(e, base?.restrictions?.canSkipNext != false)) throw e
            setIntent(base?.contextUri, item, base?.item?.uri)
        }
    }

    override suspend fun playNext(uris: List<Uri>) {
        val first = _queue.value?.queued?.firstOrNull()
        if (first == null) { addToQueue(uris); return }
        bridge.call("queue.insert", buildJsonObject {
            putJsonArray("uris") { uris.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("before", ref(first))
        })
    }

    override suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean) {
        bridge.call("queue.reorder", buildJsonObject {
            putJsonArray("items") { add(ref(item)) }
            put(if (after) "after" else "before", ref(target))
        })
    }

    override suspend fun setSpeed(speed: Double) {
        bridge.call("player.setSpeed", buildJsonObject { put("speed", speed.coerceIn(0.5, 3.5)) })
    }

    override suspend fun volume(): Double = bridge.call("volume.get").jsonPrimitive.double

    override suspend fun setVolume(level: Double) {
        wantedVolume = level.coerceIn(0.0, 1.0)
        if (fadeBackTo != null) fadeBackTo = level.coerceIn(0.0, 1.0)
        bridge.call("volume.set", buildJsonObject { put("level", level.coerceIn(0.0, 1.0)) })
    }

    /** Sets the streaming quality now (when the page is up) and on every later page load. */
    suspend fun setAudioQuality(level: String) {
        audioQuality = level
        if (bridge.ready.value) applyAudioQuality()
    }

    /** What the page plays now: bitrate (bit/s, 0 = unknown) and codec ("audio/mp4; codecs=…"). */
    suspend fun audioInfo(): Pair<Int, String> = runCatching {
        val o = bridge.call("player.audioInfo") as? JsonObject ?: return@runCatching 0 to ""
        (o["bitrate"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0) to (o["mime"]?.jsonPrimitive?.content.orEmpty())
    }.getOrDefault(0 to "")

    /**
     * Volume boost, 1.0 (off) to [BOOST_MAX]: the page's audio through Web Audio, a gain and a
     * limiter, on this phone only ("media.boost" in the bridge). Set again on every new page.
     */
    @Volatile var boost: Double = 1.0
        private set

    private val _boostSilent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The boost turned itself off: no sound came through Web Audio on this page. */
    val boostSilent: SharedFlow<Unit> = _boostSilent

    suspend fun setBoost(level: Double) {
        boost = level.coerceIn(1.0, BOOST_MAX)
        if (bridge.ready.value) applyBoost()
    }

    private suspend fun applyBoost() {
        runCatching { bridge.call("media.boost", buildJsonObject { put("level", boost) }) }
            .onSuccess { bridge.logger?.invoke("boost $boost: $it") }
            .onFailure { bridge.logger?.invoke("boost $boost not set: ${it.message}") }
    }

    /**
     * The probe heard nothing while the page played (protected or cross-origin audio that
     * Chromium keeps from Web Audio): the boost goes off, and a new page plays on where this
     * one was, since only a reload takes the media element out of Web Audio again.
     */
    private fun onBoost(msg: JsonObject) {
        // Also at 100 %: a page that has built the graph stays routed through it, silent or not.
        if ((msg["sound"] as? JsonPrimitive)?.booleanOrNull != false) return
        bridge.logger?.invoke("boost off: no sound through Web Audio, the page reloads")
        boost = 1.0
        _boostSilent.tryEmit(Unit)
        scope.launch { reloadAndPlayOn(lastTrackState, next = false) }
    }

    private suspend fun applyAudioQuality() {
        runCatching { bridge.call("player.setAudioQuality", buildJsonObject { put("level", audioQuality) }) }
            .onFailure { bridge.logger?.invoke("audio quality $audioQuality not set: ${it.message}") }
    }

    /** Pulls the state once, e.g. right after the bridge is ready. */
    suspend fun refresh() {
        onState(playerState(bridge.call("player.state") as? JsonObject))
        _queue.value = queue(bridge.call("player.queue"))
    }

    companion object {
        /** A refused ad left current that long: the page will not move on by itself. */
        const val AD_STUCK_MS = 4_000L
        /**
         * An ad mute that old is lifted. The page never reports the end of a break, so a lost
         * unmute used to leave the phone silent until the app was restarted; longer than any ad
         * break (15-30 s), shorter than a song.
         */
        const val AD_MUTE_MAX_MS = 75_000L
        /** A reloaded page reports its restored state within that long. */
        const val RESTORE_WAIT_MS = 6_000L
        /** A page going away reports "nothing plays" that long before it is gone, at most. */
        const val UNLOAD_NULL_MS = 5_000L
        /** Errors of a page not able to take a command yet (see [command]). */
        val TRANSIENT = listOf("No such device", "not ready", "page reloaded", "local player is disabled")
        /** Refusals of a page being reloaded: a skip is then held (see [Intent]). */
        val RELOADING = listOf("local player is disabled", "not ready", "page reloaded")
        /** A held skip is forgotten after that long (an ad break and a reload fit in it). */
        const val INTENT_MAX_MS = 90_000L
        /** The intent is asked for at most that often. */
        const val INTENT_REPLAY_MS = 4_000L
        /** Plays of the intent at most, after an ad or a reload. */
        const val INTENT_PLAYS = 3
        const val COMMAND_RETRIES = 3
        const val COMMAND_RETRY_MS = 700L
        const val READY_WAIT_MS = 30_000L
        /** After a reload, the song is asked for again that many times at most. */
        const val RESTORE_ATTEMPTS = 3
        /** A restore that has not started the song by then is tried again. */
        const val RESTORE_CHECK_MS = 5_000L
        const val RESTORE_GAP_MS = 1_500L
        /** The media element read each tick; a page that cannot answer that fast is pinged. */
        const val MEDIA_TIMEOUT_MS = 3_000L
        /** The highest volume boost: twice the level (+6 dB), see [setBoost]. */
        const val BOOST_MAX = 2.0
        const val LEVEL_TIMEOUT_MS = 3_000L
        /** A song the page says it plays here while its media element stands still that long. */
        const val SILENT_MS = 6_000L
        const val SILENT = "silent"
        /** The next song is preloaded once the current one has played that long. */
        const val PRELOAD_AFTER_MS = 2_000L
        /** Longer than any ad break: the page is stuck in it. */
        const val AD_BREAK_MAX_MS = 4 * 60_000L
        /** Volume steps of a fade (down; reversed to come back). */
        // Never 0: Chromium pauses a hidden page's media that (re)starts at volume 0 (a
        // "previous" that restarts the song stayed paused); the bridge keeps a floor.
        val FADE_STEPS = listOf(0.35, 0.08, 0.002)
        const val FADE_STEP_MS = 12L
        /** The sound always comes back after that long, whatever the page does. */
        const val FADE_BACK_MAX_MS = 2_500L
        /** Crossfade: the setting's range (seconds, 0 = off). */
        const val CROSSFADE_MAX_SECONDS = 12
        /**
         * Where a crossfade's ramp down ends, as a share of the page player's own level. Not 0:
         * the page keeps a floor on the element (see [FADE_STEPS]), and a media element set to 0
         * is paused by Chromium.
         */
        const val CROSSFADE_FLOOR = 0.02
        /** The floor the bridge itself keeps on a media element. */
        const val CROSSFADE_SILENT = 0.001
        /** One ramp step per that many ms; the page runs them itself (see [fade]). */
        private const val CROSSFADE_STEP_MS = 100L
        /** A 12 s ramp at 100 ms a step; more steps would not be heard. */
        private const val CROSSFADE_MAX_STEPS = 60
        /**
         * How long after the ramp was due the level is put back whatever the page did: a song
         * that stalled, a state lost in a reload, a repeated track. A volume left down is the
         * failure a user cannot work around.
         */
        const val CROSSFADE_FAILSAFE_MS = 6_000L
        /** The next song's ramp up: short, it is already playing. Ends on the own level. */
        val CROSSFADE_IN = listOf(0.2, 0.45, 0.7, 0.9)
        const val CROSSFADE_IN_STEP_MS = 100L
        /** Play commands: the page answers within a few seconds or not at all. */
        const val PLAY_TIMEOUT_MS = 10_000L
        /** A refusal that long after a play belongs to it. */
        const val REFUSED_WINDOW_MS = 5_000L
        /** Never two "frozen page" reloads closer than that. */
        const val FROZEN_RELOAD_GAP_MS = 60_000L
        /**
         * Consecutive silent ticks before the page is called dead and reloaded. One miss is not
         * enough: a busy main thread looks exactly like a frozen page for a couple of seconds, and
         * the reload that followed was itself the interruption the user complained about.
         */
        private const val FROZEN_MISSES = 2
        /** Extra ad-mute sends during a break (the media element may appear after the break starts). */
        private const val AD_MUTE_TRIES = 2
        /** The volume is checked every that many ticks. */
        const val VOLUME_CHECK_TICKS = 3
        /** An ad that plays (silent, muted) is got past after that long. */
        const val AD_PLAYING_SKIP_MS = 1_000L
        const val STUCK_MS = 8_000L
        /** A song over with nothing after it: where an ad was left out. Next song quickly. */
        const val END_STUCK_MS = 1_000L
        private const val WATCH_TICK_MS = 2_000L
        /** Nothing plays: the watcher looks less often (see [watchStalls]). */
        private const val WATCH_IDLE_TICK_MS = 4_000L
        /** Buffering longer than this on one song is a stall, not a slow network. */
        const val BUFFERING_STALL_MS = 25_000L
        /** Stuck ads skipped by playing on before the page is reloaded. */
        const val AD_SKIPS_BEFORE_RELOAD = 2

        /** What the [attempt]-th try on a stuck ad does: "next" (play on) or "reload". */
        fun adRecovery(attempt: Int): String = if (attempt <= AD_SKIPS_BEFORE_RELOAD) "next" else "reload"

        /**
         * Why [s] looks stalled at [now], or null. A song is stalled when its clock ran past
         * its end without a new song, when it paused itself at its end with a song after it,
         * or while it buffers too long.
         */
        /**
         * What a song or episode stuck at its end moves on to: the next playable item. B11: an
         * episode counts too; with tracks only, an episode stuck at its end found no target and
         * was played again from 0:00.
         */
        fun onwardTarget(s: PlayerState, nextUp: List<NowPlaying>): String? {
            val playable = { k: ItemKind -> k == ItemKind.TRACK || k == ItemKind.EPISODE }
            return s.next?.takeIf { playable(it.kind) }?.uri ?: nextUp.firstOrNull { playable(it.kind) }?.uri
        }

        fun stallOf(s: PlayerState, now: Long, userPaused: Boolean): String? {
            val item = s.item ?: return null
            // Episodes stall exactly like songs once their duration is known (a podcast playing
            // silent with a frozen progress bar is the same bug); only ads are left out, their
            // recovery is [adRecovery].
            if (item.kind == ItemKind.AD || item.durationMs <= 0) return null
            if (s.isPaused) {
                val atEnd = s.positionMs >= item.durationMs - 2_000
                return if (atEnd && s.next != null && !userPaused) "paused at the end" else null
            }
            if (s.isBuffering) return if (now - s.timestampMs > BUFFERING_STALL_MS - STUCK_MS) "buffering" else null
            val clock = s.positionMs + ((now - s.timestampMs) * s.speed).toLong()
            return if (clock > item.durationMs + 1_500) "past the end" else null
        }
    }
}
