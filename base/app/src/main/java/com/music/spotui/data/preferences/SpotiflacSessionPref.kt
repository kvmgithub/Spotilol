package com.music.spotui.data.preferences

import android.content.Context

/**
 * The signed sessions of the SpotiFLAC download servers (Zarz), one per extension
 * they are issued to (Tidal, Qobuz): the server-issued session_id + session_secret
 * that sign the download requests, captured by the verification screen after the
 * user solves the challenge themselves. The install id is shared.
 */
private const val PREF = "SpotiflacSession"

private fun prefs(c: Context, appVersion: String) =
    c.getSharedPreferences(PREF + "_" + appVersion.substringBefore('@'), Context.MODE_PRIVATE)

fun saveSpotiflacSession(
    context: Context,
    sessionId: String,
    sessionSecret: String,
    installId: String,
    expiresAt: String,
    appVersion: String,
) {
    prefs(context, appVersion).edit()
        .putString("app_version", appVersion)
        .putString("session_id", sessionId)
        .putString("session_secret", sessionSecret)
        .putString("expires_at", expiresAt)
        .apply()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("install_id", installId).apply()
}

data class SpotiflacSession(
    val sessionId: String,
    val sessionSecret: String,
    val installId: String,
    val expiresAt: String,
)

/** The install id the servers bind sessions to (kept across sessions). */
fun spotiflacInstallId(context: Context): String =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("install_id", "").orEmpty()

fun getSpotiflacSession(context: Context, appVersion: String): SpotiflacSession? {
    val p = prefs(context, appVersion)
    // A session for another version of the extension does not sign this one's requests.
    if (p.getString("app_version", "") != appVersion) return null
    val id = p.getString("session_id", null)?.takeIf { it.isNotBlank() } ?: return null
    val secret = p.getString("session_secret", null)?.takeIf { it.isNotBlank() } ?: return null
    return SpotiflacSession(id, secret, spotiflacInstallId(context), p.getString("expires_at", "").orEmpty())
}

/** At least one download server is verified. */
fun hasSpotiflacSession(context: Context): Boolean =
    com.music.spotui.lossless.ZarzSession.Ext.entries.any { getSpotiflacSession(context, it.appVersion) != null }

fun clearSpotiflacSession(context: Context, appVersion: String) {
    prefs(context, appVersion).edit().clear().apply()
}

/** Forgets every session (Settings, "Forget"). */
fun clearSpotiflacSessions(context: Context) {
    com.music.spotui.lossless.ZarzSession.Ext.entries.forEach { clearSpotiflacSession(context, it.appVersion) }
}
