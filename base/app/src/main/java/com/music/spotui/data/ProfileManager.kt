package com.music.spotui.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotilol's encrypted account profiles: the Spotify session cookies of an account, sealed with
 * an AES-256-GCM key of the Android Keystore and restored to switch accounts without signing in
 * again.
 *
 * Until 1.2.0 they were kept in EncryptedSharedPreferences (androidx security-crypto, deprecated
 * with 1.1.0 in favour of the Keystore itself). That store is read once, copied here and then
 * removed; security-crypto stays only for that read.
 *
 * Fail closed, as in Spotilol: keys are never deleted automatically, nothing falls back to
 * plaintext, and an unreadable store is never rewritten as empty.
 */
object ProfileManager {
    data class Profile(val name: String, val cookies: String, val savedAt: Long)

    /** Where the profiles' JSON is read from and written to; sealed on the device ([SealedStore]). */
    internal interface Store {
        fun read(): String?
        fun write(value: String)
    }

    /** The pre-1.2.0 EncryptedSharedPreferences file. */
    private const val LEGACY_PREFS = "spotilol_ui_profiles"
    private const val PREFS = "spotilol_ui_profiles_v2"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ALIAS = "spotilol_ui_profiles"
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

    /**
     * The profiles' JSON in a private preferences file, as `v1:<iv>:<ciphertext>` (Base64), sealed
     * with [KEY_ALIAS]. A read never creates the key: a stored value without its key fails.
     */
    private class SealedStore(private val prefs: SharedPreferences) : Store {
        private val aad = "$PREFS/$KEY_PROFILES".toByteArray()

        fun isEmpty() = !prefs.contains(KEY_PROFILES)

        override fun read(): String? {
            val sealed = prefs.getString(KEY_PROFILES, null) ?: return null
            val parts = sealed.split(':')
            require(parts.size == 3 && parts[0] == "v1") { "Unknown sealed profile format" }
            val key = key(create = false) ?: throw SecurityException("Profile key missing")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
            cipher.updateAAD(aad)
            return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
        }

        override fun write(value: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // The Keystore picks the IV (randomized encryption is required for this key).
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(aad)
            val sealed = cipher.doFinal(value.toByteArray())
            val stored = "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
            check(prefs.edit().putString(KEY_PROFILES, stored).commit()) { "Cannot save encrypted profiles" }
        }

        private fun key(create: Boolean): SecretKey? {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            if (!create) return null
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    /**
     * The sealed store, after moving a pre-1.2.0 one into it: the old store is read whole (any
     * failure throws and leaves it as it is), checked, written here, and only then removed.
     */
    @Synchronized
    private fun store(context: Context): Store {
        val app = context.applicationContext
        val store = SealedStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        // Checked as a file: EncryptedSharedPreferences.create would make an empty one.
        if (File(app.dataDir, "shared_prefs/$LEGACY_PREFS.xml").exists()) {
            // The new store counts only once it reads back (its key there, its value whole): until
            // then the old one is the profiles' only copy, moved again rather than deleted.
            val moved = !store.isEmpty() && runCatching { store.read()?.let(::parseProfiles) }.isSuccess
            if (!moved) {
                val raw = legacyPrefs(app).getString(KEY_PROFILES, null)
                if (raw != null) {
                    parseProfiles(raw)
                    store.write(raw)
                }
                android.util.Log.i(TAG, "profiles moved to the Android Keystore")
            }
            // Also a move cut off between the write and this delete: finished at the next start.
            app.deleteSharedPreferences(LEGACY_PREFS)
        }
        return store
    }

    @Suppress("DEPRECATION")
    private fun legacyPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            LEGACY_PREFS, masterKey, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The saved profiles, or null when the encrypted store cannot be read. */
    @Synchronized
    fun getProfiles(context: Context): List<Profile>? = runCatching { readProfiles(store(context)) }.getOrElse {
        android.util.Log.w(TAG, "encrypted profiles unavailable: ${it.javaClass.simpleName}")
        null
    }

    internal fun readProfiles(storage: Store): List<Profile> = parseProfiles(storage.read())

    private fun parseProfiles(raw: String?): List<Profile> {
        raw ?: return emptyList()
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
    @Synchronized
    fun saveProfile(context: Context, name: String, cookies: String) {
        val trimmed = name.trim().take(64)
        require(trimmed.isNotEmpty() && cookies.isNotEmpty())
        updateProfiles(store(context)) { profiles ->
            listOf(Profile(trimmed, cookies, System.currentTimeMillis())) + profiles.filterNot { it.name == trimmed }
        }
        android.util.Log.i(TAG, "profile saved")
    }

    @Synchronized
    fun deleteProfile(context: Context, name: String) {
        updateProfiles(store(context)) { profiles -> profiles.filterNot { it.name == name } }
        android.util.Log.i(TAG, "profile deleted")
    }

    internal fun updateProfiles(storage: Store, update: (List<Profile>) -> List<Profile>) {
        val profiles = update(readProfiles(storage)) // Never reinterpret unreadable records as empty.
        val arr = JSONArray()
        profiles.forEach { p -> arr.put(JSONObject().put("name", p.name).put("cookies", p.cookies).put("savedAt", p.savedAt)) }
        storage.write(arr.toString())
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
