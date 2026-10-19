package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B25: the profile endpoints hand out `spotify:image:<id>` identifiers rather than a picture's
 * address; the image loader was given them as they were and kept its grey placeholder, so a user's
 * profile picture and their public playlists never showed. These cases pin what the fix promises:
 * an identifier becomes the URL it stands for, and anything already usable is left alone.
 */
class SpotifyImagesTest {
    @Test fun identifierBecomesTheUrlItStandsFor() =
        assertEquals("https://i.scdn.co/image/ab67616d0000b273", spotifyImageUrl("spotify:image:ab67616d0000b273"))

    @Test fun aRealUrlPassesThrough() =
        assertEquals("https://i.scdn.co/image/abc123", spotifyImageUrl("https://i.scdn.co/image/abc123"))

    @Test fun missingStaysMissing() {
        assertNull(spotifyImageUrl(null))
        assertNull(spotifyImageUrl(""))
        assertNull(spotifyImageUrl("   "))
    }

    @Test fun anythingElseIsUntouched() =
        assertEquals("spotify:track:4cOdK2wGLETKBW3PvgPWqT", spotifyImageUrl("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))
}
