package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloaderTest {
    @Test fun contentRangeTotalReadsTheFullSize() {
        assertEquals(1234L, OfflineDownloader.contentRangeTotal("bytes 0-99/1234"))
        assertNull(OfflineDownloader.contentRangeTotal("bytes 0-99/*"))
        assertNull(OfflineDownloader.contentRangeTotal(null))
    }

    @Test fun normalizeIsrcAcceptsDashesAndCase() {
        assertEquals("USUM71703861", OfflineDownloader.normalizeIsrc("us-um7-17-03861"))
        assertEquals("FRZ039800212", OfflineDownloader.normalizeIsrc("FR Z03 98 00212"))
    }

    @Test fun normalizeIsrcRejectsOtherIds() {
        assertNull(OfflineDownloader.normalizeIsrc(""))
        assertNull(OfflineDownloader.normalizeIsrc("0602557362806")) // a UPC
        assertNull(OfflineDownloader.normalizeIsrc("USUM7170386"))   // one digit short
    }

    private fun hit(url: String, title: String, artist: String, sec: Long) = OfflineDownloader.PreviewHit(url, title, artist, sec)

    @Test fun pickPreviewKeepsTheSameSong() {
        val results = listOf(
            hit("https://cdn/live.mp3", "Paranoïa (Live)", "MadeInParis", 312),  // live take, much longer
            hit("", "Paranoïa", "MadeInParis", 163),                             // no preview
            hit("https://cdn/other.mp3", "Typical", "Al-Walid", 163),            // same length, other song
            hit("https://cdn/album.mp3", "Paranoia", "MadeInParis", 164),
        )
        assertEquals("https://cdn/album.mp3", OfflineDownloader.pickPreview(results, "Paranoïa", "MadeInParis", 163_400))
    }

    @Test fun pickPreviewIgnoresFeatAndRemasterSuffixes() {
        val results = listOf(hit("https://cdn/a.mp3", "Yellow Submarine (Remastered 2009)", "The Beatles", 157))
        assertEquals("https://cdn/a.mp3", OfflineDownloader.pickPreview(results, "Yellow Submarine - Remastered 2009", "The Beatles", 158_000))
        val feat = listOf(hit("https://cdn/b.mp3", "Stay (feat. Guest)", "Artist", 200))
        assertEquals("https://cdn/b.mp3", OfflineDownloader.pickPreview(feat, "Stay", "Artist", 200_000))
    }

    @Test fun pickPreviewRejectsAnotherArtistOrDuration() {
        val results = listOf(hit("https://cdn/a.mp3", "GO", "Someone Else", 178))
        assertNull(OfflineDownloader.pickPreview(results, "GO", "Slimka", 178_000))
        assertNull(OfflineDownloader.pickPreview(listOf(hit("https://cdn/a.mp3", "GO", "Slimka", 178)), "GO", "Slimka", 0))
        assertNull(OfflineDownloader.pickPreview(listOf(hit("https://cdn/a.mp3", "GO", "Slimka", 178)), "GO", "Slimka", 190_000))
    }

    @Test fun localIdsNeverCollideWithMediaStoreIds() {
        for (id in listOf("4uLU6hMCjMI75M1A2tKUQC", "0", "", "zzzzzzzzzzzzzzzzzzzzzz")) {
            assertTrue(OfflineStore.localIdOf(id) < 0)
        }
    }
}
