package app.myspoti.host.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's own log, kept in memory so a tester can hand it back as a file.
 *
 * The WebView bridge is invisible on a phone: the only evidence of a refused playback
 * command (`402 COMMAND_FAILED`) or of a sign-in that stalls is these lines, and until now
 * they were reachable only through `adb logcat` ([WebPlayerHost.LOG]) — which the person
 * reporting the bug cannot run. Every line is mirrored to Logcat with the very same text
 * ([log]) and kept in a bounded ring buffer that [export] writes to a shareable file, so
 * the report a tester sends is the app's own log and nothing else has to change.
 */
object Logbook {
    /**
     * Only the tail is kept: a report is read from the end, and an unbounded buffer would
     * grow for the whole life of the process (the page can run for hours).
     */
    private const val MAX_LINES = 1500

    /** Guarded by its own monitor: [log] is called from the main thread, OkHttp threads and coroutines. */
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Records [line] and mirrors it to Logcat with the same message, so the exported report
     * and `adb logcat -s Myspoti` show the same thing. Safe from any thread.
     */
    /**
     * [url] without its query string and fragment (Myspoti 2.1.0): they carry tokens, ids and
     * search terms. For every URL put in a log line.
     */
    fun noQuery(url: String): String = url.substringBefore('#').substringBefore('?')

    fun log(raw: String) {
        // Spotilol: every engine line goes through the shared redactor before Logcat or the buffer.
        val line = app.myspoti.spotify.security.LogRedactor.redact(raw)
        android.util.Log.d(WebPlayerHost.LOG, line)
        synchronized(lines) {
            lines.addLast("${stamp.format(Date())} $line")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** When each [logEvery] key last wrote, and how many lines it held back since. */
    private val every = HashMap<String, LongArray>()

    /** [line] at most once per [intervalMs] for [key]; the next line says how many were held back (Myspoti 2.3.0). */
    fun logEvery(key: String, intervalMs: Long, line: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val held = synchronized(every) {
            val e = every.getOrPut(key) { longArrayOf(Long.MIN_VALUE / 2, 0) }
            if (now - e[0] < intervalMs) { e[1]++; return }
            val n = e[1]
            e[0] = now; e[1] = 0
            n
        }
        log(if (held > 0) "$line (+$held like it since)" else line)
    }

    /** The buffered lines, oldest first. */
    fun recent(): String = synchronized(lines) { lines.joinToString("\n") }

    /**
     * Writes the report (device and version header, then [recent]) to the app's own external
     * files directory, so it can be handed to the share sheet through the FileProvider.
     * Returns the file for the caller to share.
     */
    fun export(context: Context): File {
        // The external dir is what a FileProvider can expose; the internal one is the
        // fallback for a device with no external storage.
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "diagnostic-${fileStamp.format(Date())}.txt")
        file.writeText(report(context))
        return file
    }

    /**
     * What decides whether Android lets the app run with the screen off: the battery exemption, the
     * standby bucket, the power saver, the process importance, the maker's name (Samsung and others
     * add their own sleeping lists). Music that stops a few seconds after the lock cannot be told
     * apart without them.
     */
    private fun powerLines(context: Context): List<Pair<String, String>> = buildList {
        runCatching {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            add("exempt" to "ignoring battery optimizations=${pm.isIgnoringBatteryOptimizations(context.packageName)}")
            add("saver" to "power save mode=${pm.isPowerSaveMode} device idle=${pm.isDeviceIdleMode}")
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            val bucket = context.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket
            val name = when (bucket) {
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
                else -> "other"
            }
            val restricted = context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
            add("standby" to "bucket $bucket ($name) background restricted=$restricted")
        }
        runCatching {
            val me = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
            val importance = when (me.importance) {
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
                else -> "other"
            }
            add("process" to "importance ${me.importance} ($importance)")
        }
        add("maker" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    private fun report(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "?"
        val versionCode = info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        // The player's page URL tells which Spotify screen the app was on; read only when the
        // runtime started, or the not-yet-initialized host would crash the export.
        val pageUrl = if (MyspotiRuntime.isStarted) runCatching { MyspotiRuntime.host.url.value }.getOrNull() else null
        val tail = recent()
        return buildString {
            appendLine("MySpotilol diagnostic report")
            appendLine("app     : $versionName ($versionCode)")
            appendLine("android : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("device  : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("locale  : ${Locale.getDefault().toLanguageTag()}")
            if (!pageUrl.isNullOrEmpty()) appendLine("page    : ${noQuery(pageUrl)}")
            appendLine("--- power ---")
            powerLines(context).forEach { (k, v) -> appendLine("${k.padEnd(8)}: $v") }
            appendLine()
            appendLine("--- log ---")
            appendLine(tail)
        }
    }
}
