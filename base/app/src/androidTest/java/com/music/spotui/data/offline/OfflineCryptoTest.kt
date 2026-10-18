package com.music.spotui.data.offline

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineCryptoTest {
    @Test fun encryptsAndReadsBackFromAnyPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = Random(7).nextBytes(1_000_003)
        val file = File(context.cacheDir, "crypto-test.flac").apply { writeBytes(data) }
        OfflineCrypto.encryptInPlace(context, file)
        assertTrue(OfflineCrypto.isEncrypted(file))
        assertFalse(file.readBytes().copyOfRange(OfflineCrypto.HEADER, OfflineCrypto.HEADER + 64)
            .contentEquals(data.copyOfRange(0, 64)))

        val source = OfflineCrypto.Factory(context).createDataSource()
        for (pos in listOf(0L, 1L, 15L, 16L, 17L, 4095L, 500_000L, 999_990L)) {
            val n = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(pos).build()).toInt()
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = source.read(out, read, minOf(8192, n - read))
                if (r < 0) break
                read += r
            }
            source.close()
            assertArrayEquals("position $pos", data.copyOfRange(pos.toInt(), data.size), out)
        }
        file.delete()
    }

    /** An encrypted FLAC plays: the player finds the format and the length through the decrypting source. */
    @Test fun playerReadsEncryptedFlac() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val file = File(context.cacheDir, "crypto-play.flac")
        inst.context.assets.open("tone16.flac").use { i -> file.outputStream().use { i.copyTo(it) } }
        OfflineCrypto.encryptInPlace(context, file)
        val latch = java.util.concurrent.CountDownLatch(1)
        var duration = 0L
        var error: Throwable? = null
        inst.runOnMainSync {
            val p = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(OfflineCrypto.Factory(context)))
                .build()
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY) { duration = p.duration; p.release(); latch.countDown() }
                }
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e; p.release(); latch.countDown() }
            })
            p.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
        }
        assertTrue("timed out", latch.await(20, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("error: $error", error == null && duration > 0)
        file.delete()
    }
}
