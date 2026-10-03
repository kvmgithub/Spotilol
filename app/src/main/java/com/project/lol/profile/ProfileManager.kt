package com.project.lol.profile

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.project.lol.util.Logger
import com.project.lol.security.WebSecurityPolicy
import org.json.JSONArray
import org.json.JSONObject

object ProfileManager {

    data class Profile(
        val name: String,
        val cookies: String,
        val savedAt: Long
    )

    private const val PREFS = "spotilol_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val TAG = "profiles"

    private val COOKIE_DOMAINS = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://api-partner.spotify.com",
        "https://gew4-spclient.spotify.com",
        "https://spclient.wg.spotify.com",
        "https://api.spotify.com",
        "https://www.spotify.com"
    )

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS, masterKey, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getProfiles(context: Context): List<Profile> = runCatching { readProfiles(prefs(context)) }.getOrElse {
        Logger.w(TAG, "Encrypted profiles unavailable")
        emptyList()
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

    fun saveProfile(context: Context, name: String, cookies: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && cookies.isNotEmpty())
        updateProfiles(prefs(context)) { profiles ->
            listOf(Profile(trimmed, cookies, System.currentTimeMillis())) + profiles.filterNot { it.name == trimmed }
        }
        Logger.i(TAG, "profile saved")
    }

    fun deleteProfile(context: Context, name: String) {
        updateProfiles(prefs(context)) { profiles -> profiles.filterNot { it.name == name } }
        Logger.i(TAG, "profile deleted")
    }

    internal fun updateProfiles(storage: SharedPreferences, update: (List<Profile>) -> List<Profile>) {
        val profiles = update(readProfiles(storage)) // Never reinterpret unreadable records as empty.
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("cookies", p.cookies).put("savedAt", p.savedAt))
        }
        check(storage.edit().putString(KEY_PROFILES, arr.toString()).commit()) { "Cannot save encrypted profiles" }
    }

    fun captureSession(context: Context): String? {
        val map = JSONObject()
        var hasSpDc = false
        for (domain in COOKIE_DOMAINS) {
            val cookies = CookieManager.getInstance().getCookie(domain)
            if (!cookies.isNullOrBlank()) {
                map.put(domain, cookies)
                if (cookies.contains("sp_dc=")) hasSpDc = true
            }
        }
        if (!hasSpDc) {
            Logger.w(TAG, "capture session: no sp_dc cookie, domains=${map.length()}")
            return null
        }
        Logger.i(TAG, "captured session from ${map.length()} domains")
        return map.toString()
    }

    fun applyProfile(context: Context, json: String): Boolean {
        val entries = try {
            val map = JSONObject(json)
            val out = mutableListOf<Pair<String, String>>()
            val keys = map.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                require(domain in COOKIE_DOMAINS) { "Untrusted profile cookie domain" }
                out.add(domain to map.getString(domain))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
        if (entries.isEmpty()) return false

        Logger.i(TAG, "applying session over ${entries.size} domains")
        CookieManager.getInstance().removeAllCookies {
            for ((domain, cookies) in entries) {
                for (pair in cookies.split(";")) {
                    val cookie = pair.trim()
                    WebSecurityPolicy.restoreCookie(cookie)?.let {
                        CookieManager.getInstance().setCookie(domain, it)
                    }
                }
            }
            CookieManager.getInstance().flush()
            context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("LoggedIn", true)
                .apply()
        }
        return true
    }
}
