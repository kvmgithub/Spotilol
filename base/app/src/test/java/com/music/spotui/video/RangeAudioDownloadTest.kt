package com.music.spotui.video

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RangeAudioDownloadTest {
    @Test fun streamsRangedResponseToDiskAndPreservesHeaders() = runBlocking {
        val data = ByteArray(131072) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var range = ""; var header = ""
        server.createContext("/audio") { exchange ->
            range = exchange.requestURI.rawQuery; header = exchange.requestHeaders.getFirst("X-Test")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        val out = File.createTempFile("audio-range", ".bin"); val client = OkHttpClient()
        try {
            RangeAudioDownload.fetch(client, "http://127.0.0.1:${server.address.port}/audio?clen=${data.size}", out, mapOf("X-Test" to "kept"))
            assertArrayEquals(data, out.readBytes()); assertTrue(range.contains("range=0-131071")); assertEquals("kept", header)
        } finally { out.delete(); server.stop(0); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
    @Test fun cancelledTrackStopsAnActiveResponseInsteadOfWaitingForTimeout() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100_000)
                exchange.responseBody.write(ByteArray(2048)); exchange.responseBody.flush(); entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            } catch (_: Exception) {} finally { exchange.close() }
        }
        server.start()
        val out = File.createTempFile("audio-cancel", ".bin"); val client = OkHttpClient()
        try {
            val job = launch(Dispatchers.IO) { RangeAudioDownload.fetch(client, "http://127.0.0.1:${server.address.port}/slow?clen=100000", out, emptyMap()) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(out.length() < 100_000)
        } finally { release.countDown(); server.stop(0); out.delete(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
