package com.music.spotui.data.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure checks that decide whether a publisher's file is kept (no network, no Android). */
class PodcastDownloadTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun mp3WithId3TagIsAccepted() {
        assertTrue(PodcastDownloader.audioMagicOk(ascii("ID3") + bytes(4, 0, 0)))
    }

    @Test fun bareMpegFrameSyncIsAccepted() {
        assertTrue(PodcastDownloader.audioMagicOk(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertTrue(PodcastDownloader.audioMagicOk(bytes(0xFF, 0xF3, 0x00, 0x00)))
        assertTrue(PodcastDownloader.audioMagicOk(bytes(0xFF, 0xF2, 0x00, 0x00)))
    }

    @Test fun mp4ContainerIsAccepted() {
        assertTrue(PodcastDownloader.audioMagicOk(bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")))
    }

    @Test fun htmlErrorPageIsRefused() {
        assertFalse(PodcastDownloader.audioMagicOk(ascii("<!DOCTYPE ht")))
        assertFalse(PodcastDownloader.audioMagicOk(ascii("<htm")))
    }

    @Test fun contentTypes() {
        assertTrue(PodcastDownloader.contentTypeOk("audio/mpeg"))
        assertTrue(PodcastDownloader.contentTypeOk("audio/mpeg; charset=utf-8"))
        assertTrue(PodcastDownloader.contentTypeOk("audio/mp4"))
        assertTrue(PodcastDownloader.contentTypeOk("application/octet-stream"))
        assertTrue(PodcastDownloader.contentTypeOk("audio/x-m4a"))
        // Unknown: the magic bytes decide (some podcast hosts send no type at all).
        assertTrue(PodcastDownloader.contentTypeOk(""))
        // A page or a JSON error served with 200 is a broken link, not an episode.
        assertFalse(PodcastDownloader.contentTypeOk("text/html; charset=utf-8"))
        assertFalse(PodcastDownloader.contentTypeOk("application/json"))
    }

    @Test fun extensionFollowsTheContainerNotTheUrl() {
        val mp3 = ascii("ID3")
        val m4a = bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")
        // A ".mp3" URL that actually serves an MP4 is stored as .m4a.
        assertEquals("m4a", PodcastDownloader.audioExtension("https://x/y/episode.mp3?token=1", m4a))
        assertEquals("mp3", PodcastDownloader.audioExtension("https://dts.podtrac.com/redirect.mp3/a/b?x=1", mp3))
        assertEquals("m4a", PodcastDownloader.audioExtension("https://x/y.m4a", mp3))
        // No usable extension: MP3 is the publisher default.
        assertEquals("mp3", PodcastDownloader.audioExtension("https://radiofrance.fr/stream", mp3))
    }

    @Test fun keyGuardSeparatesEpisodesFromTracks() {
        assertTrue(isEpisodeKey("episode:2dKPfHhKH0NgXGPKdCa0lA"))
        assertFalse(isEpisodeKey("3n3Ppam7vgaVa1iaRUc9Lp"))
        assertEquals("episode_2dKPfHhKH0NgXGPKdCa0lA", PodcastDownloader.safeName("episode:2dKPfHhKH0NgXGPKdCa0lA"))
        assertEquals("episode_abc", PodcastDownloader.safeName("episode:abc"))
    }

    @Test fun queuedEpisodePersistsAndComesBack() {
        val r = PodcastDownloader.Request("episode:abc", "Title", "Show", "Show", "https://c/1.jpg", 1_800_000L)
        val back = PodcastDownloader.Request.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r, back)
    }

    @Test fun podcastIndexEntriesAreTaggedAndOldOnesAreMusic() {
        val json = JSONObject().put("id", "episode:abc").put("file", "episode_abc.mp3")
        val fresh = OfflineTrack.fromJson(json.put("kind", OfflineTrack.KIND_PODCAST))
        assertTrue(fresh.isEpisode)
        assertEquals(OfflineTrack.KIND_PODCAST, fresh.kind)
        // An index written before podcasts has no "kind": those entries must stay music.
        val old = OfflineTrack.fromJson(JSONObject().put("id", "abc").put("file", "abc.flac"))
        assertFalse(old.isEpisode)
        assertEquals(OfflineTrack.KIND_MUSIC, old.kind)
        assertEquals(OfflineTrack.KIND_PODCAST, fresh.toJson().getString("kind"))
    }
}
