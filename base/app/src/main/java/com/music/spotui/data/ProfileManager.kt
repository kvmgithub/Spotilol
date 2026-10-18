package com.music.spotui.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotilol's encrypted account profiles: the Spotify session cookies of an account, kept in
 * EncryptedSharedPreferences (Keystore AES-256-GCM) and restored to switch accounts without
 * signing in again.
 *
 * Fail closed, as in Spotilol: keys are never deleted automatically, nothing falls back to
 * plaintext, and an unreadable store is never rewritten as empty.
 */
object ProfileManager {
    data class Profile(val name: String, val cookies: String, val savedAt: Long)

    private const val PREFS = "spotilol_ui_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val TAG = "Myspoti"

    /** The only origins whose cookies are captured or restored. */
    val COOKIE_DOMAINS = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://api-partner.spotify.com",
        "https://gew4-spclient.spotify.com",
        "https://spclient.wg.spotify.com",
        "https://api.spotify.com",
        "https://www.spotify.com",
    )

    @Suppress("DEPRECATION")
    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS, masterKey, context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The saved profiles, or null when the encrypted store cannot be read. */
    fun getProfiles(context: Context): List<Profile>? = runCatching { readProfiles(prefs(context)) }.getOrElse {
        android.util.Log.w(TAG, "encrypted profiles unavailable: ${it.javaClass.simpleName}")
        null
    }

    internal fun readProfiles(storage: SharedPreferences): List<Profile> {
        val raw = storage.getString(KEY_PROFILES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            val cookies = o.getString("cookies")
            require(name.isNotBlank() && cookies.isNotBlank()) { "Invalid stored profile" }
            Profile(name, cookies, o.optLong("savedAt", 0L))
        }
    }

    /** Saves (or replaces by name) a profile. Throws when the store cannot be written. */
    fun saveProfile(context: Context, name: String, cookies: String) {
        val trimmed = name.trim().take(64)
        require(trimmed.isNotEmpty() && cookies.isNotEmpty())
        updateProfiles(prefs(context)) { profiles ->
            listOf(Profile(trimmed, cookies, System.currentTimeMillis())) + profiles.filterNot { it.name == trimmed }
        }
        android.util.Log.i(TAG, "profile saved")
    }

    fun deleteProfile(context: Context, name: String) {
        updateProfiles(prefs(context)) { profiles -> profiles.filterNot { it.name == name } }
        android.util.Log.i(TAG, "profile deleted")
    }

    internal fun updateProfiles(storage: SharedPreferences, update: (List<Profile>) -> List<Profile>) {
        val profiles = update(readProfiles(storage)) // Never reinterpret unreadable records as empty.
        val arr = JSONArray()
        profiles.forEach { p -> arr.put(JSONObject().put("name", p.name).put("cookies", p.cookies).put("savedAt", p.savedAt)) }
        check(storage.edit().putString(KEY_PROFILES, arr.toString()).commit()) { "Cannot save encrypted profiles" }
    }

    /** The current session's cookies as JSON (origin to cookie header), or null without sp_dc. */
    fun captureSession(): String? {
        val map = JSONObject()
        var hasSpDc = false
        for (domain in COOKIE_DOMAINS) {
            val cookies = CookieManager.getInstance().getCookie(domain)
            if (!cookies.isNullOrBlank()) {
                map.put(domain, cookies)
                if (cookies.split(';').any { it.trim().startsWith("sp_dc=") }) hasSpDc = true
            }
        }
        if (!hasSpDc) {
            android.util.Log.w(TAG, "capture session: no auth cookie (${map.length()} origins)")
            return null
        }
        return map.toString()
    }

    /** A stored profile's cookies by origin, refusing any origin outside [COOKIE_DOMAINS]. */
    fun parseCookies(json: String): Map<String, String>? = runCatching {
        val map = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        map.keys().forEach { domain ->
            require(domain in COOKIE_DOMAINS) { "Untrusted profile cookie domain" }
            out[domain] = map.getString(domain)
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
