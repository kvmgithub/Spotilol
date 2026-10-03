<div align="center">
  <img src="art/bgwelcome.png" alt="Spotilol" style="width: 100%; max-width: 900px; margin-bottom: 20px; box-shadow: 0 8px 32px rgba(0,0,0,0.5);">
</div>

<h1 align="center">Spotilol</h1>

<p align="center">
  <a href="https://github.com/lyssadev/Spotilol/stargazers">
    <img src="https://img.shields.io/github/stars/lyssadev/Spotilol?style=for-the-badge&logo=starship&labelColor=0d0d0d&color=1DB954" alt="stars"/>
  </a>
  &nbsp;
  <a href="https://github.com/kvmgithub/Spotilol/releases">
    <img src="https://img.shields.io/github/downloads/kvmgithub/Spotilol/total?style=for-the-badge&logo=download&labelColor=0d0d0d&color=1DB954" alt="downloads"/>
  </a>
  &nbsp;
  <a href="https://github.com/kvmgithub/Spotilol/releases/latest">
    <img src="https://img.shields.io/github/v/release/kvmgithub/Spotilol?style=for-the-badge&logo=github&labelColor=0d0d0d&color=1DB954" alt="version"/>
  </a>
  &nbsp;
  <a href="https://github.com/lyssadev/Spotilol/forks">
    <img src="https://img.shields.io/github/forks/lyssadev/Spotilol?style=for-the-badge&logo=git&labelColor=0d0d0d&color=1DB954" alt="forks"/>
  </a>
  &nbsp;
  <a href="https://github.com/lyssadev/Spotilol/commits/main">
    <img src="https://img.shields.io/github/last-commit/lyssadev/Spotilol?style=for-the-badge&logo=git&labelColor=0d0d0d&color=1DB954" alt="last commit"/>
  </a>
  &nbsp;
  <a href="https://deepwiki.com/lyssadev/Spotilol">
    <img src="https://deepwiki.com/badge.svg" alt="DeepWiki" style="height: 28px;"/>
  </a>
  &nbsp;
  <a href="https://discord.gg/95dAE2UkqP">
    <img src="https://img.shields.io/badge/Discord-join-1DB954?style=for-the-badge&logo=discord&logoColor=white&labelColor=0d0d0d" alt="discord"/>
  </a>
</p>

<p align="center">
  an Android app that wraps Spotify's web player with built-in adblocking — no root, no shady mods, just your Spotify account on a slick WebView.
</p>

<p align="center">
  ported from smali to clean Kotlin by <strong>lyssadev</strong>, based on deviato's <strong>Spotifuck</strong>. free, open-source, and it just works.
</p>

---



## Download

