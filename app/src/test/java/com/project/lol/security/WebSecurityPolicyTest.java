package com.project.lol.security;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WebSecurityPolicyTest {
    @Test public void rejectsSuffixImpersonationAndCredentials() {
        assertFalse(WebSecurityPolicy.isDeepLink("https://evilspotify.com/"));
        assertFalse(WebSecurityPolicy.isDeepLink("https://user@open.spotify.com/"));
        assertFalse(WebSecurityPolicy.isNativeFetch("https://api.spotify.com@evil.test/"));
        assertTrue(WebSecurityPolicy.isDeepLink("https://open.spotify.com/track/123"));
    }
    @Test public void restrictsOAuthAndNativeAuthorityIndependently() {
        assertTrue(WebSecurityPolicy.isNavigation("https://accounts.google.com/login"));
        assertFalse(WebSecurityPolicy.isNavigation("https://login.google.evil.test/"));
        assertFalse(WebSecurityPolicy.isNativeFetch("https://accounts.spotify.com/"));
        assertTrue(WebSecurityPolicy.isNativeFetch("https://gue1-spclient.spotify.com/x"));
        assertFalse(WebSecurityPolicy.isCookieHost("https://api.spotify.com/"));
    }
    @Test public void permitsChallengeNavigationWithoutNativeAuthority() {
        String origin = "https://challenge.spotify.com";
        assertTrue(WebSecurityPolicy.isNavigation(origin + "/c/session/challenge/recaptcha"));
        assertTrue(WebSecurityPolicy.isNavigation("https://CHALLENGE.SPOTIFY.COM:443/c/session/challenge/email"));
        assertFalse(WebSecurityPolicy.isPlayer(origin + "/c/session/challenge/email"));
        assertTrue(WebSecurityPolicy.isChallenge(origin + "/c/session/challenge/email"));
        assertFalse(WebSecurityPolicy.isChallenge("https://challenge.spotify.com.evil.test/"));
        assertFalse(WebSecurityPolicy.isBridgeOrigin(origin));
        assertFalse(WebSecurityPolicy.isBridgeCall(origin, true, "loginDetected"));
        assertFalse(WebSecurityPolicy.isNativeFetch(origin + "/c/session"));
        assertFalse(WebSecurityPolicy.isCookieHost(origin));
        assertFalse(WebSecurityPolicy.isNativeSniff(origin + "/c/session", "GET", true));
    }
    @Test public void rejectsChallengeHostImpersonationAndInsecureUrls() {
        for (String url : new String[]{"http://challenge.spotify.com/", "https://challenge.spotify.com:8443/",
                "https://challenge.spotify.com.evil.test/", "https://evilchallenge.spotify.com/",
                "https://challenge.spotify.com@evil.test/", "https://user@challenge.spotify.com/"}) {
            assertFalse(url, WebSecurityPolicy.isNavigation(url));
        }
    }
    @Test public void redactsChallengeSessionIdentifiers() {
        String result = LogRedactor.redact("page started: https://challenge.spotify.com/c/SESSION_SECRET/CHALLENGE_SECRET/recaptcha?x=QUERY_SECRET#FRAGMENT_SECRET");
        assertFalse(result.contains("SECRET"));
        assertTrue(result.contains("challenge.spotify.com"));
    }
    @Test(expected = IOException.class) public void capsNativeResponseBytes() throws Exception {
        BoundedInput.readUtf8(new ByteArrayInputStream(new byte[1025]), 1024);
    }
    @Test public void redactsSessionData() {
        assertFalse(LogRedactor.redact("https://accounts.spotify.com/?code=SECRET").contains("SECRET"));
        assertFalse(LogRedactor.redact("Authorization: Bearer SECRET").contains("SECRET"));
    }
}
