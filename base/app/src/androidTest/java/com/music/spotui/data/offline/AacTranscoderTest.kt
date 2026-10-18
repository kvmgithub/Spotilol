package com.music.spotui.data.offline

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The "smallest" downloads: a FLAC becomes a playable AAC file of the same length, on the phone's codecs. */
@RunWith(AndroidJUnit4::class)
class AacTranscoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): File = File(context.cacheDir, name).also { f ->
        testAssets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    private fun check(name: String) {
        val input = asset(name)
        val out = File(context.cacheDir, "$name.m4a").apply { delete() }
        AacTranscoder.transcode(input, out)
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        try {
            val f = ex.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, f.getString(MediaFormat.KEY_MIME))
            assertEquals(2, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val seconds = f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            assertTrue("duration $seconds", seconds in 5.7..6.3)
            // 96 kbit/s for 6 s is about 72 kB; far below the FLAC.
            assertTrue("size ${out.length()}", out.length() in 40_000L..110_000L)
        } finally {
            ex.release()
        }
    }

    @Test fun cdFlacBecomesAac() = check("tone16.flac")

    @Test fun hiResFlacBecomesAac() = check("tone24.flac")
}
