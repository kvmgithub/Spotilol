package app.myspoti.spotify.security

/**
 * Best-effort diagnostic redaction, ported from Spotilol's LogRedactor. It is not
 * permission to log session data: callers still avoid logging values on purpose.
 */
object LogRedactor {
    private val I = RegexOption.IGNORE_CASE
    // Spotify puts session/challenge identifiers in the challenge path, not just the query.
    private val CHALLENGE_URL = Regex("""(https://challenge\.spotify\.com(?::443)?)(?=[/?#\s"']|$)[^\s"']*""", I)
    private val URL_QUERY = Regex("""(https?://[^\s?#"']+)[?#][^\s"']+""", I)
    private val AUTH = Regex("""(["']?Authorization["']?\s*[:=]\s*["']?)(?:Bearer\s+)?[^\s,}"']+""", I)
    private val TOKEN = Regex("""("(?:access_?token|refresh_?token|id_?token|client_?token|sp_dc|sp_key)"\s*:\s*")[^"]*""", I)
    private val COOKIE = Regex("""(["']?\b(?:cookie|set-cookie)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\r\n]+)""", I)
    private val SECRET = Regex(
        """(["']?\b(?:sp_dc|sp_key|sp_t|sp_sso|csrf_?token|access_?token|refresh_?token|id_?token|auth_?token|bearer_?token|client_?token|client_?secret|api_?key|po_?token|visitor_?data|session_?id|password|passwd|pwd|secret_?key)["']?\s*[=:]\s*["']?)(?!true\b|false\b|null\b|none\b|unknown\b)[^\s&,;"'}\]]+""", I)
    private val WEAK_SECRET = Regex(
        """(\b(?:token|secret|signature|sig|lsig|session|auth|code)\s*=\s*["']?)(?!true\b|false\b|null\b|none\b|unknown\b)[^\s&,;"'}\]]{8,}""", I)
    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{6,}\.[A-Za-z0-9_\-]{6,}(?:\.[A-Za-z0-9_\-]{4,})?""")
    private val EMAIL = Regex("""\b([A-Za-z0-9])[A-Za-z0-9._%+\-]{0,63}@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,})\b""")

    fun redact(value: String?): String {
        if (value == null) return ""
        var r = CHALLENGE_URL.replace(value, "\$1/[redacted]")
        r = URL_QUERY.replace(r, "\$1?[redacted]")
        r = AUTH.replace(r, "\$1[redacted]")
        r = TOKEN.replace(r, "\$1[redacted]")
        r = COOKIE.replace(r, "\$1[redacted]")
        r = SECRET.replace(r, "\$1[redacted]")
        r = WEAK_SECRET.replace(r, "\$1[redacted]")
        r = JWT.replace(r, "[redacted]")
        return EMAIL.replace(r, "\$1***@\$2")
    }
}
