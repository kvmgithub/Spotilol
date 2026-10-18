package com.music.spotui.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Spotilol's fail-closed profile regressions. */
class ProfileManagerTest {
    private fun preferences(read: () -> String?, onEdit: () -> Unit = {}): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getString" -> read()
                "edit" -> { onEdit(); error("Unexpected write") }
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences

    @Test fun unreadableEncryptedProfilesCannotBecomeEmptyWriteInput() {
        var writes = 0
        val prefs = preferences({ throw SecurityException("Cannot decrypt") }, { writes++ })
        assertThrows(SecurityException::class.java) { ProfileManager.updateProfiles(prefs) { emptyList() } }
        assertEquals(0, writes)
    }

    @Test fun corruptProfileJsonCannotBeOverwritten() {
        var writes = 0
        val prefs = preferences({ "not-json" }, { writes++ })
        assertThrows(org.json.JSONException::class.java) { ProfileManager.updateProfiles(prefs) { emptyList() } }
        assertEquals(0, writes)
    }

    @Test fun validStoredProfilesRetainTheirSession() {
        val prefs = preferences({ "[{\"name\":\"account\",\"cookies\":\"sp_dc=secret\",\"savedAt\":123}]" })
        val profiles = ProfileManager.readProfiles(prefs)
        assertEquals(1, profiles.size)
        assertEquals("sp_dc=secret", profiles.single().cookies)
    }

    @Test fun profileCookiesOnlyForSpotifyOrigins() {
        assertEquals("sp_dc=x", ProfileManager.parseCookies("{\"https://open.spotify.com\":\"sp_dc=x\"}")!!["https://open.spotify.com"])
        assertNull(ProfileManager.parseCookies("{\"https://open.spotify.com\":\"sp_dc=x\",\"https://evil.test\":\"a=b\"}"))
        assertNull(ProfileManager.parseCookies("{}"))
        assertNull(ProfileManager.parseCookies("not-json"))
    }
}
