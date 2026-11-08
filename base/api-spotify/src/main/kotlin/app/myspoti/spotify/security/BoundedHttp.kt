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

    /** Shared by every client built here, so a network change can drop all dead connections. */
    val pool = okhttp3.ConnectionPool()

    /** A connection the app does not allow right now (the data saver on a metered network). */
    class Refused : IOException("Data saver: no mobile data")

    /** Set by the app: true while no connection may go out. Every client built here asks it per request. */
    @Volatile var refuse: () -> Boolean = { false }

    /** [base] without redirects (a 3xx comes back as is) and with a total call deadline. */
    fun client(base: OkHttpClient = OkHttpClient(), callTimeoutSeconds: Long = 45): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .addInterceptor { chain -> if (refuse()) throw Refused(); chain.proceed(chain.request()) }
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
