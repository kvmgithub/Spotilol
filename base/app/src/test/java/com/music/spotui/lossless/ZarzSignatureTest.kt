package com.music.spotui.lossless

import org.junit.Assert.assertEquals
import org.junit.Test

/** ZARZ-HMAC-V1, checked against an independent implementation of the same protocol. */
class ZarzSignatureTest {
    @Test fun signsLikeTheExtensionRuntime() {
        val h = ZarzSession.signHeaders(
            sessionId = "sess_123", secret = "s3cr3t", appVersion = "tidal-web@1.2.5",
            method = "POST", path = "/v2/dl/tid", body = """{"id":"1"}""".toByteArray(),
            nowMs = 1_760_000_000_123, nonce = "abcdef012345",
        )
        assertEquals("2025-10-09T08:53:20.123Z", h["X-Zarz-Timestamp"])
        assertEquals("5811967f540d300d249ab30ae681359a7815fdb5d3dc71a94be1d491006a6b27", h["X-Zarz-Body-SHA256"])
        assertEquals("8EVagVFjx3zbtgAmLx-ZDgxj579Dc4nuoYlfYaOlJNw", h["X-Zarz-Signature"])
        assertEquals("tidal-web@1.2.5", h["X-Zarz-App-Version"])
        assertEquals("extension", h["X-Zarz-Platform"])
    }

    @Test fun readsTheGrantWhereverTheReturnLinkPutsIt() {
        assertEquals("g1" to "s1", ZarzSession.parseGrant("spotiflac://session-grant?cb_version=v2grant&state=s1&grant=g1"))
        assertEquals("g2" to "s2", ZarzSession.parseGrant("spotiflac://session-grant?state=s2#grant=g2"))
        assertEquals("g3" to "s3", ZarzSession.parseGrant("spotiflac://session-grant?code=g3&state=s3"))
        assertEquals("g%4" to "s4", ZarzSession.parseGrant("spotiflac://session-grant?grant=g%254&state=s4"))
        assertEquals(null, ZarzSession.parseGrant("spotiflac://session-grant?state=s5"))
    }
}
