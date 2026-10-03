package com.project.lol.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Central trust decisions. Never decide authority with URL substring matching. */
public final class WebSecurityPolicy {
    private WebSecurityPolicy() {}
    private static final Set<String> LINKS = Set.of("open.spotify.com", "spotify.com", "www.spotify.com", "play.spotify.com", "spotify.link");
    private static final Set<String> LOGIN = Set.of("accounts.spotify.com", "accounts.google.com", "consent.google.com", "www.facebook.com", "m.facebook.com", "appleid.apple.com");
    private static final Set<String> API = Set.of("open.spotify.com", "api.spotify.com", "api-partner.spotify.com", "clienttoken.spotify.com", "spclient.wg.spotify.com");

    private static final Set<String> PLAYER_METHODS = Set.of("nFetch", "loginDetected", "deferMessage", "wakeUp", "wakeOff", "cssInjected", "dbg", "clearDebugLog", "recAdContentIds", "playLoaded", "recMediaPosition", "recMediaStatus", "onMediaItemsLoaded", "onSearchCompleted", "manageTShut", "manageTSleep", "recAccountName", "openTimerDialog", "enterPip", "enterPipVideo", "downloadTrack", "downloadCollection", "skipDownload", "cancelDownload");
    public static boolean isBridgeCall(String origin, boolean mainFrame, String method) {
        if (!mainFrame || !isBridgeOrigin(origin)) return false;
        return isPlayer(origin) ? PLAYER_METHODS.contains(method) : "loginDetected".equals(method);
    }
    public static String restoreCookie(String pair) {
        if (pair == null || pair.indexOf('=') <= 0 || pair.indexOf(';') >= 0 || pair.indexOf('\r') >= 0 || pair.indexOf('\n') >= 0) return null;
        String name = pair.substring(0, pair.indexOf('=')).trim();
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || Set.of("domain", "path", "expires", "max-age", "samesite").contains(name.toLowerCase(Locale.ROOT))) return null;
        // getCookie() does not retain cookie attributes. Restore the auth cookie explicitly.
        return pair + "; Path=/; Secure" + (name.equals("sp_dc") ? "; HttpOnly" : "");
    }
    public static String httpsHost(String url) {
        if (url == null || url.length() > 8192 || url.indexOf('\\') >= 0) return null;
        if (url.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("%0a") || lower.contains("%0d") || lower.contains("%00")) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) return null;
            String host = uri.getHost();
            if (host == null || host.endsWith(".")) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+")) return null;
            // Reject alternate port spellings and encoded authorities to avoid parser differences.
            String authority = uri.getRawAuthority().toLowerCase(Locale.ROOT);
            if (!authority.equals(host) && !authority.equals(host + ":443")) return null;
            return host;
        } catch (Exception e) { return null; }
    }
    public static boolean isDeepLink(String url) { return LINKS.contains(orEmpty(httpsHost(url))); }
    public static boolean isNavigation(String url) {
        String host = orEmpty(httpsHost(url));
        return LINKS.contains(host) || LOGIN.contains(host);
    }
    public static boolean isGoogleAuth(String url) {
        String host = httpsHost(url);
        return "accounts.google.com".equals(host) || "consent.google.com".equals(host);
    }
    public static boolean isPlayer(String url) { return "open.spotify.com".equals(httpsHost(url)); }
    public static boolean isAccounts(String url) { return "accounts.spotify.com".equals(httpsHost(url)); }
    public static boolean isBridgeOrigin(String origin) {
        if (!isPlayer(origin) && !isAccounts(origin)) return false;
        try {
            URI uri = new URI(origin);
            return (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception e) { return false; }
    }
    public static boolean isNativeFetch(String url) {
        String host = orEmpty(httpsHost(url));
        return API.contains(host) || host.matches("[a-z0-9-]+-spclient\\.spotify\\.com");
    }
    public static boolean isCookieHost(String url) { return isPlayer(url); }
    public static boolean isTrackId(String id) { return id != null && id.matches("[A-Za-z0-9]{22}"); }
    public static boolean isProxyHost(String host) {
        return host != null && (host.equals("www.google.com") || host.equals("spotify.com") || host.endsWith(".spotify.com") || isNavigation("https://" + host) || isNativeFetch("https://" + host) ||
            host.equals("scdn.co") || host.endsWith(".scdn.co") || host.equals("spotifycdn.com") ||
            host.endsWith(".spotifycdn.com") || host.equals("spotifycdn.net") || host.endsWith(".spotifycdn.net") ||
            host.equals("gstatic.com") || host.endsWith(".gstatic.com") || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com"));
    }
    private static String orEmpty(String s) { return s == null ? "" : s; }
}
