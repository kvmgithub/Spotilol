# Development guidance

This repository is kvmgithub/Spotilol. The functional baseline is AdNenio/Spotilol-Lite at 8b986aa752481ad3d03fd08c662e66e5bbf051ab, based on the preserved Spotilol 1.2.4 release. The original lyssadev/Spotilol repository is unavailable. GitHub's fork-network source is not the maintained upstream.

## Scope

Keep Lite's online player, profiles, timer, Canvas/video podcasts, navigation ducking, dark/AMOLED UI, diagnostics and background WebView lifetime. Do not reintroduce Firebase, downloads/YouTube/InnerTube, Android Auto, widgets, MITM proxy/certificates, custom CSS, lyrics customization or system PiP. In-app video fullscreen is retained.

The delta from Lite is necessary security hardening, tests, update routing and signed APK publication. OkHttp is retained solely for native HTTPS request lifetime/redirect control; it does not restore the removed download stack.

## Security invariants

- Native authority is restricted to approved main-frame origins by OriginScopedBridge. No addJavascriptInterface fallback.
- Player and login have separate method authority. Add new player callbacks to WebSecurityPolicy, BridgeScript and OriginScopedBridge together, with tests.
- NativeFetch must enforce HTTPS host/port allowlists, request/response limits, a total call timeout, strict transport headers, scoped cookies and no redirects. Never return Set-Cookie to JavaScript. Its binary transport is used by Canvas protobuf requests.
- ProfileManager must never delete keys automatically or fall back to plaintext. Failed reads must not become empty writes. Cookie restoration validates domains and preserves Secure/HttpOnly.
- Navigation, intents, OAuth popups and DRM decisions use WebSecurityPolicy.
- Logger and exported reports share the security LogRedactor. Never log session values just because a redactor exists.
- Keep WebViewHost's application-scoped lifetime and NavToken stale-navigation guards while applying security changes.

## Build and release

- Run bash tests/security/run.sh, then ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease.
- CI uses JDK 21, Android platform 37 and build tools 37.0.0. No NDK, Firebase configuration or Google services project is needed.
- Published package: com.project.lol.fork, ARM64-compatible, versionCode 1000 + GitHub workflow run number. Fork versionName uses 1.2.<versionCode>; the application baseline remains 1.2.4.
- Preserve the existing APK_SIGNING_BUNDLE secret. Do not print or commit signing material. Release signing must match published v1.1.1002.
- .github/workflows/apk-release.yml tests feature-branch builds, but only main publishes a release after every check succeeds.
- UpdateChecker, changelog and source links point to kvmgithub/Spotilol. Automatic checks only notify; never open the browser automatically.
- Publish claims distinguish automated verification from real-device tests. Earlier 1.1.8 testing is not validation of this Lite-based build.

## Updating the baseline

Inspect the actual new source tree and user-approved feature scope. Preserve both histories with ordinary merges; do not force-push main. Review every change touching bridge, native networking, profiles, WebViewHost, navigation, media service or release workflow against the security invariants. Keep README and CHANGELOG current.
