# MySpotilol

Myspoti's native Android interface with the feature and security scope of Spotilol and Spotilol Lite (formerly "Spotilol UI"). This branch (`myspoti-ui`) is separate from Spotilol Lite on `main`; both apps install side by side.

Base: [kota-zo/myspoti](https://github.com/kota-zo/myspoti) 2.0.2-beta.2 (`37dd1ee`, GPL-3.0), merged from the vendor branch `myspoti-upstream`, with selected fixes and features of 2.1.0 (`320ba2a`) ported by hand (its extensions and Immersive look are left out). Playback still runs in Spotify's Web Player in a hidden WebView (Widevine); search, library, artist, album and playlist screens are native.

## Install

- Package `com.project.lol.fork.ui`, label **MySpotilol**, signed with the Spotilol fork key. Versions up to 0.2.1010 were called Spotilol UI; they upgrade in place.
- Spotilol Lite (`com.project.lol.fork`) stays installed and unchanged. MySpotilol needs its own sign-in; Lite's saved profiles are not shared.
- APKs are published as pre-releases tagged `ui-v<version>`. Pre-releases never become "latest", so Spotilol Lite's update check ignores them. This variant has no update checker of its own.
- Versions follow [Semantic Versioning](https://semver.org) from 0.3.0 on: patch for fixes, minor for features or larger upstream merges, 1.0.0 once it replaces Spotilol Lite in daily use. Earlier releases used the CI run number as patch version.
- What changed in each release: [CHANGELOG-UI.md](CHANGELOG-UI.md), also shown on each GitHub release. `CHANGELOG.md` is Myspoti's own history, kept as upstream ships it.

## Scope compared to Myspoti

Removed:

- SpotiFLAC/lossless downloads and every download path (Deezer, Tidal, Qobuz lookups, signed sessions, transcoding, download UI)
- Android Auto, the exported artwork provider and external media browsing (the media service is not exported)
- YouTube music videos (NewPipeExtractor), including 2.0.0's clip sound
- Third-party lyrics (LRCLIB, NetEase, LyricsPlus, lrc.red, Kugou, Genius), Genius song meanings and Google lyric translation; Spotify's own lyrics stay, word by word where Spotify times them
- In-app updater (`REQUEST_INSTALL_PACKAGES`), donation prompts, home screen widget
- Google Play services code scanner for Jam QR codes (it brought ML Kit and Google's data transport logging); joining a Jam by invite link stays
- Podcast episode downloads (beta.9), the in-app update settings and the diagnostic-report export row (the redacted log view covers it)
- The phone's own device name in Spotify's device lists (beta.9 sends it; this variant keeps the Web Player's name)
- From 2.0.2/2.1.0: extensions (third-party JavaScript, audio and download sources), the Immersive look, the Connect mirror for extension songs, play reports sent by the app (`melody` stays blocked), Cronet and the Chromium network routes for refused commands, the update popup

Added from Spotilol:

- Encrypted account profiles (EncryptedSharedPreferences, fail closed): save the current session, switch without signing in again
- Switchable, redacted diagnostics (Settings → App)
- Spotilol's ad and telemetry blocking, including Spotify's `gabo-receiver-service` events and ad hosts; telemetry gets an empty `200` so the page does not retry

Added in MySpotilol: a volume boost up to 200 % through a gain and a limiter in the page (Settings → Playback); it turns itself off and reloads the player when no sound comes through Web Audio. A real crossfade: the page plays a song slightly faster while the graph slows it back down (tempo and pitch unchanged), and the reserve that builds up lets the end of one song overlap the start of the next; a switch in the mini player turns it on and off.

Kept from Myspoti: native screens, library tools, Spotify Connect and Jam, local Recap, sleep timer, Canvas, local files, streaming quality, data and energy saver.

From beta.9: the page's ad managers switched off at runtime (the device registration is no longer altered), X-Requested-With suppressed, renderer-crash recovery, per-install browser identity that follows the WebView version, a kept client device id, relayed requests with the page's own browser headers, login that waits for a real token, crossfade, Recents, show/episode pages and the podcast player, playlist add/remove, and the UI fixes.

From 2.1.0: ads marked only by their metadata left out, a reload past a stuck ad that stays muted until a song plays, licence holds that pause instead of skipping, the page always visible to itself, no idle copy of the phone left among the Connect devices, 15 s headphone skips on podcasts, collaborative playlists on the owner's side, adding a playlist or album to a playlist, library filter chips, listening stats and friend activity, likes made offline sent later, page caches, Polish, Russian and Chinese.

From 2.0.0: ads left out of Spotify's track-playback queue in the relay before the page sees them, and the next song played just before an ad that cannot be left out; a player page Chromium never throttles; failed page loads retried; licence rate limits held; collaborative playlists, Blends, add songs, Spotify Codes; per-network streaming quality; the new welcome screen, player and page animations.

## Security rules

- The page reaches the app only through web message listeners for exactly `https://open.spotify.com`, main frame only. There is no `addJavascriptInterface` fallback; WebViews without listener and document-start support fail closed.
- The control relay repeats only `connect-state`/`track-playback` requests to exact `*-spclient.spotify.com`/`spclient.wg.spotify.com` hosts, with bounded requests and responses, a total deadline and no redirects. Cookies go out only when the page's own fetch asks for them (`credentials: 'include'`), Set-Cookie is never stored or returned; answers go to the sending document's reply proxy.
- Direct API, client-token, GraphQL-operation and cover requests use strict HTTPS hosts, no redirects and bounded bodies. Shared short links (spotify.link, spoti.fi) are followed by hand over HTTPS on those hosts only, for at most four hops.
- Main-frame navigation is limited to exact HTTPS Spotify and sign-in hosts; Widevine is granted to the player origin only.
- Restored profile cookies are limited to Spotify origins, carry `Secure`, and the auth cookie keeps `HttpOnly`.
- Logs and exported diagnostics go through the shared `LogRedactor`.

Policy, redaction, blocking and profile regressions run as JVM tests in CI.

## Credits and lineage

- [Myspoti](https://github.com/kota-zo/myspoti) by kota-zo: the native interface, with UI derived from [Spotui](https://github.com/Spotui/Spotui) and [Metrolist](https://github.com/MetrolistGroup/Metrolist).
- Spotilol by lyssadev, after deviato's Spotifuck; security rules, blocking and encrypted profiles from [kvmgithub/Spotilol](https://github.com/kvmgithub/Spotilol).
- [Spotilol Lite](https://github.com/AdNenio/Spotilol-Lite) by AdNenio: the lean feature scope.

## Build

The Android project is in `base/` (JDK 17+, Android SDK with compileSdk 37):

```bash
cd base
./gradlew testDebugUnitTest :api:test :api-spotify:test :app:assembleDebug
```

Release signing reads `APK_KEYSTORE`, `APK_KEY_PASSWORD` and `APK_KEY_ALIAS` (CI fills them from `APK_SIGNING_BUNDLE`), else `base/keystore/keystore.properties`. `.github/workflows/spotilol-ui.yml` builds every push to `myspoti-ui`.

Releasing: collect notes under `## [Unreleased]` in `CHANGELOG-UI.md`, then rename that heading to `## [X.Y.Z] - YYYY-MM-DD` with a higher version and push. CI takes the newest version from the changelog (versionCode = MAJOR·1,000,000 + MINOR·1,000 + PATCH) and publishes it once as `ui-vX.Y.Z`; later pushes of the same version only build. Editing a published section updates that release's text on the next push.

## License

GPL-3.0 (see `LICENSE`); third-party notices in `THIRD_PARTY_NOTICES.md`. The launcher icon is an original pixel drawing.

Not affiliated with or endorsed by Spotify. Blocking ads goes against Spotify's Terms of Use; use at your own risk.
