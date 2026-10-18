import com.project.lol.security.WebSecurityPolicy;
import com.project.lol.security.BoundedInput;
import com.project.lol.security.LogRedactor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class SecurityPolicyTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        for (String url : new String[]{"https://open.spotify.com/track/abc", "https://spotify.link/xyz", "https://www.spotify.com/", "https://play.spotify.com/"})
            check(WebSecurityPolicy.isDeepLink(url), "valid deep link: " + url);
        for (String url : new String[]{"https://evilspotify.com/", "http://open.spotify.com/", "https://open.spotify.com.evil.test/", "https://open.spotify.com@evil.test/", "https://user@open.spotify.com/", "https://open.spotify.com:444/", "javascript://open.spotify.com/alert(1)", "https://open.spotify.com\\@evil.test/", "https://open.spotify.com./", "https://open.spotify.com/%0a", "https://open.spotify.com:0443/"})
            check(!WebSecurityPolicy.isDeepLink(url), "blocked deep link: " + url);
        check(WebSecurityPolicy.isDeepLink("https://OPEN.SPOTIFY.COM:443/"), "case normalization");
        for (String host : new String[]{"accounts.google.com", "consent.google.com", "www.facebook.com", "appleid.apple.com", "accounts.spotify.com"})
            check(WebSecurityPolicy.isNavigation("https://" + host + "/login"), "valid login: " + host);
        for (String host : new String[]{"login.google.evil.test", "evil.google.com", "apple.evil.test", "accounts.spotify.com.evil.test"})
            check(!WebSecurityPolicy.isNavigation("https://" + host + "/"), "blocked login: " + host);
        for (String host : new String[]{"open.spotify.com", "api.spotify.com", "api-partner.spotify.com", "clienttoken.spotify.com", "gue1-spclient.spotify.com", "spclient.wg.spotify.com"})
            check(WebSecurityPolicy.isNativeFetch("https://" + host + "/x"), "valid native host: " + host);
        for (String url : new String[]{"https://accounts.spotify.com/", "https://127.0.0.1/", "https://evilspotify.com/", "https://api.spotify.com:8443/", "file:///etc/passwd", "https://api.spotify.com@evil.test/", "https://evil.test/?spotify.com", "https://api.spotify.com/%0d%0aInjected", "https://i.scdn.co/"})
            check(!WebSecurityPolicy.isNativeFetch(url), "blocked native URL: " + url);
        check(WebSecurityPolicy.isCookieHost("https://open.spotify.com/x"), "cookie host");
        check(!WebSecurityPolicy.isCookieHost("https://api.spotify.com/x"), "API bearer auth, no cookies");
        check(WebSecurityPolicy.isBridgeOrigin("https://open.spotify.com"), "player origin");
        check(WebSecurityPolicy.isBridgeOrigin("https://accounts.spotify.com"), "login origin");
        check(!WebSecurityPolicy.isBridgeOrigin("https://open.spotify.com/path"), "origin only");
        check(WebSecurityPolicy.isNativeSniff("https://accounts.google.com/login", "GET", false), "Google login sniff");
        check(WebSecurityPolicy.isNativeSniff("https://consent.google.com/", "HEAD", false), "Google consent HEAD sniff");
        check(WebSecurityPolicy.isNativeSniff("https://audio.scdn.co/audio/example", "GET", true), "ad candidate HTTPS sniff");
        for (String url : new String[]{"https://x.google.attacker.test/", "https://youtube.com/", "https://accounts.google.com:8443/", "http://accounts.google.com/"})
            check(!WebSecurityPolicy.isNativeSniff(url, "GET", false), "untrusted auth sniff rejected: " + url);
        check(!WebSecurityPolicy.isNativeSniff("http://audio.scdn.co/audio/example", "GET", true), "cleartext ad sniff rejected");
        for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"})
            check(!WebSecurityPolicy.isNativeSniff("https://accounts.google.com/login", method, false), "non-read sniff rejected: " + method);
        check(!WebSecurityPolicy.isNativeSniff("https://open.spotify.com/", "GET", false), "unrelated request bypasses sniff");
        check("héllo".equals(BoundedInput.readUtf8(new ByteArrayInputStream("héllo".getBytes(StandardCharsets.UTF_8)), 6)), "UTF-8 exact byte limit");
        boolean exceeded = false;
        try { BoundedInput.readUtf8(new ByteArrayInputStream(new byte[7]), 6); }
        catch (java.io.IOException e) { exceeded = true; }
        check(exceeded, "response byte cap");
        check(!LogRedactor.redact("https://accounts.spotify.com/login?code=SECRET#TOKEN").contains("SECRET"), "OAuth query redacted");
        check(!LogRedactor.redact("Authorization: Bearer SECRET").contains("SECRET"), "bearer redacted");
        check(!LogRedactor.redact("{\"Authorization\":\"Bearer SECRET\"}").contains("SECRET"), "JSON authorization redacted");
        check(!LogRedactor.redact("{\"accessToken\":\"SECRET\"}").contains("SECRET"), "JSON token redacted");
        check(!WebSecurityPolicy.isBridgeCall("https://open.spotify.com", false, "nFetch"), "subframe call rejected");
        check(!WebSecurityPolicy.isBridgeCall("https://evilspotify.com", true, "nFetch"), "foreign origin rejected");
        check(!WebSecurityPolicy.isBridgeCall("https://accounts.spotify.com", true, "nFetch"), "accounts native fetch rejected");
        check(WebSecurityPolicy.isBridgeCall("https://accounts.spotify.com", true, "loginDetected"), "accounts login allowed");
        check(WebSecurityPolicy.isBridgeCall("https://open.spotify.com", true, "nFetch"), "player fetch allowed");
        check(!WebSecurityPolicy.isBridgeCall("https://open.spotify.com", true, "arbitraryReflection"), "unknown bridge method rejected");
        check(WebSecurityPolicy.restoreCookie("sp_dc=secret").contains("HttpOnly"), "restored session preserves HttpOnly");
        check(WebSecurityPolicy.restoreCookie("sp_t=preference").contains("Secure"), "restored cookies require TLS");
        check(WebSecurityPolicy.restoreCookie("Domain=evil.test") == null, "cookie attribute injection rejected");
        for (String secret : new String[]{
            "Cookie: sp_dc=verysecret; sp_key=othersecret",
            "{\"Cookie\":\"sp_dc=verysecret; sp_key=othersecret\"}",
            "{\"accessToken\":\"verysecret\"}", "refresh_token=verysecret", "password=verysecret",
            "client_secret=verysecret", "https://api.spotify.com/?session=verysecret"}) {
            check(!LogRedactor.redact(secret).contains("verysecret"), "diagnostic secret redacted: " + secret.split("[=:]")[0]);
        }
        check(!LogRedactor.redact("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzZWNyZXQifQ.signatureSecret").contains("signatureSecret"), "JWT redacted");
        for (String method : new String[]{"openVolumeDialog", "playerExpanded", "wideVideo", "ctxMenuState", "enterVideoFullscreen", "exitVideoFullscreen"}) {
            check(WebSecurityPolicy.isBridgeCall("https://open.spotify.com", true, method), "Lite player callback allowed: " + method);
            check(!WebSecurityPolicy.isBridgeCall("https://accounts.spotify.com", true, method), "login cannot control player: " + method);
        }
        for (String method : new String[]{"downloadTrack", "downloadCollection", "enterPip", "enterPipVideo", "onMediaItemsLoaded", "onSearchCompleted"})
            check(!WebSecurityPolicy.isBridgeCall("https://open.spotify.com", true, method), "removed feature has no native authority: " + method);
        System.out.println("PASS: " + checks + " security policy checks");
    }
}
