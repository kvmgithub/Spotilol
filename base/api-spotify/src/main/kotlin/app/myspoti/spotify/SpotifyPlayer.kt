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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
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

    init {
        scope.launch {
            bridge.events.collect { (type, payload) ->
                when (type) {
                    "state" -> onState(playerState(payload))
                    "queue" -> queue(payload)?.let { q -> _queue.value = q.copy(nextUp = q.nextUp.filter { it.kind != ItemKind.AD }) }
                    "refused" -> onRefused()
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
                if (lost != null && !ownReload) scope.launch { runCatching { resumeAfterReload(lost) } }
                if (audioQuality != "auto") applyAudioQuality()
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
        var tick = 0
        var lastMediaTime = -1.0
        var silentSince = 0L
        var waitingForNetwork = false
        while (true) {
            // A song plays (or an ad is on): the watcher looks every [WATCH_TICK_MS] to hear a
            // stall in seconds. Nothing plays: there is less to see, so it looks less often.
            val quiet = !_adPlaying.value && _state.value.let { it == null || it.isPaused }
            delay(if (quiet) WATCH_IDLE_TICK_MS else WATCH_TICK_MS)
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
                if (now - frozenReloadAt > FROZEN_RELOAD_GAP_MS) {
                    frozenReloadAt = now
                    bridge.logger?.invoke("page not answering: reloading the player")
                    val playing = lastTrackState?.takeIf { !pausedByUser && !it.isPaused }
                    reloadAndPlayOn(playing?.copy(positionMs = playing.positionAt(now), timestampMs = now), next = false)
                    key = null; attempts = 0; silentSince = 0L
                }
                continue
            }
            val media = read.getOrNull()
            lastMedia = media
            val s = _state.value
            preloadNextSong(s, media)
            if (tick % VOLUME_CHECK_TICKS == 0) guardVolume(s, media)
            if (_adPlaying.value) {
                val ad = adState
                // A refused ad sits at 0:00, buffering; a real one plays to its end.
                val refused = ad != null && (ad.isBuffering || ad.speed == 0.0) && now - adSince > AD_STUCK_MS
                val playing = ad != null && !refused && now - adSince > AD_PLAYING_SKIP_MS && !adReloaded
                if (refused || playing || now - adSince > AD_BREAK_MAX_MS) {
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
    private suspend fun playOn(s: PlayerState) {
        val ctx = s.contextUri
        val target = s.next?.takeIf { it.kind == ItemKind.TRACK }?.uri
            ?: _queue.value?.nextUp?.firstOrNull { it.kind == ItemKind.TRACK }?.uri
        when {
            ctx != null && target != null -> play(ctx, trackUri = target, order = lastOrder?.takeIf { it.first == ctx }?.second)
            target != null -> next()
            ctx != null -> play(ctx, trackUri = s.item?.uri)
        }
    }

    private fun onState(s: PlayerState?) {
        stateSeq++
        if (s?.item?.kind == ItemKind.AD) {
            // Keep showing the last real track, buffering, while the ad plays.
            if (!_adPlaying.value) {
                adSince = System.currentTimeMillis()
            }
            adState = s
            _adPlaying.value = true
            _state.value = lastTrackState?.copy(isBuffering = true) ?: s.copy(item = null)
            return
        }
        _adPlaying.value = false
        if (s?.item != null) lastSong = s
        else if (lastTrackState?.item != null) lastSongUntil = System.currentTimeMillis()
        lastTrackState = s
        _state.value = s
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
    private suspend fun fade(levels: List<Double>, restore: Boolean = false) {
        bridge.call("media.fade", buildJsonObject {
            putJsonArray("steps") { levels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.coerceIn(0.0, 1.0))) } }
            put("ms", FADE_STEP_MS)
            put("restore", restore)
        }, timeoutMs = LEVEL_TIMEOUT_MS, quiet = true)
    }

    private suspend fun withFade(block: suspend () -> Unit) {
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
        val muted = bridge.call("media.mute", buildJsonObject { put("on", true) })
        bridge.logger?.invoke("ad muted ($muted media elements)")
    }

    private suspend fun unmuteAfterAd() = runCatching {
        if (!adMuted) return@runCatching
        bridge.call("media.mute", buildJsonObject { put("on", false) })
        adMuted = false
    }

    override suspend fun play(contextUri: Uri, trackUri: Uri?, index: Int?, positionMs: Long?, order: List<Uri>?) {
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

    override suspend fun pause() { pausedByUser = true; command("player.pause") }
    override suspend fun resume() { pausedByUser = false; command("player.resume") }
    override suspend fun seek(positionMs: Long) { command("player.seek", buildJsonObject { put("positionMs", positionMs) }) }
    override suspend fun next() { pausedByUser = false; withFade { command("player.next") } }
    override suspend fun previous() {
        pausedByUser = false
        withFade {
            val s = _state.value
            runCatching { command("player.previous") }.onFailure { e ->
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

    override suspend fun skipTo(item: NowPlaying) { pausedByUser = false; withFade { command("queue.skipTo", ref(item)) } }

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
        /** A reloaded page reports its restored state within that long. */
        const val RESTORE_WAIT_MS = 6_000L
        /** A page going away reports "nothing plays" that long before it is gone, at most. */
        const val UNLOAD_NULL_MS = 5_000L
        /** Errors of a page not able to take a command yet (see [command]). */
        val TRANSIENT = listOf("No such device", "not ready", "page reloaded")
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
        /** Play commands: the page answers within a few seconds or not at all. */
        const val PLAY_TIMEOUT_MS = 10_000L
        /** A refusal that long after a play belongs to it. */
        const val REFUSED_WINDOW_MS = 5_000L
        /** Never two "frozen page" reloads closer than that. */
        const val FROZEN_RELOAD_GAP_MS = 60_000L
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
        fun stallOf(s: PlayerState, now: Long, userPaused: Boolean): String? {
            val item = s.item ?: return null
            if (item.kind != ItemKind.TRACK || item.durationMs <= 0) return null
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
