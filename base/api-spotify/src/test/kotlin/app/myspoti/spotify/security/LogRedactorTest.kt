package app.myspoti.spotify.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogRedactorTest {
    @Test
    fun `session values never survive redaction`() {
        listOf(
            "https://accounts.spotify.com/login?code=verysecret#frag",
            "Authorization: Bearer verysecret",
            "{\"Authorization\":\"Bearer verysecret\"}",
            "{\"accessToken\":\"verysecret\"}",
            "Cookie: sp_dc=verysecret; sp_key=othersecret",
            "{\"Cookie\":\"sp_dc=verysecret; sp_key=othersecret\"}",
            "refresh_token=verysecret", "password=verysecret", "client_secret=verysecret",
            "https://api.spotify.com/?session=verysecret",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.verysecretSig",
        ).forEach { assertFalse(LogRedactor.redact(it).contains("verysecret"), it) }
    }

    @Test
    fun `challenge identifiers go, the host stays`() {
        val out = LogRedactor.redact("page start https://challenge.spotify.com/c/SESSIONSECRET/CHALLENGESECRET/email?x=1")
        assertFalse(out.contains("SECRET"))
        assertTrue(out.contains("challenge.spotify.com"))
    }

    @Test
    fun `emails are masked`() {
        assertFalse(LogRedactor.redact("user hans.mustermann@example.org").contains("mustermann"))
    }
}
