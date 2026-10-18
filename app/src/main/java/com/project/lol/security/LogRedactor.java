package com.project.lol.security;

import java.util.regex.Pattern;

/** Best-effort diagnostic redaction, not permission to log arbitrary session data. */
public final class LogRedactor {
    private LogRedactor() {}
    // Spotify embeds session/challenge identifiers in the path, not just the query.
    private static final Pattern CHALLENGE_URL = Pattern.compile("(https://challenge\\.spotify\\.com(?::443)?)(?=[/?#\\s\\\"']|$)[^\\s\\\"']*", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?#\\\"']+)[?#][^\\s\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH = Pattern.compile("([\"']?Authorization[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?[^\\s,}\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("(\\\"(?:access_?token|refresh_?token|id_?token|client_?token|sp_dc|sp_key)\\\"\\s*:\\s*\\\")[^\\\"]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE = Pattern.compile("([\"']?\\b(?:cookie|set-cookie)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\r\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile("([\"']?\\b(?:sp_dc|sp_key|sp_t|sp_sso|csrf_?token|access_?token|refresh_?token|id_?token|auth_?token|bearer_?token|client_?token|client_?secret|api_?key|po_?token|visitor_?data|session_?id|password|passwd|pwd|secret_?key)[\"']?\\s*[=:]\\s*[\"']?)(?!true\\b|false\\b|null\\b|none\\b|unknown\\b)[^\\s&,;\"'}\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_SECRET = Pattern.compile("(\\b(?:token|secret|signature|sig|lsig|session|auth|code)\\s*=\\s*[\"']?)(?!true\\b|false\\b|null\\b|none\\b|unknown\\b)[^\\s&,;\"'}\\]]{8,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}(?:\\.[A-Za-z0-9_\\-]{4,})?");
    private static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9])[A-Za-z0-9._%+\\-]{0,63}@([A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,})\\b");
    public static String redact(String value) {
        if (value == null) return "";
        String result = CHALLENGE_URL.matcher(value).replaceAll("$1/[redacted]");
        result = URL_QUERY.matcher(result).replaceAll("$1?[redacted]");
        result = AUTH.matcher(result).replaceAll("$1[redacted]");
        result = TOKEN.matcher(result).replaceAll("$1[redacted]");
        result = COOKIE.matcher(result).replaceAll("$1[redacted]");
        result = SECRET.matcher(result).replaceAll("$1[redacted]");
        result = WEAK_SECRET.matcher(result).replaceAll("$1[redacted]");
        result = JWT.matcher(result).replaceAll("[redacted]");
        return EMAIL.matcher(result).replaceAll("$1***@$2");
    }
}
