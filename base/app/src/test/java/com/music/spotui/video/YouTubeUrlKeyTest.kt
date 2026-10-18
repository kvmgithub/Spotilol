package com.music.spotui.video

import org.junit.Assert.*
import org.junit.Test

class YouTubeUrlKeyTest {
    @Test fun knownUrlFormsAndTrackingQueriesShareCanonicalVideoKey() {
        val id = "AbCdEfGhI_1"
        val expected = "https://www.youtube.com/watch?v=$id|locale=fr-CH"
        for (url in listOf("https://www.youtube.com/watch?v=$id&list=other&t=7", "https://music.youtube.com/watch?v=$id",
            "http://youtube.com/watch?v=$id", "https://youtu.be/$id?si=track", "https://www.youtube.com/shorts/$id", "https://www.youtube.com/embed/$id")) {
            assertEquals(url, expected, YouTubeUrlKey.cacheKey(url, "fr-CH"))
        }
    }
    @Test fun idsAreExactAndAmbiguousOrUnrelatedHostsAreNotMerged() {
        for (url in listOf("https://youtube.com/watch?v=AbCdEfGhI_12", "https://youtube.com/watch?v=short",
            "https://youtube.com/watch?v=AbCdEfGhI_1&v=other000000", "https://evil.example/watch?v=AbCdEfGhI_1",
            "https://youtube.com@evil.example/watch?v=AbCdEfGhI_1", "https://youtube.com:8888/watch?v=AbCdEfGhI_1")) {
            assertNull(url, YouTubeUrlKey.videoId(url)); assertEquals(url, YouTubeUrlKey.canonicalUrl(url))
        }
    }
    @Test fun localeAndDifferentVideosHaveDifferentKeys() {
        val a = "https://youtube.com/watch?v=AbCdEfGhI_1"
        assertNotEquals(YouTubeUrlKey.cacheKey(a, "fr-CH"), YouTubeUrlKey.cacheKey(a, "en-US"))
        assertNotEquals(YouTubeUrlKey.cacheKey(a, "fr-CH"), YouTubeUrlKey.cacheKey("https://youtube.com/watch?v=AbCdEfGhI_2", "fr-CH"))
    }
}
