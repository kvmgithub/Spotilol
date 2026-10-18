package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import com.music.spotui.data.entity.Lyrics

/** Invokes the real private parser, without changing application visibility. */
class LyricsApiAuditTest {
    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): List<LyricLine> =
        LyricsApi::class.java.getDeclaredMethod("parseLrc", String::class.java).let {
            it.isAccessible = true
            it.invoke(LyricsApi, text) as List<LyricLine>
        }

    @Test fun fractionalSecondsOneTwoThreeDigits() {
        assertEquals(listOf(1200L, 1230L, 1234L, 2000L),
            parse("[00:01.2] a\n[00:01.23] b\n[00:01.234] c\n[00:02] d").map { it.timeMs })
    }

    @Test fun multipleTagsProduceChronologicallySortedCopies() {
        val lines = parse("[01:02.50][00:03.100] Refrain\n[00:02] Avant")
        assertEquals(listOf(2000L, 3100L, 62500L), lines.map { it.timeMs })
        assertEquals(listOf("Avant", "Refrain", "Refrain"), lines.map { it.text })
    }

    @Test fun untaggedMetadataAndBlankLinesAreIgnored() {
        assertEquals(emptyList<LyricLine>(), parse("[ar:Artist]\n[ti:Song]\n\nplain text"))
    }

    @Test fun consecutiveInstrumentalLinesCollapseWithoutRemovingLaterInstrumental() {
        assertEquals(listOf("♪", "Hello", "♪"),
            parse("[00:01]\n[00:02] \n[00:03] Hello\n[00:04]\n[00:05]").map { it.text })
    }

    @Test fun minuteBoundaryAndColonFraction() {
        assertEquals(listOf(59999L, 60000L, 60300L),
            parse("[00:59.999] a\n[01:00] b\n[01:00:3] c").map { it.timeMs })
    }

    @Test fun plausibleExactAndRemasterWithinThreeSeconds() {
        assertTrue(LyricsApi.isPlausibleMatch("Hello - 2011 Remaster", "Adele", 203, "Hello", "Adele", 200))
        assertFalse(LyricsApi.isPlausibleMatch("Hello", "Adele", 204, "Hello", "Adele", 200))
    }

    @Test fun unrelatedTitleAndArtistAreRejected() {
        assertFalse(LyricsApi.isPlausibleMatch("Hello", "Lionel Richie", 200, "Hello", "Adele", 200))
        assertFalse(LyricsApi.isPlausibleMatch("Skyfall", "Adele", 200, "Hello", "Adele", 200))
    }

    @Test fun unknownDurationDoesNotRejectOtherwiseMatchingSong() {
        assertTrue(LyricsApi.isPlausibleMatch("Hello", "Adele", 0, "Hello", "Adele", 200))
    }

    // Regression expectations: failures reveal unsafe substring matching.
    @Test fun differentArtistWithSharedPrefixIsRejected() {
        assertFalse("Cher and Cherry are distinct artists",
            LyricsApi.isPlausibleMatch("Hello", "Cherry", 200, "Hello", "Cher", 200))
    }

    @Test fun distinctSongWithSharedTitlePrefixIsRejected() {
        assertFalse("Hello and Hello Again are distinct songs",
            LyricsApi.isPlausibleMatch("Hello Again", "Adele", 200, "Hello", "Adele", 200))
    }

    /** Uses a controlled real Deferred; never contacts a lyrics provider. */
    @Test fun cancellingOneWaiterMustNotEvictStillRunningSharedRequest() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val requests = LyricsApi::class.java.getDeclaredField("inFlight").let {
            it.isAccessible = true
            it.get(LyricsApi) as ConcurrentHashMap<String, Deferred<Lyrics?>>
        }
        val track = LyricsApi.Track("audit-controlled-cancellation", "test", "artist", "", 200_000)
        val deferred = CompletableDeferred<Lyrics?>()
        val previous = requests.put(track.key, deferred)
        try {
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { LyricsApi.fetch(track) }
            waiter.cancelAndJoin()
            assertSame("A cancelled UI waiter must not remove an unfinished shared provider request", deferred, requests[track.key])
        } finally {
            deferred.cancel()
            if (previous == null) requests.remove(track.key) else requests[track.key] = previous
        }
    }
}
