package com.project.lol.security

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The scoped bridge's bounded HTTPS transport. Cookie access stays inside Android's cookie store. */
internal class NativeFetch(
    client: OkHttpClient = OkHttpClient(),
    private val readCookies: (String) -> String? = { null },
    private val storeCookies: (String, List<String>) -> Unit = { _, _ -> },
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    fun execute(url: String, options: String?): String = try {
        require(WebSecurityPolicy.isNativeFetch(url)) { "Untrusted target" }
        require((options?.length ?: 0) <= 1_048_576) { "Request too large" }
        val opts = if (options.isNullOrBlank()) JSONObject() else JSONObject(options)
        val method = opts.optString("method", "GET").uppercase(Locale.ROOT)
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
        val bytes = when {
            opts.has("bodyBase64") -> Base64.getDecoder().decode(opts.getString("bodyBase64"))
            opts.has("body") && !opts.isNull("body") -> opts.getString("body").toByteArray(Charsets.UTF_8)
            else -> null
        }
        require((bytes?.size ?: 0) <= 1_048_576)
        val headers = opts.optJSONObject("headers") ?: JSONObject()
        require(headers.length() <= 64)
        val request = Request.Builder().url(url)
        var headerBytes = 0
        var contentType: String? = null
        headers.keys().forEach { key ->
            val lower = key.lowercase(Locale.ROOT)
            if (lower !in BLOCKED_HEADERS && !lower.startsWith("sec-") && !headers.isNull(key)) {
                val value = headers.getString(key)
                headerBytes += key.length + value.length
                require(headerBytes <= 16_384)
                request.header(key, value)
                if (lower == "content-type") contentType = value
            }
        }
        request.header("User-Agent", DESKTOP_UA)
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua", "\"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
        if (WebSecurityPolicy.isCookieHost(url)) {
            readCookies(url)?.takeIf(String::isNotEmpty)?.let { request.header("Cookie", it) }
        }
        val body = when (method) {
            "GET", "HEAD" -> { require(bytes == null || bytes.isEmpty()); null }
            "POST", "PUT", "PATCH" -> (bytes ?: byteArrayOf()).toRequestBody(contentType?.toMediaTypeOrNull())
            else -> bytes?.toRequestBody(contentType?.toMediaTypeOrNull())
        }
        client.newCall(request.method(method, body).build()).execute().use { response ->
            require(response.code !in setOf(300, 301, 302, 303, 305, 307, 308)) { "Redirect rejected" }
            if (WebSecurityPolicy.isCookieHost(url)) {
                val cookies = response.headers.values("Set-Cookie")
                if (cookies.isNotEmpty()) storeCookies(url, cookies)
            }
            val data = response.body.byteStream().use { BoundedInput.readBytes(it, 4_194_304) }
            val responseHeaders = JSONObject()
            response.headers.names().forEach { key ->
                if (!key.equals("Set-Cookie", true) && !key.equals("Set-Cookie2", true)) {
                    responseHeaders.put(key, response.header(key))
                }
            }
            JSONObject().put("status", response.code).put("headers", responseHeaders).apply {
                if (opts.optBoolean("responseBase64", false)) put("bodyBase64", Base64.getEncoder().encodeToString(data))
                else put("body", data.toString(Charsets.UTF_8))
            }.toString()
        }
    } catch (_: Exception) {
        "{\"status\":0,\"body\":\"Native request rejected or failed\",\"headers\":{}}"
    }

    companion object {
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
        private val BLOCKED_HEADERS = setOf("cookie", "cookie2", "host", "origin", "referer", "connection", "content-length",
            "transfer-encoding", "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade", "accept-encoding", "user-agent", "x-requested-with")
    }
}
