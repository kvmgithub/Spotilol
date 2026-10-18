package com.music.spotui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Private pure helpers are invoked reflectively, preserving the unmodified app sources. */
class YouTubeSourceTest {
    @Test fun titleMatchesIgnoresCasePunctuationAndFeatSuffix() {
        assertTrue(titleMatches("Beyoncé - Halo (Official Music Video)", "HALO"))
        assertTrue(titleMatches("Artist — One More Time (Official Video)", "One More Time"))
        assertTrue(titleMatches("Artist — Stay", "Stay (feat. Guest)"))
        assertTrue(titleMatches("Artist — Stay", "Stay feat. Guest"))
    }

    @Test fun titleMatchesRejectsUnrelatedTitles() {
        assertFalse(titleMatches("Artist — Another Song (Official Video)", "One More Time"))
        assertFalse(titleMatches("A documentary about the artist", "Billie Jean"))
    }

    @Test fun titleMatchesDoesNotUseSubstringsAsWholeWords() {
        assertFalse("Fox is not Foxtrot", titleMatches("Foxtrot (Official Video)", "Fox"))
        assertFalse("Love is not Loveless", titleMatches("Loveless (Official Video)", "Love"))
    }

    @Test fun titleMatchesKeepsElidedWordsWhole() {
        assertTrue(titleMatches("Ikaz Boi - J'SAVAIS (ft. La Fève) (Official Visualizer)", "J'SAVAIS"))
        assertTrue(titleMatches("Ikaz Boi - J’savais", "J'SAVAIS"))
        assertFalse("Si tu savais is another song", titleMatches("Ikaz Boi (ft. Leto) - Si Tu Savais", "J'SAVAIS"))
    }

    @Test fun titleMatchesAllowsCommonAccentDifferences() {
        assertTrue(titleMatches("Artist — Deja Vu (Official Video)", "Déjà Vu"))
    }

    @Test fun normRemovesPresentationLabelsAndFeatMarker() {
        assertEquals("adelehello", norm("ADELE — Hello (Official Music Video)"))
        assertEquals("artistguest", norm("Artist feat. Guest [HD]"))
    }

    @Test fun normPreservesLettersInsideOrdinaryWords() {
        assertEquals("liftyourself", norm("Lift Yourself"))
        assertEquals("draft", norm("Draft"))
        assertEquals("audiobook", norm("Audiobook"))
    }

    @Test fun normTreatsComposedAndUnaccentedNamesEqually() {
        assertEquals(norm("Beyonce"), norm("Beyoncé"))
        assertEquals(norm("Deja Vu"), norm("Déjà Vu"))
    }

    @Test fun looksLikeClipAcceptsEnglishAndFrenchLabels() {
        for (title in listOf("Artist (Official Music Video)", "Artist (Official Video)",
            "Artist (Clip officiel)", "Artist (Vidéo officielle)", "Artist (Vidéoclip)")) {
            assertTrue(title, YouTubeSource.looksLikeClip(title))
        }
    }

    @Test fun looksLikeClipRejectsPlainAudioAndUnmarkedTitles() {
        for (title in listOf("Artist (Official Audio)", "Artist (Lyric Video)", "Artist — Song", "Artist (unofficial video)")) {
            assertFalse(title, YouTubeSource.looksLikeClip(title))
        }
    }

    @Test fun sameArtistAcceptsTopicVevoAndFirstArtist() {
        assertTrue(YouTubeSource.sameArtist(hit("Adele - Topic"), "Adele"))
        assertTrue(YouTubeSource.sameArtist(hit("AdeleVEVO"), "Adele, Guest"))
        assertFalse(YouTubeSource.sameArtist(hit("Other Artist"), "Adele"))
        assertFalse(YouTubeSource.sameArtist(hit(""), "Adele"))
        assertFalse(YouTubeSource.sameArtist(hit("Adele"), ""))
    }

    @Test fun sameArtistRejectsNamesThatOnlyShareAnInternalSubstring() {
        assertFalse("Madeleine is not Adele", YouTubeSource.sameArtist(hit("Madeleine"), "Adele"))
        assertFalse("A is not an identity check for any artist", YouTubeSource.sameArtist(hit("A"), "Adele"))
    }

    @Test fun sameArtistAllowsAccentDifferences() {
        assertTrue(YouTubeSource.sameArtist(hit("BeyonceVEVO"), "Beyoncé"))
    }

    @Test fun videoIdRequiresAnElevenCharacterWatchId() {
        assertEquals("AbCdEfGhI_1", YouTubeSource.videoId("https://www.youtube.com/watch?v=AbCdEfGhI_1&list=other"))
        assertEquals(null, YouTubeSource.videoId("https://www.youtube.com/watch?v=short"))
    }

    private fun hit(uploader: String) = YouTubeSource.Hit("https://www.youtube.com/watch?v=AbCdEfGhI_1", "Song", uploader, 180)
    private fun norm(value: String): String = call("norm", arrayOf(value)) as String
    private fun titleMatches(candidate: String, title: String): Boolean = call("titleMatches", arrayOf(candidate, title)) as Boolean
    private fun call(name: String, arguments: Array<String>): Any? {
        val parameters = Array(arguments.size) { String::class.java }
        val method = YouTubeSource::class.java.getDeclaredMethod(name, *parameters)
        method.isAccessible = true
        return method.invoke(YouTubeSource, *arguments)
    }
}
