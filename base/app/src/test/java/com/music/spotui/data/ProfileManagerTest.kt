package com.music.spotui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Spotilol's fail-closed profile regressions. */
class ProfileManagerTest {
    private fun preferences(value: () -> String?, onEdit: () -> Unit = {}) = object : ProfileManager.Store {
        override fun read() = value()
        override fun write(value: String) { onEdit(); error("Unexpected write") }
    }

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
