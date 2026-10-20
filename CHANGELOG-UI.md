# MySpotilol changelog

All notable changes to MySpotilol (formerly Spotilol UI), newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 0.3.0 on. Releases before 0.3.0 used the CI run number as their patch version.

Each version section is the text of the GitHub pre-release tagged `ui-v<version>`; CI adds the commit, package and check note below it. To release, rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` with a higher version: the next push to `myspoti-ui` builds, signs and publishes it.

## [Unreleased]

## [0.6.1] - 2026-10-20

### Fixed
- Crossfade with the volume boost on: the fade ran before the limiter, which held a boosted song at its ceiling until the level dropped below the threshold, so the first half of the fade went unheard and the rest fell away at once. Fades, the crossfade and skip fades now act after the limiter while the boost is on; without the boost nothing changes.
- The mini player's crossfade switch turns back on at the length used before switching off, also when it was chosen in Settings before 0.6.0 (it fell back to 6 s).

## [0.6.0] - 2026-10-20

### Added
- Crossfade switch in the mini player (the crossing curves next to the devices button): one tap turns it off, the next turns it back on at the last length chosen in Settings (6 s at first). Green while on.

### Diagnostics
- `crossfade due on <song>: …` once per song when it enters its fade window, with what decides the fade (time left, active device, next song, page volume); `crossfade length …` whenever the setting reaches the player.
- The app's state polls no longer write every answer to the log, so the diagnostic log covers its full ten minutes again.

## [0.5.0] - 2026-10-20

### Added
- Artist pages show "Featuring <artist>" (This Is, the artist's radio), the artist's own playlists and "Discovered on", as in Spotify's apps.
- A search for an artist's name lists their "Featuring" playlists right under the artist; the Playlists filter includes them too.

### Changed
- Volume boost: confirmed on a Pixel 9 Pro XL. Chromium passes Spotify's decrypted audio to the amplifier and applies the player's own volume there, so fades and ad muting keep working while boosted.

### Fixed
- A late "network lost" for the previous network no longer leaves the app offline on the new one; stall recovery and crossfade paused while it thought so.
- Search no longer logs every query that a newer one replaced as a failure.

### Diagnostics
- `crossfade skipped on <song>: <reason>`, once per song, when a fade is due but does not run (another device active, nothing queued after it, page volume at 0).
- The status line shows `online=` (the app's view of the network); the volume boost probe reports the player's volume with its result.

## [0.4.0] - 2026-10-20

Test release for the volume boost. Whether it works depends on the phone's WebView passing Spotify's protected audio to Web Audio, which only a device test can show.

### Added
- Volume boost (Settings → Playback): 100 % (off) to 200 %. Above 100 % the music plays through an amplifier with a limiter, on this phone only; other Spotify Connect devices are not affected. Android's volume warning does not see this boost.
- If no sound comes through the amplifier during 10 seconds of playback, the app turns the boost off, reloads the player and plays on where it was; Settings then says so.

### Diagnostics
- New log lines: `boost …` (level, connected media elements, audio state) and `page boost: …` (sound through the amplifier or not, and the kind of audio source).

## [0.3.0] - 2026-10-20

### Changed
- New name: MySpotilol (was Spotilol UI). The package stays `com.project.lol.fork.ui`, so this version installs over the previous one and keeps the sign-in, saved profiles and settings.
- New launcher icon, splash screen and notification icon: pixel-art headphones with an equalizer. Themed icons get a matching monochrome version.
- Version numbers follow Semantic Versioning from this release on; release notes are in English.
- About lists the three projects the app is built from, each with a link: Myspoti, Spotilol and Spotilol Lite.
- Profile menu: the subtitles no longer mention a Discord community or downloads.

### Fixed
- German texts for Recents, show and episode pages, the podcast player, crossfade and playlist editing; Myspoti beta.9 shipped them in English only.
- The welcome screen still showed the name "Myspoti", and shared track cards carried the "Myspoti" wordmark.

## [0.2.1010] - 2026-10-20

Myspoti 2.0.0-beta.9 merged, as far as it fits Spotilol's scope.

### Ads and fingerprinting
- The Spotify page's ad managers are switched off at runtime, again at every ad break.
- The device registration goes to Spotify unchanged. Earlier versions stripped the ad formats from it, which was suspected of getting Free accounts blocked with `402 COMMAND_FAILED`.
- The `X-Requested-With` header (the app's package name) is switched off for the page's requests where the WebView supports it.
- The browser identity follows the installed WebView version instead of a fixed Chrome 155. A one-time new sign-in may be needed.
- The client device id persists across restarts.
- Relayed playback commands carry the same browser headers as the page. Cookies go along only when Spotify's own request asks for them; `Set-Cookie` is still discarded.

### Stability
- When the WebView's renderer crashes, the app rebuilds the page instead of going silent.
- Sign-in waits for a real session token and no longer hangs.
- Fast skips during ads or reloads land on the right track; podcast episodes no longer get stuck at the end.

### Added
- Crossfade (Settings → Playback: off, 3–12 s).
- Recents in the profile menu, grouped like the official app.
- Show and episode pages and a podcast player (±15 s, speed, mark as played).
- Remove tracks from your own playlists or add them to another one; options menu on artist pages.
- Missing covers and profile pictures load; upcoming lyric lines are readable on dark cards; navigation bar without the light strip.

### Not taken over
- Podcast downloads, the update check, the diagnostic report file export (the log view covers it), the disabled audio proxy.
- The phone's name in Spotify's device list: the app still appears as "Web Player (Chrome)".

## [0.1.1009] - 2026-10-19

### Fixed
- During a network outage (Wi-Fi/mobile switch, VPN reconnect, DNS failure) stall detection reloaded the page every few seconds. It now waits until Spotify is reachable again and then tries once.
- On a network change the app drops open connections, so requests do not hang on the old route until they time out.

### Diagnostics
- New log lines: `network lost`, `network changed`, `offline: stall recovery waits…`, `network back…`.

## [0.1.1008] - 2026-10-19

### Fixed
- A pause that Spotify dropped while buffering is sent again, up to twice.
- GrapheneOS (secure app spawning): if the first page load is lost, the app reloads at once instead of after 4 seconds.

### Diagnostics
- Blocked telemetry shows once per host and path as `telemetry blocked: …`.

## [0.1.1007] - 2026-10-19

### Changed
- The floating log button is gone. The redacted logs of the last 10 minutes open under Settings → App → Diagnostic logs, with copy and share.
- The last settings entries scroll clear of the mini player and the navigation bar.
- Texts call the app "Spotilol UI" and no longer mention downloads or music videos.

## [0.1.1006] - 2026-10-19

### Fixed
- Profile switch: the previous account's page is closed before its cookies are restored, so it cannot write them back.

## [0.1.1005] - 2026-10-19

### Removed
- QR scanner for Jam invites (Google Play services with ML Kit and Google's data transport logging). Joining a Jam by invite link still works.

## [0.1.1004] - 2026-10-19

First test build: Myspoti 2.0.0-beta.8's native interface with Spotilol Lite's feature and security scope. Installs next to Spotilol Lite and needs its own sign-in.

### Taken over from Spotilol
- The page reaches the app only through web message listeners for exactly `https://open.spotify.com`, with no JavaScript interface fallback.
- Playback relay only to exact Spotify hosts, with size and time limits and no redirects; answers go only to the requesting document.
- Navigation, Widevine access and direct requests by exact HTTPS host; logs redacted.
- Blocklist as in Spotilol, including Spotify's event telemetry (`gabo-receiver-service`).
- Encrypted account profiles (Settings → Accounts) and switchable diagnostic logs.

### Removed compared to Myspoti
- SpotiFLAC and all other downloads, Android Auto, YouTube music videos, third-party lyrics and translation, in-app updater, donation prompts, widget.
