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
    @Test public void blocksTraversalIds() {
        assertFalse(WebSecurityPolicy.isTrackId("../profile"));
        assertTrue(WebSecurityPolicy.isTrackId("4uLU6hMCjMI75M1A2tKUQC"));
    }
    @Test(expected = IOException.class) public void capsNativeResponseBytes() throws Exception {
        BoundedInput.readUtf8(new ByteArrayInputStream(new byte[1025]), 1024);
    }
    @Test(expected = IOException.class) public void rejectsAmbiguousProxyFraming() throws Exception {
        HttpFraming.contentLength(List.of("Content-Length", "Transfer-Encoding"), List.of("1", "chunked"));
    }
    @Test public void redactsSessionData() {
        assertFalse(LogRedactor.redact("https://accounts.spotify.com/?code=SECRET").contains("SECRET"));
        assertFalse(LogRedactor.redact("Authorization: Bearer SECRET").contains("SECRET"));
    }
}