Every commit pushed to `main` automatically runs the regression tests, builds a signed ARM64 release APK, and publishes it with a SHA-256 checksum under [Releases](https://github.com/kvmgithub/Spotilol/releases). The package `com.project.lol.fork` installs alongside the original app and previous test APKs; its separate app data requires a fresh login. Later fork releases use the same signing key and install as updates.

For Pixel 9 Pro XL / GrapheneOS, download the `Spotilol-arm64-*.apk` asset. Upstream APKs do not contain the changes below.

## Preview

<div align="center">
  <img src="art/spotilol_ss1.jpg" alt="screenshot 1" width="30%" style="max-width: 250px; margin: 4px; border-radius: 12px;" />
  <img src="art/spotilol_ss2.jpg" alt="screenshot 2" width="30%" style="max-width: 250px; margin: 4px; border-radius: 12px;" />
  <img src="art/spotilol_ss3.jpg" alt="screenshot 3" width="30%" style="max-width: 250px; margin: 4px; border-radius: 12px;" />
</div>

---

## Features

- blocks audio ads & telemetry
- media notification: play/pause, skip, seek, like/unlike, shuffle, repeat with custom actions
- **Android Auto**: browse your playlists, albums, artists, and podcasts; search and play from the car dashboard
- **offline downloads**: download songs and play them offline — audio is sourced via the InnerTube API
- lock screen, Bluetooth, and Wear OS controls
- autoplay modes: off, once at start, or permanent
- mobile-friendly CSS/JS layout tweaks
- AMOLED dark mode (pure black)
- sleep timer
- update checker (auto & manual)
- multiple account profiles
- browse your library through Spotify's pathfinder API
- picture-in-picture (PiP) support
- wake lock controls & power save mode

---

## Requirements

- Android 9.0+ (API 28)
- a Spotify account (free or premium)
- Google Chrome / WebView (comes with your phone)

---

## Quick Start

install the APK, open it, done. Spotilol runs in **normal mode** by default — no certificate, no setup, no "Certificate Required" screen. it just works out of the box.

---

## Proxy MITM Mode (optional)

want the full fingerprint treatment? flip the mode in **Settings → Connection Mode → "MITM Proxy (Certificate)"**. the app restarts and walks you through the cert install.

### The Certificate Thing

Spotilol generates a local CA cert to rewrite request headers; this does not guarantee that Spotify cannot detect the WebView. it lives on your device, stays on your device.

1. open Spotilol in proxy mode — you'll see the **"Certificate Required"** screen
2. tap **"Export .pem"** to save it to your Downloads
3. go to **Settings > Security > Encryption & Credentials > Install a certificate > CA certificate**
4. find `spotilol_ca.pem` in your Downloads and tap it
5. it'll warn you about network monitoring — tap **"Install anyway"**
6. come back to Spotilol and tap **"Check"**. if it worked, you're in.

> **Note:** if you ever clear your device's credential storage (like after a factory reset), you'll have to do this again.

---

## Build It Yourself

```bash
git clone https://github.com/kvmgithub/Spotilol
cd Spotilol
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Differences from upstream

Based on upstream version 1.1.8 (`1e75b65`).

| Area | Changes in this fork and reason |
| --- | --- |
| Player bridge | Exact HTTPS origin and main-frame checks replace the global native JavaScript interface. The login page can only signal login. Older WebViews receive an update message instead of an insecure fallback. |
| Native requests | Spotify host allowlists, disabled redirects, restricted headers/cookies, asynchronous execution, timeouts and bounded bodies/queues limit privileged requests and resource exhaustion. Cookie response headers stay outside JavaScript. |
| Navigation and DRM | Parsed deep-link/OAuth allowlists, guarded injections and callbacks, restricted popup settings, and player-only protected-media permission prevent other pages from inheriting player privileges. |
| Accounts and proxy identity | Encrypted storage fails closed. Unreadable profiles cannot be overwritten, session cookies restore Secure/HttpOnly, and an unreadable CA is preserved while the app falls back visibly to normal mode. |
| Local proxy and certificates | CONNECT hosts/port, workers and HTTP framing are bounded; malformed or ambiguous framing is rejected. TLS failures close sockets, certificate dates use an explicit locale, and user CA trust is scoped to supported domains. |
| Media and downloads | Trusted media-controller/package checks protect library access. Media queries are JSON-quoted and expire; online/offline voice-search playback is supported. Receivers are non-exported and download paths accept validated Spotify IDs only. |
| Compatibility and startup | WebView capability checks, released proxy executors, API 28 transliteration fallback, locale-aware Compose labels, and a linear JavaScript delimiter scan improve compatibility and avoid quote-heavy startup stalls. |
| Privacy | Firebase Analytics, Crashlytics, Performance and their build plugins are removed. Recognized credentials and URL queries are redacted from local diagnostics; explicit account-name logging is removed. This does not remove Spotify's own server-side data collection. |
| Delivery | Each `main` commit produces a signed ARM64 APK and release with checksum. Update checks and release links point to this fork. |

No `google-services.json` is needed. Diagnostic logs remain local and opt-in in release builds; inspect exports before sharing. The player requires an up-to-date Android System WebView with AndroidX web-message and document-start support.

See [security review](docs/security-review.md) for exact trust boundaries, regression coverage and remaining limitations. The upstream contribution retains Firebase telemetry and original upstream release links; telemetry removal and fork release automation are separate.

### Release signing setup

Create repository Actions secret `APK_SIGNING_BUNDLE` containing a JSON object with `keystore` (base64-encoded PKCS12 keystore) and `password` (a single-line keystore/key password). Its signing alias must be `release`. Keep a private backup; never commit the keystore or password. The workflow decodes it into temporary storage, masks the password and deletes the local copy after building. Keep the workflow filename and version offset stable so Android version codes continue increasing.

## Contributing

contributions are welcome. open issues, throw PRs, suggest stuff — free for all.

---

## Credits

**deviato** reverse-engineered the original Spotifuck. **lyssadev** ported the core logic from smali to Kotlin and maintains this project.

all rights reserved — lyssadev & deviato.