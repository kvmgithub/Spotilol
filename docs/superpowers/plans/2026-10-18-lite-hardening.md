# Lite 1.2.4 integration

Goal: adopt AdNenio/Spotilol-Lite at 8b986aa752481ad3d03fd08c662e66e5bbf051ab in kvmgithub/Spotilol, retaining necessary security hardening, regression tests and signed APK releases.

The existing Lite player, video features, UI and background lifecycle are the functional baseline. Do not restore Firebase, downloads, Android Auto, widgets, proxy, custom CSS, lyrics customization or system PiP. Preserve com.project.lol.fork and the existing release signing bundle so the published APK updates the current fork installation.

## Integration tasks

- [x] Import the Lite tree without rewriting either repository's history.
- [x] Restore trusted-origin/main-frame bridge authorization and adapt its allowed methods to Lite's timer, volume, expanded-player, context-menu and video-fullscreen callbacks.
- [x] Restrict native HTTP destinations, headers, cookies, redirects, request sizes, response sizes and request lifetime. Keep this transport testable without Android.
- [x] Preserve encrypted profiles on read/key/write failures; validate restored cookie domains and restore Secure/HttpOnly attributes.
- [x] Restore strict navigation, deep-link, popup and DRM checks while retaining Lite's WebViewHost lifetime and navigation-token logic.
- [x] Preserve Lite's expanded diagnostic redaction and restore protections for JSON credentials and full URL queries.
- [x] Retain applicable Java/JavaScript/JVM tests, removing proxy/download/Android Auto tests. Add coverage for the new bridge methods, native transport and redaction.
- [x] Point updates to kvmgithub/Spotilol and retain notification-only update behavior.
- [x] Adapt ARM64 signed-release automation to the Java/Kotlin-only application, preserving its key and monotonically increasing version code.
- [x] Run standalone tests, JVM tests, release lint and signed release assembly; inspect the resulting APK and final diff.

The full Android build, 14 JVM tests, release lint and APK metadata/signature verification passed in run 39135407229. Its final certificate comparison exposed a parser mismatch with build-tools 37 output. Run 39136031349 confirmed that the stored signing certificate matches the pinned v1.1.1002 APK. The corrected parser has six regression tests; main release publication remains gated by all checks. Real-device validation of the new Lite-based build is separate.

## Review focus

Foreign origins and subframes must never gain native authority. Lookalike hosts and nonstandard ports must fail validation. Redirects must not move credentials, and response cookies must remain inaccessible to JavaScript. Failed encrypted reads must never lead to data deletion or plaintext writes. New video/volume/player methods must remain functional through the scoped asynchronous bridge.
