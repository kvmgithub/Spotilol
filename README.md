<div align="center">
  <img src="art/bgwelcome.png" alt="Spotilol" style="width: 100%; max-width: 900px; margin-bottom: 20px; box-shadow: 0 8px 32px rgba(0,0,0,0.5);">
</div>

<h1 align="center">Spotilol Lite — hardened fork</h1>

<p align="center">
  Based on <a href="https://github.com/AdNenio/Spotilol-Lite">AdNenio/Spotilol-Lite</a> and the preserved Spotilol 1.2.4 sources. Native bridge, networking and encrypted-profile hardening are retained from kvmgithub/Spotilol.
</p>

---

## What's different from upstream

This fork strips out everything that isn't core playback:

- no Firebase (no analytics, no Crashlytics, no Performance) — nothing to configure, it just builds
- no offline downloads (no YouTube/Innertube pipeline, no mp3/m4a transcoders)
- no Android Auto
- no home screen widgets
- no third-party lyrics API (Spotify's own in-player lyrics are untouched)
- no telemetry setting
- no theme presets, Material You or theme sharing (one fixed dark palette, optional AMOLED blacks)
- no lyrics styling/presets, no custom CSS
- no system picture-in-picture
- the update check only notifies you when a new **fork** version exists — it never opens GitHub by itself

Everything else stays like upstream: ad/telemetry blocking, media notification with lock screen and Bluetooth controls, profiles, sleep timer, AMOLED theme, in-player lyrics, canvas/video podcasts, wake lock, and the rest.

---

## Features

- blocks audio ads, trackers and telemetry
- media notification with play, pause, skip, seek, like, shuffle and repeat
- lock screen and Bluetooth/headset controls
- multiple account profiles and a sleep timer
- in-player lyrics
- AMOLED dark mode
- canvas and video podcasts
- mobile layout tweaks and playlist sorting
- wake lock and power save mode
- update notification for new fork releases

---

## Requirements

- Android 9.0+ (API 28)
- a Spotify account
- Google Chrome / webview (comes with your phone)

---

## Build It Yourself

```bash
git clone https://github.com/kvmgithub/Spotilol
cd Spotilol
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

No Firebase project, no `google-services.json`, no NDK — the plain Android SDK (platform 37 + build tools) is enough.

### Release builds

Signing is read from environment variables:

```bash
export KEYSTORE_FILE="/path/to/upload-keystore.jks"
export KEYSTORE_PASSWORD="..."
export KEY_ALIAS="..."
export KEY_PASSWORD="..."
./gradlew assembleRelease
```

Or, without env vars, from `keystore/keystore.properties` (gitignored, upstream-style) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

---

## Security and releases

The JavaScript bridge is restricted by origin, frame and method. Native HTTP is restricted to approved Spotify HTTPS endpoints, bounded in size and time, and does not follow redirects or expose response cookies. Encrypted profiles fail closed without deleting keys or writing plaintext. Navigation, OAuth popups, deep links and DRM grants are checked centrally. Shared log redaction covers credentials, tokens and URL queries.

The native HTTP transport uses OkHttp for strict request lifetime and redirect handling. The YouTube/download stack remains removed.

Run `bash tests/security/run.sh` and `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`. CI produces signed releases under [Releases](https://github.com/kvmgithub/Spotilol/releases). The published package remains `com.project.lol.fork`; signing identity is checked against v1.1.1002, allowing existing fork installations to update. Fork version numbers use `1.2.<versionCode>`; the functional baseline is Spotilol 1.2.4.

The standalone regression suite requires a JDK, Node.js and Python 3. It also checks certificate matching against legacy and current Android signing-tool output.

Login security checks may navigate to exact HTTPS `challenge.spotify.com`. Challenge documents run without player injections or native bridge authority. Logs redact their session identifiers and record blocked main-frame destinations by hostname only. A reCAPTCHA `requestStorageAccess` warning alone does not establish that its network connection failed.

Automatic update checks query this fork and only show a notification. The original upstream repository is unavailable. Future source updates require review of the actual source tree and the security invariants documented in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Credits

**deviato** reverse-engineered the original Spotifuck. **lyssadev** ported the core logic from smali to Kotlin and maintains Spotilol. This repo is a personal fork for my own use case.

All rights reserved. lyssadev and deviato.
