package com.music.spotui.video

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded copying, with the active HTTP request cancelled on track/background changes. */
internal object RangeAudioDownload {
    suspend fun fetch(client: OkHttpClient, url: String, out: File, headers: Map<String, String>) {
        val parsed = url.toHttpUrl()
        val length = parsed.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
        out.outputStream().use { output ->
            var start = 0L
            val chunk = 4L shl 20
            while (true) {
                currentCoroutineContext().ensureActive()
                val end = if (length != null) minOf(length, start + chunk) - 1 else start + chunk - 1
                val rangeUrl = parsed.newBuilder().setQueryParameter("range", "$start-$end").build()
                val request = Request.Builder().url(rangeUrl)
                    .apply { headers.forEach { (key, value) -> header(key, value) } }.build()
                val count = copyRange(client.newCall(request), output)
                if (start == 0L && count < 1024) throw IOException("Audio response too short")
                start += count
                if (count == 0L || (length != null && start >= length) || (length == null && count < chunk)) break
            }
        }
    }

    private suspend fun copyRange(call: Call, output: OutputStream): Long = suspendCancellableCoroutine { pending ->
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.isActive) pending.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val count = response.use { r ->
                        if (!r.isSuccessful) throw IOException("Audio HTTP ${r.code}")
                        val body = r.body ?: throw IOException("Empty audio response")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            while (pending.isActive) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                copied += n
                            }
                            copied
                        }
                    }
                    if (pending.isActive) pending.resume(count)
                } catch (t: Exception) {
                    if (pending.isActive) pending.resumeWithException(t)
                }
            }
        })
    }
}
