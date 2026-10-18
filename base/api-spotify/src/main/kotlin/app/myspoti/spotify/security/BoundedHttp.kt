package app.myspoti.spotify.security

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Spotilol's native transport rules: no redirects, bounded time, bounded responses. */
object BoundedHttp {
    const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    /** [base] without redirects (a 3xx comes back as is) and with a total call deadline. */
    fun client(base: OkHttpClient = OkHttpClient(), callTimeoutSeconds: Long = 45): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    fun readBytes(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "negative byte limit" }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val count = input.read(buf)
            if (count == -1) break
            if (count > maxBytes - out.size()) throw IOException("Response exceeds byte limit")
            out.write(buf, 0, count)
        }
        return out.toByteArray()
    }

    /** The body as UTF-8 text, refused above [maxBytes]. */
    fun text(body: ResponseBody, maxBytes: Int = MAX_RESPONSE_BYTES): String =
        body.byteStream().use { String(readBytes(it, maxBytes), Charsets.UTF_8) }
}
