package com.music.spotui.lossless

/*
 * The signed session SpotiFLAC Mobile's download extensions use (qobuz-web,
 * tidal-web…), written from its protocol: the extension manifest's `signedSession`
 * block and the app's signed_session runtime (bootstrap → hosted challenge →
 * grant → exchange; ZARZ-HMAC-V1 signatures; download tickets).
 * SpotiFLAC Mobile is MIT (Copyright (c) 2026 zarzet), its extensions Apache-2.0:
 * see THIRD_PARTY_NOTICES.md.
 */

import android.content.Context
import com.music.spotui.data.preferences.getSpotiflacSession
import com.music.spotui.data.preferences.saveSpotiflacSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object ZarzSession {
    const val BASE = "https://api.zarz.moe/v2"
    const val PLATFORM = "extension"

    /** The extension a session is issued to; the server checks it in every signature. */
    enum class Ext(val appVersion: String) {
        TIDAL("tidal-web@1.2.5"),
        QOBUZ("qobuz-web@1.2.21"),
    }
    const val CALLBACK = "spotiflac://session-grant"
    private const val SCHEME = "ZARZ-HMAC-V1"
    private const val PREFIX = "X-Zarz-"
    private const val WINDOW_SECONDS = 300L
    private const val REFRESH_AHEAD_MS = 3_600_000L

    /**
     * The grant redirects the verification page sends back to the app
     * (spotiflac://session-grant…). A StateFlow, not a SharedFlow: the value is
     * held until the screen takes it, so a rotation (or a screen that starts
     * collecting a moment later) cannot lose the return. The screen clears it.
     */
    val grants = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A challenge page to open (the user solves it), or null when a session came back directly. */
    class Bootstrap(val challengeUrl: String?, val state: String)

    /** The gateway wants the user to verify again (HTTP 428 / VERIFY_REQUIRED). */
    class VerifyRequired : Exception("VERIFY_REQUIRED")

    fun installId(context: Context): String =
        com.music.spotui.data.preferences.spotiflacInstallId(context).takeIf { it.isNotBlank() } ?: randomHex(16)

    /** GET /bootstrap: a session right away, or the challenge to solve. */
    fun bootstrap(context: Context, ext: Ext, installId: String): Bootstrap {
        val (code, text) = request(ext, "GET", "$BASE/bootstrap?app_version=${enc(ext.appVersion)}&install_id=${enc(installId)}", null, emptyMap())
        if (code !in 200..299) throw java.io.IOException("bootstrap HTTP $code: ${text.take(120)}")
        val o = JSONObject(text)
        if (store(context, ext, installId, o)) return Bootstrap(null, "")
        val state = randomHex(16)
        val given = o.optString("auth_url").ifBlank { o.optString("challenge_url") }
        val callback = "$CALLBACK?cb_version=v2grant&state=$state"
        val url = when {
            given.startsWith("https://") -> if ("state=" in given) given else "$given${if ('?' in given) '&' else '?'}state=$state"
            o.optString("challenge_id").isNotBlank() -> "$BASE/challenge?id=${enc(o.optString("challenge_id"))}&cb=${enc(callback)}"
            else -> throw java.io.IOException("bootstrap gave neither a session nor a challenge")
        }
        return Bootstrap(url, state)
    }

    /** POST /session/exchange with the grant the challenge page handed back. */
    fun exchange(context: Context, ext: Ext, installId: String, grant: String) {
        val body = JSONObject().put("grant", grant).put("install_id", installId)
            .put("app_version", ext.appVersion).put("platform", PLATFORM).toString()
        val (code, text) = request(ext, "POST", "$BASE/session/exchange", body, mapOf("Content-Type" to "application/json"))
        if (code !in 200..299 || !store(context, ext, installId, JSONObject(text))) throw java.io.IOException("session exchange failed: HTTP $code")
    }

    /**
     * A signed JSON call ([path] under [BASE], e.g. "/tickets"); returns the body.
     * Refreshes a session that expires within the hour first.
     */
    /**
     * As SpotiFLAC Mobile's signed-session client does: a request refused because another
     * download renewed the session meanwhile (the renewal changes the secret) is sent again
     * once with the new one; the session is dropped only when the gateway says to start
     * over (401 SESSION_INVALID, bootstrap_session); "verify" (428) keeps it; a provider
     * that is briefly unavailable (503, same_operation) is waited for, at most twice.
     */
    fun signedJson(context: Context, ext: Ext, method: String, path: String, body: JSONObject?, extra: Map<String, String> = emptyMap()): JSONObject {
        refreshIfDue(context, ext)
        var sessionRetried = false
        var providerRetries = 0
        while (true) {
            val used = com.music.spotui.data.preferences.getSpotiflacSession(context, ext.appVersion) ?: throw VerifyRequired()
            val (code, text) = signed(context, ext, method, path, body?.toString(), extra)
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            val err = o.optString("code").uppercase()
            val origin = o.optString("origin").lowercase()
            val action = o.optString("action").lowercase()
            if (code == 503 && origin == "provider" && err == "PROVIDER_UNAVAILABLE" && o.optBoolean("retryable") &&
                o.optString("retry_mode").equals("same_operation", true) && providerRetries < 2) {
                providerRetries++
                Thread.sleep((o.optLong("retry_after_seconds", 2).coerceIn(1, 120)) * 1000)
                continue
            }
            val sessionRefused = (code == 401 && err == "SESSION_INVALID") || (code == 403 && err == "REQUEST_AUTH_INVALID")
            if (sessionRefused) {
                val latest = com.music.spotui.data.preferences.getSpotiflacSession(context, ext.appVersion)
                if (!sessionRetried && latest != null && latest.sessionSecret != used.sessionSecret) { sessionRetried = true; continue }
                if (code == 401 && origin == "gateway" && action == "bootstrap_session")
                    com.music.spotui.data.preferences.clearSpotiflacSession(context, ext.appVersion)
                throw VerifyRequired()
            }
            if (code == 428 || err == "VERIFY_REQUIRED" || o.optBoolean("needsVerification")) throw VerifyRequired()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${(o.optString("error").ifBlank { text }).take(160)}")
            return o
        }
    }

    /** A download ticket for one resource ("qbz", "track", the track's URL). */
    fun ticket(context: Context, ext: Ext, provider: String, type: String, id: String): String {
        val hash = sha256Hex("$provider:$type:${id.lowercase()}".toByteArray())
        val o = signedJson(context, ext, "POST", "/tickets", JSONObject()
            .put("capability", "download_ticket").put("provider", provider).put("resource_hash", hash))
        return o.optString("ticket_id").ifBlank { o.optString("ticket") }.trim().ifBlank { throw java.io.IOException("no ticket_id") }
    }

    private val refreshLocks = Ext.entries.associateWith { Any() }

    /**
     * Renews the session in its last hour, one renewal at a time (as SpotiFLAC Mobile): the
     * renewal changes the secret, so renewing on every call of parallel downloads made the
     * server refuse the others and the session was lost. An ended session still gets a try.
     */
    private fun refreshIfDue(context: Context, ext: Ext) = synchronized(refreshLocks.getValue(ext)) {
        // Read again under the lock: another download may have just renewed it.
        val s = getSpotiflacSession(context, ext.appVersion) ?: throw VerifyRequired()
        val expires = parseIso(s.expiresAt) ?: return
        val left = expires - System.currentTimeMillis()
        if (left > REFRESH_AHEAD_MS) return
        val renewed = runCatching {
            val (code, text) = signed(context, ext, "POST", "/session/refresh", JSONObject().put("install_id", s.installId).toString(), emptyMap())
            if (code in 200..299) { store(context, ext, s.installId, JSONObject(text), keepMissing = true); true } else false
        }.getOrDefault(false)
        if (!renewed && left <= 0) throw VerifyRequired()
    }

    private fun signed(context: Context, ext: Ext, method: String, path: String, body: String?, extra: Map<String, String>): Pair<Int, String> {
        val s = getSpotiflacSession(context, ext.appVersion) ?: throw VerifyRequired()
        val url = BASE + path
        val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
        val headers = signHeaders(s.sessionId, s.sessionSecret, ext.appVersion, method, URL(url).path, bytes, System.currentTimeMillis(), randomHex(12)) +
            extra + (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
        return request(ext, method, url, body, headers)
    }

    /** ZARZ-HMAC-V1: a key rolled every 5 minutes from the secret, then the request signed with it. */
    internal fun signHeaders(sessionId: String, secret: String, appVersion: String, method: String, path: String, body: ByteArray, nowMs: Long, nonce: String): Map<String, String> {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(nowMs))
        val hash = sha256Hex(body)
        val window = (nowMs / 1000) / WINDOW_SECONDS
        val rolling = hmac(secret.toByteArray(), "$window:$sessionId".toByteArray())
        val signing = listOf(SCHEME, method, path, "", hash, ts, nonce, sessionId, appVersion, PLATFORM).joinToString("\n")
        val signature = hmac(rolling.toByteArray(), signing.toByteArray())
        return linkedMapOf(
            "Session" to sessionId, "Timestamp" to ts, "Nonce" to nonce, "Body-SHA256" to hash,
            "Signature" to signature, "App-Version" to appVersion, "Platform" to PLATFORM,
        ).mapKeys { PREFIX + it.key }
    }

    private fun store(context: Context, ext: Ext, installId: String, o: JSONObject, keepMissing: Boolean = false): Boolean {
        val id = o.optString("session_id"); val secret = o.optString("session_secret"); val exp = o.optString("expires_at")
        if (id.isBlank() || secret.isBlank()) {
            if (!keepMissing || exp.isBlank()) return false
            val old = getSpotiflacSession(context, ext.appVersion) ?: return false
            saveSpotiflacSession(context, old.sessionId, old.sessionSecret, installId, exp, ext.appVersion)
            return true
        }
        saveSpotiflacSession(context, id, secret, installId, exp, ext.appVersion)
        return true
    }

    private fun request(ext: Ext, method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            // As SpotiFLAC Mobile's runtime: "SpotiFLAC-Mobile/<extension version>" (the bare name gets 403).
            setRequestProperty("User-Agent", "SpotiFLAC-Mobile/${ext.appVersion}")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) doOutput = true
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The grant and state of a verification return link, read as SpotiFLAC Mobile reads
     * it: `grant` (or `code`) and `state` in the query, else anywhere in the link
     * (fragment included), else in a nested `cb` link. Null when either is missing.
     */
    internal fun parseGrant(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        fun nonEmpty(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() }
        fun query(name: String): String? = Regex("[?&]" + name + "=([^&#]*)").find(t.substringBefore('#'))?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        var grant = nonEmpty(query("grant") ?: query("code"))
        var state = nonEmpty(query("state"))
        val nested = nonEmpty(query("cb"))
        if (grant == null && nested != null) parseGrant(nested)?.let { return it }
        fun anywhere(name: String): String? = Regex("(?:^|[?&#\\s])" + name + "=([^&#\\s]+)").find(t)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        grant = nonEmpty(grant ?: anywhere("grant") ?: anywhere("code"))
        state = nonEmpty(state ?: anywhere("state"))
        return if (grant != null && state != null) grant to state else null
    }

    internal fun parseIso(value: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun hmac(key: ByteArray, data: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data),
    )

    internal fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun randomHex(n: Int): String = ByteArray(n).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
