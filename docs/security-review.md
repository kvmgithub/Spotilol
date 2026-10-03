# Security hardening review

Upstream baseline: `1e75b65a62af3322f3b94d1b0b7ab81dabf82f82` (version 1.1.8).

## Corrected boundaries

- The player no longer uses `addJavascriptInterface`. AndroidX messages are restricted to exact player/login HTTPS origins and the main frame. The login origin can only signal login. There is no insecure compatibility fallback. An outdated WebView receives an update message.
- Native fetch is asynchronous, limited to explicit player/API hosts and regional Spotify spclient hosts, and uses a bounded worker queue. HTTPS, default port and unambiguous URL parsing are mandatory. Automatic redirects are disabled; redirect responses are rejected. Cookies are attached only to the player host; Set-Cookie is applied natively but never returned to JavaScript. Bodies, headers and responses have size limits. Errors do not expose requested URL queries.
- Deep links and top-level navigation use parsed hosts rather than substring/suffix checks. OAuth hosts are explicit. External HTTPS navigation opens outside the WebView only on a user gesture. Popup WebViews deny file/content access and mixed content. Player injections and delayed callbacks are origin-checked.
- DRM permissions grant only protected-media ID on the player origin, never other resources requested together.
- Media browsing verifies package/UID ownership and AndroidX trusted-media-controller status before revealing the library.
- Proxy CA and account profile preferences fail closed instead of using plaintext or deleting a shared master key. Unreadable profiles cannot be overwritten by a subsequent save/delete. UI failures are handled in both normal and offline settings. Restored sp_dc session cookies retain Secure/HttpOnly.
- Local CONNECT is restricted to supported hosts on port 443. Worker queues, lines, headers and trailers are bounded. Ambiguous Content-Length/Transfer-Encoding and malformed chunks are rejected. Interim responses remain part of their current exchange. TLS failures close their sockets. Certificate dates use an explicit locale without changing the process-wide locale.
- User CA trust is scoped to the supported proxy domains, rather than the whole app. System CAs remain the default for other traffic.
- Media search query/ID/context arguments are JSON-quoted before JavaScript evaluation. Pending search requests are bounded and expire. Online and offline sessions handle voice-search playback; external voice intents wait for the authenticated player. Custom action receivers are explicitly non-exported across supported Android versions.
- Unsupported proxy/renderer features are checked before calling AndroidX WebKit, and proxy-override executors are released after completion. Android 28 retains the transliteration fallback without calling an API 29 class. Compose resource/date labels observe locale changes.
- Download IDs must be 22-character Spotify IDs before being used in private download/cover paths.
- Firebase Analytics, Crashlytics, Performance and their build plugins are removed. Query strings and recognized bearer/JSON tokens are redacted from local diagnostics; account names are no longer explicitly logged by the player/profile code.

## Validation record

- Dependency-free JVM tests: 78 policy checks passed (URLs, bridge callers, cookie restore, traversal, bounded streams, HTTP framing, log redaction).
- Node tests: 7 tests passed against the exact injected bridge shim (foreign origins/frames, login authority, native responses, failures/timeouts, compatibility methods), plus voice-search script escaping and result selection.
- `git diff --check`: passed.
- Independent code review found and resolved a misplaced media-client check plus incomplete error handling and strict read behavior in the profile manager.
- Android JUnit: 14 tests passed, 0 failures (6 security-policy tests, 3 encrypted-profile mutation tests, 3 voice-query tests, 2 scanner regressions).
- Android lint: 0 errors/fatal findings; all 31 initial error/fatal findings resolved. 158 warnings and 4 hints remain, mostly Kotlin API/style suggestions, resource/SDK advice and dependency notices. The three trust-all warnings are unused BouncyCastle dependency classes, not the proxy's platform-default upstream TLS socket. The exported media service is intentional and uses the package/UID/trust check described above. Process-lifetime helper WebViews use application context, while the player reference is cleared on destruction.
- Debug APK assembly: passed with JDK 21, Gradle 9.7.1, Android SDK 37 and NDK 28.2.13676358.
- Combined verification: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --no-configuration-cache --console=plain -Pkotlin.incremental=false` returned BUILD SUCCESSFUL. Disabling incremental Kotlin compilation avoided stale duplicate Compose symbols after source edits in this environment.

## Limits and operational changes

This is not a guarantee that the entire application is vulnerability-free. The initial source review did not use a live account or physical device. Subsequent Pixel 9 Pro XL / GrapheneOS testing confirmed login/session restoration, playback, profile handling, downloads and offline playback. Later logs show continued background playback and Bluetooth-disconnect pause without a fatal exception. Proxy/certificate mode and Android Auto still need device validation. Native fetch intentionally rejects redirects and unsupported hosts; future Spotify changes may need explicit allowlist updates. Unknown login providers open outside the trusted WebView rather than gaining native access.

The MITM mode remains optional and changes HTTP headers; it does not guarantee an undetectable browser fingerprint. All user-installed CAs still qualify within the narrowly configured proxy domains. Existing profile restoration can preserve known auth-cookie flags but cannot recover all cookie metadata, because Android's CookieManager.getCookie omits those attributes. Other cookies preserve the original JavaScript visibility semantics. A damaged encrypted CA remains on disk and triggers normal mode rather than automatic replacement; recovering that proxy identity requires fixing/resetting its secure storage deliberately.

Diagnostics remain local and opt-in in release builds; redaction is best-effort, so inspect exports before sharing them. The internal YouTube cipher/token WebViews still use minimal JavascriptInterfaces for local generated pages, with network loads blocked; they are separate from the logged-in Spotify player and require a dedicated audit if their downloaded JavaScript is to be treated as hostile.

## Device-log follow-up

The Pixel/Vanadium log shows successful login and persistent session restoration. Both player loads stop logging during native injection preparation; SIGQUIT thread dumps follow. The log does not include an ANR verdict or the dumped main-thread stack, so it cannot prove the exact device-side cause.

A separate regression reproduced quadratic work in JsUtils.nextLiteral: four suffix searches were repeated for every literal, even when some delimiter types were absent. A 1.6 MB quote-heavy payload timed out after 3 seconds before the fix. One forward delimiter scan completes the same regression in about 20 ms on the build host, while preserving nested calls, comments and quoted text. All 14 Android JVM tests pass and the arm64 test APK builds successfully. Subsequent device testing confirmed successful player startup and playback.
