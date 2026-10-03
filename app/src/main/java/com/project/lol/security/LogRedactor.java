package com.project.lol.security;

import java.util.regex.Pattern;

/** Best-effort diagnostic redaction, not permission to log arbitrary session data. */
public final class LogRedactor {
    private LogRedactor() {}
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?#\\\"']+)[?#][^\\s\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH = Pattern.compile("([\"']?Authorization[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?[^\\s,}\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("(\\\"(?:access_?token|refresh_?token|id_?token|client_?token|sp_dc|sp_key)\\\"\\s*:\\s*\\\")[^\\\"]*", Pattern.CASE_INSENSITIVE);
    public static String redact(String value) {
        if (value == null) return "";
        String result = URL_QUERY.matcher(value).replaceAll("$1?[redacted]");
        result = AUTH.matcher(result).replaceAll("$1[redacted]");
        return TOKEN.matcher(result).replaceAll("$1[redacted]");
    }
}
