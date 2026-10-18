# Spotilol UI

Myspoti's native Android interface with Spotilol Lite's feature and security scope. This branch (`myspoti-ui`) is separate from Spotilol Lite on `main`; both apps install side by side.

Base: [kota-zo/myspoti](https://github.com/kota-zo/myspoti) 2.0.0-beta.8 (`5b247b3`, GPL-3.0). Playback still runs in Spotify's Web Player in a hidden WebView (Widevine); search, library, artist, album and playlist screens are native.

## Install

- Package `com.project.lol.fork.ui`, label **Spotilol UI**, signed with the Spotilol fork key.
- Spotilol Lite (`com.project.lol.fork`) stays installed and unchanged. Spotilol UI needs its own sign-in; Lite's saved profiles are not shared.
- APKs are published as pre-releases tagged `ui-v<version>`. Pre-releases never become "latest", so Spotilol Lite's update check ignores them. This variant has no update checker of its own.

## Scope compared to Myspoti

Removed:

- SpotiFLAC/lossless downloads and every download path (Deezer, Tidal, Qobuz lookups, signed sessions, transcoding, download UI)
- Android Auto, the exported artwork provider and external media browsing (the media service is not exported)
- YouTube music videos (NewPipeExtractor)
- Third-party lyrics (LRCLIB, NetEase, Genius) and Google lyric translation; Spotify's own lyrics stay
- In-app updater (`REQUEST_INSTALL_PACKAGES`), donation prompts, home screen widget
- Google Play services code scanner for Jam QR codes (it brought ML Kit and Google's data transport logging); joining a Jam by invite link stays

Added from Spotilol:

- Encrypted account profiles (EncryptedSharedPreferences, fail closed): save the current session, switch without signing in again
- Switchable, redacted diagnostics (Settings → App)
- Spotilol's ad and telemetry blocking, including Spotify's `gabo-receiver-service` events and ad hosts; telemetry gets an empty `200` so the page does not retry

Kept from Myspoti: native screens, library tools, Spotify Connect and Jam, local Recap, sleep timer, Canvas, local files, streaming quality, data and energy saver.

## Security rules

- The page reaches the app only through web message listeners for exactly `https://open.spotify.com`, main frame only. There is no `addJavascriptInterface` fallback; WebViews without listener and document-start support fail closed.
- The control relay repeats only `connect-state`/`track-playback` requests to exact `*-spclient.spotify.com`/`spclient.wg.spotify.com` hosts, with bounded requests and responses, a total deadline, no redirects and no cookies; answers go to the sending document's reply proxy.
- Direct API, client-token, GraphQL-operation and cover requests use strict HTTPS hosts, no redirects and bounded bodies.
- Main-frame navigation is limited to exact HTTPS Spotify and sign-in hosts; Widevine is granted to the player origin only.
- Restored profile cookies are limited to Spotify origins, carry `Secure`, and the auth cookie keeps `HttpOnly`.
- Logs and exported diagnostics go through the shared `LogRedactor`.

Policy, redaction, blocking and profile regressions run as JVM tests in CI.

## Build

The Android project is in `base/` (JDK 17+, Android SDK with compileSdk 37):

```bash
cd base
./gradlew testDebugUnitTest :api:test :api-spotify:test :app:assembleDebug
```

Release signing reads `APK_KEYSTORE`, `APK_KEY_PASSWORD` and `APK_KEY_ALIAS` (CI fills them from `APK_SIGNING_BUNDLE`), else `base/keystore/keystore.properties`. `.github/workflows/spotilol-ui.yml` builds every push to `myspoti-ui` and publishes a pre-release for commits marked `[publish]`.

## Credits and license

GPL-3.0 (see `LICENSE`). Myspoti by kota-zo; UI derived from [Spotui](https://github.com/Spotui/Spotui) and [Metrolist](https://github.com/MetrolistGroup/Metrolist); see `THIRD_PARTY_NOTICES.md`. Security rules ported from [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol).

Not affiliated with or endorsed by Spotify. Blocking ads goes against Spotify's Terms of Use; use at your own risk.
