package com.music.spotui.lossless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

private fun hit(id: String, title: String, artist: String, duration: Int, isrc: String = "") =
    LosslessSource.TidalHit(id, title, artist, duration, isrc)

/** The exact-recording rules: ISRC first, otherwise nothing but the same song wins. */
class LosslessMatchTest {

    @Test
    fun picksByExactIsrc() {
        val hits = listOf(hit("1", "Wrong", "A", 300, "GBXXX2400001"))
        assertEquals("1", LosslessSource.pickHit(hits, "GBXXX2400001", "Real Title", "Artist", 200)?.id)
    }

    @Test
    fun withoutIsrcPicksSameTitleArtistDuration() {
        val hits = listOf(
            hit("1", "Real Title", "Artist", 201),   // within 3 s
            hit("2", "Real Title (Live)", "Artist", 200), // live take never wins
            hit("3", "Real Title", "Someone", 200),
        )
        assertEquals("1", LosslessSource.pickHit(hits, "", "Real Title", "Artist", 200)?.id)
    }

    @Test
    fun rejectsRemixCoverAndOffDuration() {
        val hits = listOf(
            hit("1", "Real Title - Remix", "Artist", 200),
            hit("2", "Other Song", "Artist", 200),
            hit("3", "Real Title", "Artist", 310), // 110 s off
        )
        assertNull(LosslessSource.pickHit(hits, "", "Real Title", "Artist", 200))
    }

    @Test
    fun isrcBeatsTitleOnlyWhenSameRecording() {
        val hits = listOf(
            hit("1", "Real Title", "Artist", 200, "GBUM71705174"),
            hit("2", "Real Title", "Artist", 200, "USUM71712345"),
        )
        // The track's own ISRC decides which of the same-named candidates wins.
        assertEquals("1", LosslessSource.pickHit(hits, "GBUM71705174", "Real Title", "Artist", 200)?.id)
    }
}

/** The FLAC STREAMINFO read that rejects 30 s previews handed out by backends. */
class FlacDurationTest {

    private fun fakeFlac(sampleRate: Int = 44_100, samples: Long = sampleRate * 200L): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray().copyInto(head)
        // STREAMINFO fields a real FLAC carries, packed as the parser expects:
        // 20 bits sample rate | 3 channels | 5 bits/sample | 36-bit total samples.
        val packed = (sampleRate.toLong() and 0xFFFFF) shl 44 or
            (2L shl 41) or // stereo
            (16L shl 36) or ((samples and 0xFFFFFFFFL))
        for (i in 0 until 8) head[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        return head
    }

    @Test
    fun readsTheDuration() {
        val head = fakeFlac(44_100, 44_100L * 210)
        assertEquals(210, LosslessSource.flacDurationSec(head))
    }

    @Test
    fun notFlacOrTooShortIsNull() {
        assertNull(LosslessSource.flacDurationSec(ByteArray(42)))
        assertNull(LosslessSource.flacDurationSec(ByteArray(20)))
    }

    @Test
    fun aTitleMatchIsNotAnIsrcMatch() {
        assertTrue(LosslessSource.isrcMatches("usum71703861", "USUM71703861"))
        // Same song name, another recording (live, remaster): its own ISRC.
        assertFalse(LosslessSource.isrcMatches("GBUM71703999", "USUM71703861"))
        // Spotify gave no ISRC: nothing can match it.
        assertFalse(LosslessSource.isrcMatches("USUM71703861", ""))
    }

    @Test
    fun readsTheOneFileOfAnAacManifest() {
        fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
        assertEquals("https://cdn/x.mp4", LosslessSource.aacManifestUrl(b64("""{"mimeType":"audio/mp4","codecs":"mp4a.40.5","urls":["https://cdn/x.mp4"]}""")))
        // A FLAC or a DASH manifest is not the small AAC file.
        assertNull(LosslessSource.aacManifestUrl(b64("""{"mimeType":"audio/flac","urls":["https://cdn/x.flac"]}""")))
        assertNull(LosslessSource.aacManifestUrl(b64("<MPD></MPD>")))
    }
}
