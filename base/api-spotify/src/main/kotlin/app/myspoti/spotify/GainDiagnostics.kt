package app.myspoti.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Gain diagnostics: which `gain_db` values Spotify's track-playback answers carry, for which song
 * and file candidate, from which answer, and what the page player's level settings were at a
 * change of song, context, player, element or volume. Observation only: nothing here changes a
 * level, a request or an answer, asks Spotify anything or takes part in a playback decision. Off
 * unless the diagnostics switch is on ([setEnabled]); off, nothing is read, kept or scheduled.
 *
 * Runs and pages: a run starts when the switch goes on and at every possible change of account
 * ([newRun]). Within a run, each page is armed with its own tag ([armPage], sent to the bridge with
 * 'diag.gain'; tags are never reused while the app runs). Only the tag armed last is accepted: from
 * the page's messages, which carry it, and from the relay, whose requests carry the tag of the page
 * that sent them ([relayTicket], at the relay's entry). A page going away ([pageGone]) and a new run
 * leave nothing armed until a new page is: whatever the old page or the old run still sends is
 * dropped and counted ("late"). Every line written for a page or an answer names its page
 * ("page=", the tag), fixed when the line is taken, not when it is written: a line held back by the
 * rate limit keeps the page it came from, and the bridge's own numbers (pl, el), which start again
 * at 1 on a new page, are read with it.
 *
 * Provenance: every file candidate keeps up to [MAX_SEEN] observations, each with the answer's
 * request order ("ans", the order the relay took the requests in, not proof of what is newer on
 * Spotify's side), its page, the song's place in that answer (current or queue), the kind of its
 * context there, and the Connect device as the host's cache had it when the answer arrived
 * ("rxDev": a snapshot at receipt, not the device the request was made for, nor proof the cache was
 * fresh). A page line shows the latest by request order and the others that differ from it, and
 * whether a candidate's context kind is the page's ("here": same kind, e.g. both playlists, not
 * necessarily the same playlist). A song is the page's song only when the page says so: the state
 * an answer points at is that answer's.
 *
 * What is not seen proves nothing: an assignment pushed out ("evicted"), files past the bound of an
 * answer ("capped") or "gain=none" are limits of this observation, not Spotify sending no gain.
 *
 * Bounds: [MAX_ENTRIES] song/file assignments (the least recently seen goes), [MAX_PER_ANSWER] file
 * observations read from one answer, [LINES_PER_S] lines a second (one waiting line per kind, the
 * newest, with how many it stands for), [MAX_SHOWN] candidates written per line and at most
 * [MAX_GAIN_CHARS] characters of them on a page line. Lines carry ids of
 * the run (t1 for a song, f1 for a file; an id held by an assignment is never given away) instead
 * of Spotify's, fixed words for manifest groups (unknown ones are only counted), and no token,
 * cookie, URL, name, raw id or free error text. A file is a "candidate" (offered in an answer) or
 * "unknown" (no file id): nothing the app receives says which file the page plays.
 */
class GainDiagnostics(
    private val output: (String) -> Unit,
    /** Runs [run] once after [delayMs]; returns its cancel. */
    private val schedule: (delayMs: Long, run: () -> Unit) -> (() -> Unit),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Loudness the player's cache already holds for a track uri; never asked for here. Null: not in
     * the cache ("not-read"). NaN: the cache holds no usable value ("unusable"); its cause is not
     * known (the cache keeps a missing, an invalid and an empty answer alike), so it is not
     * "Spotify has no value".
     */
    private val loudness: (String) -> Double? = { null },
    private val app: () -> App = { App() },
    /** Whether the page is Spotify Connect's active device, as the player last read it (null: unknown). */
    private val device: () -> Boolean? = { null },
) {
    /** The app's own sound settings at the time of a line; null: not known. */
    data class App(val normalize: Boolean? = null, val boost: Double? = null, val crossfadeSeconds: Int? = null, val adMuted: Boolean? = null)

    /** A value as read, with whether it was there: [Status.OK] carries it, 0.0 included. */
    enum class Status(val label: String) { OK("ok"), ABSENT("absent"), NULL("null"), INVALID("invalid"), ERROR("error") }

    internal class Reading(val status: Status, val value: Double? = null) {
        val shown: String get() = if (status == Status.OK) num(value) else status.label

        companion object {
            fun of(o: JsonObject, key: String): Reading {
                if (!o.containsKey(key)) return Reading(Status.ABSENT)
                val e = o[key]
                if (e is JsonNull) return Reading(Status.NULL)
                val p = e as? JsonPrimitive ?: return Reading(Status.INVALID)
                if (p.isString) return Reading(Status.INVALID)
                val d = p.doubleOrNull?.takeIf { it.isFinite() } ?: return Reading(Status.INVALID)
                return Reading(Status.OK, d)
            }
        }
    }

    /** What one answer said about one file of one song, and where that answer came from. */
    private class Seen(val gain: Reading, val context: String, val role: String, val order: Long, val page: Long, val device: String)

    /** A file candidate of a song. Raw ids stay here, never in a line. */
    private class Candidate(val uri: String, val fileId: String?, val format: String, val list: String) {
        val seen = ArrayList<Seen>(MAX_SEEN)
    }

    /** An answer as read, before any of it is kept (built outside the lock). */
    private class FileObs(val fileId: String?, val format: String, val list: String, val gain: Reading, val index: Int)
    private class TrackObs(val uri: String, val context: String, val role: String, val files: List<FileObs>)
    private class Answer(val tracks: List<TrackObs>, val currentUri: String?, val currentAd: Boolean, val currentContext: String,
                         val ads: Int, val otherLists: Int, val capped: Int)

    @Volatile private var on = false
    private var runNo = 0
    private var runStart = 0L
    private var seq = 0L
    private var late = 0L
    private var bad = 0L
    private var evicted = 0L
    private var aliasEvicted = 0L
    /** Tags handed to pages, never reused; [armed] is the one accepted now (0: none). */
    private var tags = 0L
    @Volatile private var armed = 0L
    /** The order requests were sent in this run (see [relayTicket]). */
    private var requests = 0L

    /** Assignments held per song uri ("t:") and file id ("f:"): their aliases are never given away. */
    private val pins = HashMap<String, Int>()
    private val entries = object : LinkedHashMap<String, Candidate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Candidate>?): Boolean {
            if (size <= MAX_ENTRIES) return false
            evicted++
            eldest?.value?.let { unpin(it) }
            return true
        }
    }
    private val trackIds = LinkedHashMap<String, Int>(64, 0.75f, true)
    private val fileIds = LinkedHashMap<String, Int>(64, 0.75f, true)
    private var nextTrack = 0
    private var nextFile = 0

    /** When the last lines went out (at most [LINES_PER_S] within [WINDOW_MS]). */
    private val written = ArrayDeque<Long>()
    /** One waiting line per key (see [emit]), oldest first, with how many earlier ones it replaced. */
    private val waiting = LinkedHashMap<String, Pair<String, Int>>()
    private var cancelFlush: (() -> Unit)? = null

    val enabled: Boolean get() = on

    /** The diagnostics switch. On starts a run; off drops the run's table and any waiting line. */
    @Synchronized fun setEnabled(value: Boolean) {
        if (value == on) return
        stop()
        on = value
        if (value) start("on")
    }

    /**
     * The account may change (logout, a lost session): a new run, nothing of the last one kept, and
     * no page armed. The page there now belongs to the old run; the next page loaded is armed.
     */
    @Synchronized fun newRun() {
        if (!on) return
        stop()
        start("account")
    }

    /** A tag for the page that is up now, the only one accepted from here on; null while off. */
    @Synchronized fun armPage(): Long? {
        if (!on) return null
        armed = ++tags
        return armed
    }

    /** The page went away: nothing it still sends is accepted, nor any answer to its requests. */
    @Synchronized fun pageGone() { armed = 0L }

    /**
     * Taken at the relay's entry for a request of the page with [pageTag] (the tag the page was
     * armed with, 0: not armed): that page and the request's order. Null (its answer is never read)
     * while off, for a page not armed, and for a page other than the one armed now (counted "late":
     * a request an earlier page sent, delivered after a new one was armed).
     */
    @Synchronized fun relayTicket(pageTag: Long): Long? {
        if (!on || pageTag <= 0L) return null
        if (armed == 0L || pageTag != armed) { late++; return null }
        requests++
        return (armed shl 32) or (requests and ORDER_MASK)
    }

    /** How many song/file assignments are held. */
    internal val assignments: Int
        get() = synchronized(this) { entries.size }

    /**
     * A track-playback answer the relay received for a request with [ticket]. Read only for its
     * songs' file candidates and their `gain_db`; never changed, nothing sent, never throws.
     */
    fun onRelayAnswer(ticket: Long, path: String, body: String) {
        if (!on) return
        // Read outside the lock: a large answer never holds up the page's messages (main thread).
        val answer = runCatching { parse(body) }.getOrNull()
        val dev = deviceWord()
        synchronized(this) {
            if (!on) return
            if (armed == 0L || ticket ushr 32 != armed) { late++; return }
            if (answer == null) return
            runCatching { record(ticket and ORDER_MASK, armed, path, answer, dev) }.onFailure { bad++ }
        }
    }

    /** A "diag" message of the bridge, read field by field; anything else in it is never looked at. */
    @Synchronized fun onPage(msg: JsonObject) {
        if (!on) return
        runCatching { readPage(msg) }.onFailure { bad++ }
    }

    // --- reading -----------------------------------------------------------------------------------

    private fun record(order: Long, page: Long, path: String, a: Answer, dev: String) {
        val shown = ArrayList<String>()
        var more = 0
        for (t in a.tracks) for (f in t.files) {
            val key = "${t.uri}|${f.fileId ?: "${f.list}#${f.index}"}"
            val c = entries[key] ?: Candidate(t.uri, f.fileId, f.format, f.list).also { pin(it); entries[key] = it }
            c.seen += Seen(f.gain, t.context, t.role, order, page, dev)
            if (c.seen.size > MAX_SEEN) c.seen.remove(c.seen.minBy { it.order })
            if (shown.size < MAX_SHOWN) shown += "${trackId(t.uri)}/${t.role}/${t.context}:${fileLabel(c)}=${f.gain.shown}" else more++
        }
        val current = when { a.currentUri != null -> trackId(a.currentUri); a.currentAd -> "ad"; else -> "unknown" }
        emit("$KIND_ANSWER:$page", "ev=answer page=$page src=${source(path)} ans=$order answerCurrent=$current ctx=${a.currentContext} rxDevice=$dev " +
            "tracks=${a.tracks.size} ads=${a.ads} entries=${shown.joinToString(",").ifEmpty { "none" }} more=$more " +
            "otherLists=${a.otherLists} capped=${a.capped}")
    }

    private fun readPage(msg: JsonObject) {
        fun p(key: String) = msg[key] as? JsonPrimitive
        fun text(key: String) = p(key)?.takeIf { it.isString }?.content
        fun bool(key: String) = p(key)?.takeIf { !it.isString }?.booleanOrNull
        fun number(key: String) = p(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        fun count(key: String) = p(key)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..1_000_000 } ?: 0
        val tag = p("tag")?.takeIf { !it.isString }?.longOrNull
        val ev = text("ev")?.takeIf { it in PAGE_EVENTS }
        if (tag == null || ev == null) { bad++; return }
        if (armed == 0L || tag != armed) { late++; return }
        val uri = text("uri")?.takeIf { URI.matches(it) }
        val pv = when (text("pvSt")) {
            "absent" -> Reading(Status.ABSENT)
            "invalid" -> Reading(Status.INVALID)
            "error" -> Reading(Status.ERROR)
            else -> number("pv")?.let { Reading(Status.OK, it) } ?: Reading(Status.INVALID)
        }
        val vol = number("vol")?.takeIf { it in 0.0..1.0 }
        val level = number("level")?.takeIf { it >= 0.0 }
        val wrote = text("wrote")?.takeIf { it in OWN_WRITES } ?: "none"
        val wroteMs = p("wroteMs")?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..86_400_000 }
        val fault = text("fault")?.takeIf { it in FAULTS } ?: "none"
        val ctx = text("ctx")?.let(::contextWord) ?: "unknown"
        val kind = when {
            uri == null -> "unknown"
            uri.startsWith("spotify:track:") -> "track"
            uri.startsWith("spotify:episode:") -> "episode"
            uri.startsWith("spotify:ad:") -> "ad"
            else -> "other"
        }
        val candidates = if (uri == null) emptyList() else entries.values.filter { it.uri == uri }
        // Bounded in number and length: one line stays whole in Logcat (about 4,000 bytes a line).
        val gains = ArrayList<String>()
        var room = MAX_GAIN_CHARS
        for (c in candidates) {
            if (gains.size == MAX_SHOWN) break
            val d = describe(c, ctx)
            if (d.length + 1 > room) break
            gains += d
            room -= d.length + 1
        }
        val l = if (uri == null) null else runCatching { loudness(uri) }.getOrNull()
        val loudSt = when { uri == null -> "unknown"; l == null -> "not-read"; l.isNaN() -> "unusable"; else -> "cached" }
        emit("$KIND_PAGE:$tag", "ev=$ev page=$tag track=${uri?.let(::trackId) ?: "unknown"} kind=$kind ctx=$ctx pl=${count("pl")} el=${count("el")} " +
            "device=${deviceWord()} pv=${if (pv.status == Status.OK) num(pv.value) else "unknown"} pvSt=${pv.status.label} " +
            "cubic=${flag(bool("cubic"))} vol=${num(vol)} muted=${flag(bool("muted"))} paused=${flag(bool("paused"))} " +
            "level=${num(level)} routed=${flag(bool("routed"))} pageAdMute=${flag(bool("adMute"))} wrote=$wrote " +
            "wroteMs=${wroteMs ?: "unknown"} wroteMatch=${flag(bool("wroteMatch"))} ${settings()} " +
            "loud=${if (loudSt == "cached") num(l) else "unknown"} loudSt=$loudSt " +
            "gain=${gains.joinToString(",").ifEmpty { "none" }} gainMore=${(candidates.size - gains.size).coerceAtLeast(0)} " +
            "pageMerged=${count("merged")} pageFaults=${count("faults")} pageFault=$fault")
    }

    /**
     * A candidate on a page line: its latest observation by request order, then each other one
     * that differs from it in gain or context ("alt"), each with where it came from, and whether
     * its context is the page's ("here": same, other, unknown).
     */
    private fun describe(c: Candidate, pageCtx: String): String {
        val latest = c.seen.maxByOrNull { it.order } ?: return "${fileLabel(c)}=none"
        fun from(s: Seen): String {
            val here = when { s.context == "unknown" || pageCtx == "unknown" -> "unknown"; s.context == pageCtx -> "same"; else -> "other" }
            return "${s.gain.shown}(ans=${s.order};page=${s.page};role=${s.role};ctx=${s.context};rxDev=${s.device};here=$here)"
        }
        val alts = c.seen.filter { it !== latest && (it.gain.shown != latest.gain.shown || it.context != latest.context) }
            .sortedByDescending { it.order }
        return "${fileLabel(c)}=${from(latest)}" + alts.joinToString("") { "|alt=" + from(it) } + "|n=${c.seen.size}"
    }

    // --- output ------------------------------------------------------------------------------------

    private fun start(reason: String) {
        runNo++
        runStart = clock()
        seq = 0
        emit(KIND_META, "ev=start reason=$reason v=3 max=$MAX_ENTRIES perS=$LINES_PER_S ${settings()}")
    }

    private fun stop() {
        cancelFlush?.invoke()
        cancelFlush = null
        waiting.clear()
        entries.clear()
        pins.clear()
        trackIds.clear()
        fileIds.clear()
        nextTrack = 0
        nextFile = 0
        late = 0
        bad = 0
        evicted = 0
        aliasEvicted = 0
        requests = 0
        armed = 0L
    }

    private fun settings(): String {
        val a = runCatching { app() }.getOrNull() ?: App()
        return "norm=${flag(a.normalize)} boost=${a.boost?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unknown"} " +
            "xf=${a.crossfadeSeconds ?: "unknown"} appAdMute=${flag(a.adMuted)}"
    }

    private fun deviceWord(): String = when (runCatching { device() }.getOrNull()) { true -> "local"; false -> "remote"; null -> "unknown" }

    /**
     * A line now if this second has room, else it waits as the one line of its [key] (the newest):
     * its kind and, for a page's line or an answer's, its page, so lines of two pages never merge.
     * ponytail: a waiting line per kind and page; pages come seconds apart, so a few at most.
     */
    private fun emit(key: String, body: String) {
        val now = clock()
        val line = "run=$runNo seq=${seq++} ms=${now - runStart} $body"
        flushReady(now)
        if (waiting.isEmpty() && written.size < LINES_PER_S) {
            write(now, line, 0)
            return
        }
        val held = waiting.remove(key)
        waiting[key] = line to (held?.let { it.second + 1 } ?: 0)
        scheduleFlush(now)
    }

    private fun flushReady(now: Long) {
        while (written.isNotEmpty() && now - written.first() >= WINDOW_MS) written.removeFirst()
        while (waiting.isNotEmpty() && written.size < LINES_PER_S) {
            val kind = waiting.keys.first()
            val (line, merged) = waiting.remove(kind)!!
            write(now, line, merged)
        }
    }

    private fun scheduleFlush(now: Long) {
        if (cancelFlush != null || waiting.isEmpty()) return
        val wait = if (written.size < LINES_PER_S) 0L else (WINDOW_MS - (now - written.first())).coerceAtLeast(0L)
        val forRun = runNo
        cancelFlush = schedule(wait) {
            synchronized(this) {
                cancelFlush = null
                if (!on || runNo != forRun) return@synchronized
                flushReady(clock())
                scheduleFlush(clock())
            }
        }
    }

    private fun write(now: Long, line: String, merged: Int) {
        written.addLast(now)
        runCatching {
            output("$PREFIX $line table=${entries.size} evicted=$evicted aliasEvicted=$aliasEvicted late=$late bad=$bad merged=$merged")
        }
    }

    // --- ids and words -----------------------------------------------------------------------------

    private fun pin(c: Candidate) {
        pins["t:" + c.uri] = (pins["t:" + c.uri] ?: 0) + 1
        c.fileId?.let { pins["f:$it"] = (pins["f:$it"] ?: 0) + 1 }
    }

    private fun unpin(c: Candidate) {
        fun drop(key: String) { val n = (pins[key] ?: 0) - 1; if (n > 0) pins[key] = n else pins.remove(key) }
        drop("t:" + c.uri)
        c.fileId?.let { drop("f:$it") }
    }

    /** The run's id for [key]; past [MAX_ALIASES] the least recently used one no assignment holds goes. */
    private fun alias(table: LinkedHashMap<String, Int>, prefix: String, key: String, next: () -> Int): Int {
        val id = table.getOrPut(key, next)
        if (table.size > MAX_ALIASES) {
            val keys = table.keys.iterator()
            while (table.size > MAX_ALIASES && keys.hasNext()) {
                val k = keys.next()
                if (k != key && !pins.containsKey(prefix + k)) { keys.remove(); aliasEvicted++ }
            }
        }
        return id
    }

    private fun trackId(uri: String) = "t" + alias(trackIds, "t:", uri) { ++nextTrack }

    private fun fileLabel(c: Candidate): String {
        val file = c.fileId?.let { "f" + alias(fileIds, "f:", it) { ++nextFile } } ?: "?"
        val sel = if (c.fileId != null) "candidate" else "unknown"
        return "$file@${c.format}/${c.list}:$sel"
    }

    companion object {
        const val MAX_ENTRIES = 256
        const val LINES_PER_S = 2
        const val WINDOW_MS = 1_000L
        const val PREFIX = "gain-diag"
        /** Candidates written in one line; the rest are counted ("more", "gainMore"). */
        const val MAX_SHOWN = 12
        /** Observations kept per candidate (the newest by request order). */
        const val MAX_SEEN = 4
        /** File observations read from one answer; the rest are counted ("capped"). */
        const val MAX_PER_ANSWER = 512
        /** Run ids kept per kind: those held by an assignment, then the most recently used. */
        const val MAX_ALIASES = 512
        /** Characters of candidates on one page line; the rest are counted ("gainMore"). */
        const val MAX_GAIN_CHARS = 3_000

        private const val ORDER_MASK = 0xffffffffL
        private const val KIND_META = "meta"
        private const val KIND_ANSWER = "answer"
        private const val KIND_PAGE = "page"
        private val PAGE_EVENTS = setOf("enable", "track", "context", "player", "element", "volume")
        private val OWN_WRITES = setOf("level", "restore", "mute")
        private val FAULTS = setOf("state", "read", "locate", "flush", "write", "command", "follow")
        private val CONTEXTS = setOf("album", "playlist", "artist", "collection", "station", "show", "episode", "track", "user", "search", "genre", "local", "app")
        /** The manifest groups of file ids the diagnostics know, by fixed word; any other is only counted. */
        private val LISTS = mapOf("file_ids_mp4" to "mp4", "file_ids_mp4_dual" to "mp4_dual")
        private val URI = Regex("^spotify:(track|episode|ad|local|chapter):[A-Za-z0-9._%:+-]{1,200}$")
        private val FILE_ID = Regex("^[0-9a-f]{16,64}$")
        private val FORMAT = Regex("^[0-9]{1,3}$")
        private val STATE_PATH = Regex("^/track-playback/v1/devices/[^/]+/state$")

        /** A number as written; one too large for a level or a gain is not written out in full. */
        private fun num(v: Double?): String = when {
            v == null -> "unknown"
            !v.isFinite() || kotlin.math.abs(v) > 1e6 -> "out-of-range"
            else -> String.format(Locale.ROOT, "%.4f", v)
        }
        private fun flag(v: Boolean?): String = v?.toString() ?: "unknown"

        /** Which kind of request an answer came for, without its device id. */
        private fun source(path: String): String = when {
            STATE_PATH.matches(path) -> "track-playback/state"
            path.endsWith("/track-playback/v1/devices") -> "track-playback/devices"
            else -> "track-playback/other"
        }

        private fun contextType(uri: String?): String {
            if (uri.isNullOrEmpty()) return "unknown"
            if (!uri.startsWith("spotify:")) return "other"
            if (uri.endsWith(":collection") || ":collection:" in uri) return "collection"
            return contextWord(uri.removePrefix("spotify:").substringBefore(':'))
        }

        private fun contextWord(word: String): String = if (word in CONTEXTS) word else "other"

        /**
         * The songs and file candidates of an answer, read without keeping anything: the current song
         * is the one the answer's own state ref points at, and its context is that song's (unknown
         * when it has none, never another song's).
         */
        private fun parse(body: String): Answer? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val machine = root["state_machine"] as? JsonObject ?: return null
            val tracks = machine["tracks"] as? JsonArray ?: return null
            val states = machine["states"] as? JsonArray
            val ref = ((root["updated_state_ref"] as? JsonObject)?.get("state_index") as? JsonPrimitive)?.intOrNull
            val currentIndex = ref?.let { ((states?.getOrNull(it) as? JsonObject)?.get("track") as? JsonPrimitive)?.intOrNull }
            var currentUri: String? = null
            var currentAd = false
            var currentContext = "unknown"
            var ads = 0
            var otherLists = 0
            var capped = 0
            var taken = 0
            val out = ArrayList<TrackObs>()
            for ((index, element) in tracks.withIndex()) {
                val t = element as? JsonObject ?: continue
                val meta = t["metadata"] as? JsonObject
                val uri = (meta?.get("uri") as? JsonPrimitive)?.contentOrNull?.takeIf { URI.matches(it) }
                val type = (t["content_type"] as? JsonPrimitive)?.contentOrNull
                if (type == "AD" || uri?.startsWith("spotify:ad:") == true) {
                    ads++
                    if (index == currentIndex) currentAd = true
                    continue
                }
                if (uri == null) continue
                val ctx = contextType((meta?.get("context_uri") as? JsonPrimitive)?.contentOrNull)
                val role = if (index == currentIndex) "cur" else "queue"
                if (role == "cur") { currentUri = uri; currentContext = ctx }
                val files = ArrayList<FileObs>()
                val manifest = t["manifest"] as? JsonObject
                if (manifest != null) for ((name, value) in manifest) {
                    if (!name.startsWith("file_ids_")) continue
                    val list = LISTS[name]
                    if (list == null) { otherLists++; continue }
                    val array = value as? JsonArray ?: continue
                    for ((i, f) in array.withIndex()) {
                        val o = f as? JsonObject ?: continue
                        if (taken >= MAX_PER_ANSWER) { capped++; continue }
                        taken++
                        val fileId = (o["file_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { FILE_ID.matches(it) }
                        val format = (o["format"] as? JsonPrimitive)?.contentOrNull?.takeIf { FORMAT.matches(it) } ?: "?"
                        files += FileObs(fileId, format, list, Reading.of(o, "gain_db"), i)
                    }
                }
                out += TrackObs(uri, ctx, role, files)
            }
            return Answer(out, currentUri, currentAd, currentContext, ads, otherLists, capped)
        }
    }
}
