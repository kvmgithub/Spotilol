package com.project.lol.security

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeFetchTest {
    private fun client(block: (Interceptor.Chain) -> Response) =
        OkHttpClient.Builder().addInterceptor(Interceptor { block(it) }).build()

    private fun response(chain: Interceptor.Chain, code: Int = 200, body: ByteArray = "ok".toByteArray()) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody()).build()

    @Test fun rejectsUntrustedTargetsBeforeCallingTransport() {
        var called = false
        val fetch = NativeFetch(client { called = true; response(it) })
        for (url in listOf("https://evilspotify.com/", "https://127.0.0.1/", "http://open.spotify.com/", "https://api.spotify.com:8443/")) {
            assertEquals(0, JSONObject(fetch.execute(url, null)).getInt("status"))
        }
        assertFalse(called)
    }

    @Test fun neverReturnsSessionCookiesToJavaScript() {
        val stored = mutableListOf<String>()
        val fetch = NativeFetch(client { response(it).newBuilder().addHeader("Set-Cookie", "sp_dc=secret; HttpOnly").build() },
            storeCookies = { _, values -> stored.addAll(values) })
        val result = JSONObject(fetch.execute("https://open.spotify.com/", null))
        assertEquals(200, result.getInt("status"))
        assertFalse(result.getJSONObject("headers").toString().contains("secret"))
        assertEquals(listOf("sp_dc=secret; HttpOnly"), stored)
    }

    @Test fun stripsInjectedTransportHeadersAndScopesCookieAccess() {
        var cookieReads = 0
        val fetch = NativeFetch(client { chain ->
            assertNull(chain.request().header("Cookie"))
            assertEquals("https://open.spotify.com", chain.request().header("Origin"))
            assertEquals("Bearer api-token", chain.request().header("Authorization"))
            response(chain)
        }, readCookies = { cookieReads++; "sp_dc=secret" })
        val options = JSONObject().put("headers", JSONObject().put("Cookie", "stolen").put("Origin", "https://evil.test")
            .put("Authorization", "Bearer api-token")).toString()
        assertEquals(200, JSONObject(fetch.execute("https://api.spotify.com/v1/me", options)).getInt("status"))
        assertEquals(0, cookieReads)
    }

    @Test fun rejectsRedirectsAndOversizedResponses() {
        val redirect = NativeFetch(client { response(it, 302).newBuilder().header("Location", "https://evil.test").build() })
        assertEquals(0, JSONObject(redirect.execute("https://api.spotify.com/", null)).getInt("status"))
        val large = NativeFetch(client { response(it, body = ByteArray(4_194_305)) })
        assertEquals(0, JSONObject(large.execute("https://api.spotify.com/", null)).getInt("status"))
    }

    @Test fun binaryCanvasRequestsAndResponsesPreserveTheirBytes() {
        val bytes = byteArrayOf(0, -1, -128, 65)
        val fetch = NativeFetch(client { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            assertArrayEquals(bytes, buffer.readByteArray())
            response(chain, body = bytes)
        })
        val options = JSONObject().put("method", "POST").put("bodyBase64", Base64.getEncoder().encodeToString(bytes))
            .put("responseBase64", true).toString()
        val result = JSONObject(fetch.execute("https://spclient.wg.spotify.com/canvaz-cache/v0/canvases", options))
        assertEquals(200, result.getInt("status"))
        assertArrayEquals(bytes, Base64.getDecoder().decode(result.getString("bodyBase64")))
    }
}
