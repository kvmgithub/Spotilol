import com.project.lol.security.WebSecurityPolicy;
import com.project.lol.security.BoundedInput;
import com.project.lol.security.HttpFraming;
import com.project.lol.security.LogRedactor;
import java.util.List;
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
        check(WebSecurityPolicy.isTrackId("4uLU6hMCjMI75M1A2tKUQC"), "Spotify ID");
        for (String id : new String[]{"../x", "/tmp/x", "..", "abc", "aaaaaaaaaaaaaaaaaaaaa/", "aaaaaaaaaaaaaaaaaaaaa\\"})
            check(!WebSecurityPolicy.isTrackId(id), "blocked track ID: " + id);
        check("héllo".equals(BoundedInput.readUtf8(new ByteArrayInputStream("héllo".getBytes(StandardCharsets.UTF_8)), 6)), "UTF-8 exact byte limit");
        boolean exceeded = false;
        try { BoundedInput.readUtf8(new ByteArrayInputStream(new byte[7]), 6); }
        catch (java.io.IOException e) { exceeded = true; }
        check(exceeded, "response byte cap");
        check("line".equals(HttpFraming.readLine(new ByteArrayInputStream("line\r\n".getBytes(StandardCharsets.US_ASCII)), 4)), "bounded CRLF line");
        for (String line : new String[]{"xxxxx\r\n", "abc\n", "abc", "abc\rX"}) {
            boolean rejected = false;
            try { HttpFraming.readLine(new ByteArrayInputStream(line.getBytes(StandardCharsets.US_ASCII)), 4); }
            catch (java.io.IOException e) { rejected = true; }
            check(rejected, "malformed or oversized line");
        }
        check(HttpFraming.contentLength(List.of("Content-Length"), List.of("123")) == 123, "valid content length");
        check(HttpFraming.contentLength(List.of("Transfer-Encoding"), List.of("chunked")) == -1, "valid chunks");
        for (List<String> values : List.of(List.of("-1"), List.of("garbage"), List.of("999999999999999999999999"))) {
            boolean rejected = false;
            try { HttpFraming.contentLength(List.of("Content-Length"), values); }
            catch (java.io.IOException e) { rejected = true; }
            check(rejected, "invalid length rejected");
        }
        boolean ambiguous = false;
        try { HttpFraming.contentLength(List.of("Content-Length", "Transfer-Encoding"), List.of("1", "chunked")); }
        catch (java.io.IOException e) { ambiguous = true; }
        check(ambiguous, "TE/CL ambiguity rejected");
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
        System.out.println("PASS: " + checks + " security policy checks");
    }
}
