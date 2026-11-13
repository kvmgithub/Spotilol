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
 * and file candidate, and what the page player's level settings were at a change of song, of
 * element or of the element's volume. Observation only: nothing here changes a level, a request
 * or an answer, asks Spotify anything or takes part in a playback decision. Off unless the
 * diagnostics switch is on ([setEnabled]); off, nothing is read, kept or scheduled.
 *
 * Sources: answers the control relay already received ([onRelayAnswer], with the run they were
 * asked in, so an answer of an earlier run is dropped), the bridge's "diag" messages ([onPage]),
 * and what the player already holds (loudness read for the normalization, its sound settings, the
 * Connect device the page said it was). A song is the page's song only when the page says so: the
 * state an answer points at is that answer's, not what plays.
 *
 * Bounds: [MAX_ENTRIES] song/file assignments (the least recently seen goes), [LINES_PER_S] lines
 * a second; more in between wait as one line per kind (the newest), with how many it stands for.
 * Lines carry ids of the run (t1 for a song, f1 for a file) instead of Spotify's, and no token,
 * cookie, URL, name or raw id. A file is a "candidate" (offered in the answer) or "unknown" (no file
 * id): nothing the app receives says which file the page plays, so none is ever "selected" here.
 */
class GainDiagnostics(
    private val output: (String) -> Unit,
    /** Runs [run] once after [delayMs]; returns its cancel. */
    private val schedule: (delayMs: Long, run: () -> Unit) -> (() -> Unit),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Loudness the player already read for a track uri: NaN when Spotify has none, null when not read. */
    private val loudness: (String) -> Double? = { null },
    private val app: () -> App = { App() },
    /** Whether the page is Spotify Connect's active device, as the player last read it (null: unknown). */
    private val device: () -> Boolean? = { null },
) {
    /** The app's own sound settings at the time of a line; null: not known. */
    data class App(val normalize: Boolean? = null, val boost: Double? = null, val crossfadeSeconds: Int? = null, val adMuted: Boolean? = null)

    /** A value as read, with whether it was there: [Status.OK] carries it, 0.0 included. */
    enum class Status(val label: String) { OK("ok"), ABSENT("absent"), NULL("null"), INVALID("invalid") }

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

    /** One file candidate of one song, as an answer offered it. Raw ids stay here, never in a line. */
    private class Offered(val uri: String, val fileId: String?, val format: String, val list: String, val gain: Reading, val context: String)

    @Volatile private var on = false
    private var runNo = 0
    private var runStart = 0L
    private var seq = 0L
    private var late = 0L
    private var bad = 0L
    private var evicted = 0L

    private val entries = object : LinkedHashMap<String, Offered>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Offered>?): Boolean =
            (size > MAX_ENTRIES).also { if (it) evicted++ }
    }
    private val trackIds: LinkedHashMap<String, Int> = idTable()
    private val fileIds: LinkedHashMap<String, Int> = idTable()
    private var nextTrack = 0
    private var nextFile = 0

    /** When the last lines went out (at most [LINES_PER_S] within [WINDOW_MS]). */
    private val written = ArrayDeque<Long>()
    /** One waiting line per kind, oldest kind first, with how many earlier ones it replaced. */
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

    /** The account may change (logout, a lost session): a new run, nothing of the last one kept. */
    @Synchronized fun newRun() {
        if (!on) return
        stop()
        start("account")
    }

    /** The run a relay request is made in, taken when it is sent; null while off (then nothing is read later). */
    @Synchronized fun runToken(): Long? = if (on) runNo.toLong() else null

    /** How many song/file assignments are held. */
    internal val assignments: Int
        get() = synchronized(this) { entries.size }

    /**
     * A track-playback answer the relay received for a request made in run [token]. Read only for
     * its songs' file candidates and their `gain_db`; never changed, nothing sent.
     */
    fun onRelayAnswer(token: Long, path: String, body: String) {
        if (!on) return
        // Parsed outside the lock: a large answer never holds up the page's messages (main thread).
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        synchronized(this) {
            if (!on) return
            if (token != runNo.toLong()) { late++; return }
            if (root == null) return
            runCatching { readAnswer(path, root) }.onFailure { bad++ }
        }
    }

    /** A "diag" message of the bridge, read field by field; anything else in it is never looked at. */
    @Synchronized fun onPage(msg: JsonObject) {
        if (!on) return
        runCatching { readPage(msg) }.onFailure { bad++ }
    }

    // --- reading -----------------------------------------------------------------------------------

    private fun readAnswer(path: String, root: JsonObject) {
        val machine = root["state_machine"] as? JsonObject ?: return
        val tracks = machine["tracks"] as? JsonArray ?: return
        val states = machine["states"] as? JsonArray
        val ref = ((root["updated_state_ref"] as? JsonObject)?.get("state_index") as? JsonPrimitive)?.intOrNull
        val currentIndex = ref?.let { ((states?.getOrNull(it) as? JsonObject)?.get("track") as? JsonPrimitive)?.intOrNull }
        var current = "unknown"
        var context = "unknown"
        var songs = 0
        var ads = 0
        val shown = ArrayList<String>()
        var more = 0
        for ((index, element) in tracks.withIndex()) {
            val t = element as? JsonObject ?: continue
            val meta = t["metadata"] as? JsonObject
            val uri = (meta?.get("uri") as? JsonPrimitive)?.contentOrNull?.takeIf { URI.matches(it) }
            val type = (t["content_type"] as? JsonPrimitive)?.contentOrNull
            if (type == "AD" || uri?.startsWith("spotify:ad:") == true) {
                ads++
                if (index == currentIndex) current = "ad"
                continue
            }
            if (uri == null) continue
            songs++
            val ctx = contextType((meta?.get("context_uri") as? JsonPrimitive)?.contentOrNull)
            if (index == currentIndex) { current = trackId(uri); context = ctx }
            else if (context == "unknown") context = ctx
            val manifest = t["manifest"] as? JsonObject ?: continue
            for ((list, files) in manifest) {
                if (!LIST.matches(list)) continue
                for ((i, f) in ((files as? JsonArray) ?: continue).withIndex()) {
                    val o = f as? JsonObject ?: continue
                    val fileId = (o["file_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { FILE_ID.matches(it) }
                    val format = (o["format"] as? JsonPrimitive)?.contentOrNull?.takeIf { FORMAT.matches(it) } ?: "?"
                    val e = Offered(uri, fileId, format, list.removePrefix("file_ids_"), Reading.of(o, "gain_db"), ctx)
                    entries["$uri|${fileId ?: "$list#$i"}"] = e
                    if (shown.size < MAX_SHOWN) shown += "${trackId(uri)}.${describe(e)}" else more++
                }
            }
        }
        emit(KIND_ANSWER, "ev=answer src=${source(path)} answerCurrent=$current ctx=$context tracks=$songs ads=$ads " +
            "entries=${shown.joinToString(",").ifEmpty { "none" }} more=$more")
    }

    private fun readPage(msg: JsonObject) {
        fun p(key: String) = msg[key] as? JsonPrimitive
        fun text(key: String) = p(key)?.takeIf { it.isString }?.content
        fun bool(key: String) = p(key)?.takeIf { !it.isString }?.booleanOrNull
        fun number(key: String) = p(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        val ev = text("ev")?.takeIf { it in PAGE_EVENTS }
        if (ev == null) { bad++; return }
        val uri = text("uri")?.takeIf { URI.matches(it) }
        val el = p("el")?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..1_000_000 } ?: 0
        val pv = when (text("pvSt")) {
            "absent" -> Reading(Status.ABSENT)
            "invalid" -> Reading(Status.INVALID)
            else -> number("pv")?.let { Reading(Status.OK, it) } ?: Reading(Status.INVALID)
        }
        val vol = number("vol")?.takeIf { it in 0.0..1.0 }
        val level = number("level")?.takeIf { it >= 0.0 }
        val wrote = text("wrote")?.takeIf { it in OWN_WRITES } ?: "none"
        val wroteMs = p("wroteMs")?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..86_400_000 }
        val pageMerged = p("merged")?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..1_000_000 } ?: 0
        val ctx = text("ctx")?.let(::contextWord) ?: "unknown"
        val kind = when {
            uri == null -> "unknown"
            uri.startsWith("spotify:track:") -> "track"
            uri.startsWith("spotify:episode:") -> "episode"
            uri.startsWith("spotify:ad:") -> "ad"
            else -> "other"
        }
        val gains = if (uri == null) emptyList() else entries.values.filter { it.uri == uri }.map(::describe)
        val l = if (uri == null) null else runCatching { loudness(uri) }.getOrNull()
        val loudSt = when { uri == null -> "unknown"; l == null -> "not-read"; l.isNaN() -> "none"; else -> "cached" }
        val active = runCatching { device() }.getOrNull()
        emit(KIND_PAGE, "ev=$ev track=${uri?.let(::trackId) ?: "unknown"} kind=$kind ctx=$ctx el=$el " +
            "device=${when (active) { true -> "local"; false -> "remote"; null -> "unknown" }} " +
            "pv=${if (pv.status == Status.OK) num(pv.value) else "unknown"} pvSt=${pv.status.label} cubic=${flag(bool("cubic"))} " +
            "vol=${num(vol)} muted=${flag(bool("muted"))} paused=${flag(bool("paused"))} level=${num(level)} routed=${flag(bool("routed"))} " +
            "pageAdMute=${flag(bool("adMute"))} wrote=$wrote wroteMs=${wroteMs ?: "unknown"} wroteMatch=${flag(bool("wroteMatch"))} " +
            "${settings()} loud=${if (loudSt == "cached") num(l) else "unknown"} loudSt=$loudSt " +
            "gain=${gains.joinToString(",").ifEmpty { "none" }} pageMerged=$pageMerged")
    }

    // --- output ------------------------------------------------------------------------------------

    private fun start(reason: String) {
        runNo++
        runStart = clock()
        seq = 0
        emit(KIND_META, "ev=start reason=$reason v=1 max=$MAX_ENTRIES perS=$LINES_PER_S ${settings()}")
    }

    private fun stop() {
        cancelFlush?.invoke()
        cancelFlush = null
        waiting.clear()
        entries.clear()
        trackIds.clear()
        fileIds.clear()
        nextTrack = 0
        nextFile = 0
        late = 0
        bad = 0
        evicted = 0
    }

    private fun settings(): String {
        val a = runCatching { app() }.getOrNull() ?: App()
        return "norm=${flag(a.normalize)} boost=${a.boost?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unknown"} " +
            "xf=${a.crossfadeSeconds ?: "unknown"} appAdMute=${flag(a.adMuted)}"
    }

    /** A line now if this second has room, else it waits as its kind's one line (the newest). */
    private fun emit(kind: String, body: String) {
        val now = clock()
        val line = "run=$runNo seq=${seq++} ms=${now - runStart} $body"
        flushReady(now)
        if (waiting.isEmpty() && written.size < LINES_PER_S) {
            write(now, line, 0)
            return
        }
        val held = waiting.remove(kind)
        waiting[kind] = line to (held?.let { it.second + 1 } ?: 0)
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
        runCatching { output("$PREFIX $line table=${entries.size} evicted=$evicted late=$late bad=$bad merged=$merged") }
    }

    // --- ids and words -----------------------------------------------------------------------------

    private fun trackId(uri: String) = "t" + trackIds.getOrPut(uri) { ++nextTrack }

    private fun describe(e: Offered): String {
        val file = e.fileId?.let { "f" + fileIds.getOrPut(it) { ++nextFile } } ?: "?"
        val sel = if (e.fileId != null) "candidate" else "unknown"
        return "$file@${e.format}/${e.list}:$sel=${e.gain.shown}"
    }

    /** Which kind of request an answer came for, without its device id. */
    private fun source(path: String): String = when {
        Regex("^/track-playback/v1/devices/[^/]+/state$").matches(path) -> "track-playback/state"
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

    companion object {
        const val MAX_ENTRIES = 256
        const val LINES_PER_S = 2
        const val WINDOW_MS = 1_000L
        const val PREFIX = "gain-diag"
        /** Entries written in one answer's line; the rest are counted ("more"). */
        const val MAX_SHOWN = 12

        private const val KIND_META = "meta"
        private const val KIND_ANSWER = "answer"
        private const val KIND_PAGE = "page"
        private val PAGE_EVENTS = setOf("enable", "track", "element", "volume")
        private val OWN_WRITES = setOf("level", "restore", "mute")
        private val CONTEXTS = setOf("album", "playlist", "artist", "collection", "station", "show", "episode", "track", "user", "search", "genre", "local", "app")
        private val URI = Regex("^spotify:(track|episode|ad|local|chapter):[A-Za-z0-9._%:+-]{1,200}$")
        private val FILE_ID = Regex("^[0-9a-f]{16,64}$")
        private val FORMAT = Regex("^[0-9]{1,3}$")
        private val LIST = Regex("^file_ids_[a-z0-9_]{1,24}$")

        private fun num(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "unknown"
        private fun flag(v: Boolean?): String = v?.toString() ?: "unknown"

        /** Run-local ids by raw id, the least recently used out past [MAX_ENTRIES]. */
        private fun idTable(): LinkedHashMap<String, Int> = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_ENTRIES
        }
    }
}
