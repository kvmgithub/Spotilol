# MySpotilol changelog

All notable changes to MySpotilol (formerly Spotilol UI), newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 0.3.0 on. Releases before 0.3.0 used the CI run number as their patch version.

Each version section is the text of the GitHub pre-release tagged `ui-v<version>`; CI adds the commit, package and check note below it. To release, rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` with a higher version: the next push to `myspoti-ui` builds, signs and publishes it.

## [Unreleased]

## [0.11.2] - 2026-10-29

### Fixed
- Crossfade: short dropouts from the middle of a song on, more frequent and longer towards its end. To build the crossfade reserve, Spotify's player plays the song slightly ahead of what is heard, but it is shown the heard time, so it counted the reserve as audio still to play and fetched the next part of the song too late. Past about 5 s of reserve its media element ran out of audio for 1–5 s at a time (log seen: five gaps in two minutes, the first while Spotify answered in 0.3 s). The player now also sees its buffered audio moved back by the reserve, so it fetches by what the element really has ahead. Should the player then fetch the same audio over and over (more than 20 requests in 30 s), the ranges are shown as they are again for that page.
- A headset button while nothing played, or swiping away the paused notification, could crash the app ("Could not find any Service that handles MEDIA_BUTTON"): the playback service now takes these key events itself. It stays unreachable for other apps.

### Diagnostics
- `page xfade` `starve` lines: the media element ran out of audio while playing, with the gap in seconds, the reserve at that moment, whether the buffered ranges were moved, how often the player read them (element / SourceBuffer) and the audio requests in the last 30 s.

## [0.11.1] - 2026-10-27

### Fixed
- A short network drop no longer turns into a long silence. The song's audio running out while the network is gone was taken for a broken Web Audio graph: the boost and the crossfade were switched off until the next start and the player page was reloaded, which then failed without a network and was rebuilt on a new WebView. Now the player waits for the audio data, asks again with a seek every 15 s, and never reloads the page for it; a page that failed to load is only loaded again, not rebuilt.

### Diagnostics
- `audio waits for the network on <song>` with the media element's state, and `no sound through Web Audio while the audio waits for the network: boost and crossfade kept`.

## [0.11.0] - 2026-10-27

MySpotilol drops the features that only work with other Spotify users.

### Removed
- Friend activity.
- Spotify Jam (start, join, invite).
- Blends: creating, joining by link, the Blend line on its page, rename, invite and leave.
- Collaborative playlists: invitation links (sending and joining), "Shared with" and its owner actions, leaving, stopping a collaboration, who added each song, the note on collaborative playlists you cannot add to. A collaborative playlist of yours can still be made private; one you can edit stays editable.
- A playlist page no longer asks Spotify for its members and who added each song.

### Changed
- Short links (spotify.link, spoti.fi) still resolve; a playlist link carrying an invitation opens that playlist like any other link.

## [0.10.0] - 2026-10-27

Features taken from Myspoti 2.1.0 (from its 2.0.2 betas), without extensions or the Immersive look.

### Added
- Collaborative playlists: leave one you joined (playlist menu, or your row in "Shared with"); as the owner, make a member a collaborator or a listener, remove them, or stop collaborating (collaborators become listeners on a private playlist and are removed from a public one).
- Add a whole playlist or album to one of your playlists, from its menu or the library's long-press menu, with the choice of what to do about songs already in it.
- Library filter chips: Playlists, Artists, Albums, Songs.
- Listening stats from Spotify (top artists and tracks over 4 weeks, 6 months and a year) beside the phone's own Recap, minutes per week, and a "your #N artist" line on artist pages.
- Friend activity (Home menu): what the people you follow are listening to.
- Likes and saves made without a connection are kept and sent when the network is back; a sign-out or profile switch drops them.
- Polish, Russian and Chinese for Myspoti's texts (MySpotilol's own texts stay English there).

### Changed
- Your own playlists have no save button.
- Back buttons on playlist, album and stats pages are easier to hit.
- Playlists of up to 200 songs open complete; a playlist read in full, Liked Songs and albums opened before are kept for a while and open at once, refreshed behind (a pull-to-refresh reads everything again).

## [0.9.0] - 2026-10-27

Fixes taken from Myspoti 2.1.0, the stable release of the 2.0.2 betas. Its extensions, the Immersive look, the Connect mirror for extension songs, play reports sent by the app (Spotify's `melody` telemetry stays blocked), the update checker and the Chromium network routes for refused commands are not part of MySpotilol.

### Fixed
- Ads: an ad marked only by its metadata (`is_advertisement`, an `spotify:ad:` uri, an `ad_id`) is left out of the queue like one typed as an ad; a song whose title merely says "ad" never is.
- A page reloaded to get past a stuck ad stays muted until a real song plays on it, so an ad it starts with is no longer heard.
- A muted ad that plays is first played past instead of reloading the player at once.
- A song picked just before an ad that ended in a reload is played on the new page instead of being lost.
- After a reload, playback waits for the new page instead of sending the next song to the page going away (the music stayed off until Play was pressed).
- While Spotify limits audio licences, each song the page moves on to by itself is paused once, instead of the page skipping through the queue.
- The player page always sees itself visible, so its timers are not throttled with the screen off or the app in the background.
- The repeat mode is kept from one start of the app to the next.
- A song played from search goes on with related songs (Spotify's autoplay) instead of another list.
- Tapping the title of a radio song opens its album, also when the radio sent the song without one; a radio's "playing from" header no longer opens Liked Songs.
- A playlist or album page that failed to load has a Try again button; an album that could not be found has a back button and Try again.
- When the app was killed before the player page could sign its device off, the next start takes that device off the account: no idle copy of this phone stays among the Spotify Connect devices.
- A page answering without a field it always had (a new Web Player build) is an error the app recovers from, not a crash.

### Added
- Headphone and car buttons skip 15 s forward and back on podcast episodes, with 15 s buttons in the notification (Settings, Playback; off: next and previous episode).
- The notification's buttons and channel are translated; home cards say "Album" and "Playlist" in the app's language.
- Recent searches are kept for three minutes: typing back to an earlier text shows its results at once.

### Diagnostics
- `ad break started`, `ad break over after <n> s`, `ad kept in the queue: <why>`, `a song plays after the reload`, `playback held (Spotify limiting): <song> paused`, `previous player device taken off`.
- A stalled player logs its media element (ready and network state, error).
- Repeated `page state` lines of a buffering song and successful playback requests are left out of the log; URLs in log lines lose their query string.

## [0.8.6] - 2026-10-26

Three changes taken from Myspoti 2.0.2-beta.7; the rest of that beta (the Immersive look and its queue, the last session of extension and downloaded songs, extensions) is not part of MySpotilol. Its fix for previous right after a skip only concerns extension songs.

### Fixed
- When Spotify skips through several songs in a row that it cannot play (each after a few seconds, nobody asking for it), playback is paused and a message says so, instead of the page running through the whole queue.
- The lyrics follow the song after a skip also when title and artist stay the same (another version of the song).

### Added
- A clear button in the search field of the search page.

### Changed
- The clear buttons of the library, "Find in…" and "Add songs" fields are easier to hit and put the cursor back with the keyboard up.

### Diagnostics
- `track change: <old> -> <new> (<reason>, left at <position> of <length>)` for every change of song, with the reason (a command, an ad break, the song's end, or the page on its own) and, with crossfade on, the reserve still heard.

## [0.8.5] - 2026-10-26

Two fixes taken from Myspoti 2.0.2-beta.6; the rest of that beta (extensions, Android Auto, the update popup, the Immersive look, Settings tabs) is not part of MySpotilol.

### Fixed
- A crash at playback start when the cover for the notification and lock screen had been released by the system ("cannot use a recycled source in createBitmap"): such a cover is fetched again, and the media session falls back to metadata without artwork instead of failing.
- Play, next and previous from Bluetooth or headset buttons while the app sat paused in the background: the playback service now starts for real, so playback no longer stops by itself a few seconds later.
- The notification, lock screen and Bluetooth devices get the large cover, also for a song picked from a list that carried only the small one.

### Diagnostics
- `media session: <command>` for every command from the notification, lock screen or a Bluetooth device.

## [0.8.4] - 2026-10-25

Three fixes taken from Myspoti 2.0.2-beta.5; the rest of that beta (extensions, the Immersive look, a network stack from Google Play services) is not part of MySpotilol.

### Fixed
- Playback with the screen off: the app keeps the CPU and Wi-Fi awake while a song plays or loads, so the player page's timers and audio downloads no longer stall in power saving.
- While Spotify limits audio licences, the player page no longer skips through the following songs on its own: its licence requests are answered by the app until the hold is over, and Spotify sees none of them.
- The player shows the large cover of a song picked from a list that only carried the small one, instead of a stretched thumbnail.

### Diagnostics
- The page's recurring font preload and storage-access warnings are left out of the log.

## [0.8.3] - 2026-10-23

Myspoti 2.0.2-beta.2 merged (Myspoti has no final 2.0.2 yet; its beta.1 left Spotify's player without a device, beta.2 undoes that part).

### Fixed
- A play Spotify acknowledges but never starts (the song stays at 0:00) now goes on to the fallbacks after 10 s instead of leaving a silent player.
- A song left paused right after an ad break, without anyone pausing it, starts by itself.
- While another device of the account plays (Spotify Connect, the desktop app), the app no longer seeks, skips or sets the volume on it; the computer stopped jumping tracks.
- Repeat keeps the mode you chose after a new play or a reloaded page.
- Sign-in on a slow phone: the wait for the session only counts once the player page is ready, so the app no longer sends you back to the login page in a loop.
- The + of a song in a search or an album is no longer shown as saved because an own playlist plays.
- Recents lists a play whose playlist or album row Spotify did not announce instead of dropping it.

### Added
- If the phone's WebView has no working Widevine (which every Spotify stream needs), the app says so after a play fails, and how to fix it.
- Playlists without a cover show the mosaic of their songs' albums.

### Changed
- App font: Manrope instead of Figtree (Cyrillic and Greek text now uses the app's font too).
- Big playlists show their own controls and members sooner.

### Not taken over
- Myspoti's download and Android Auto fixes (both are not part of MySpotilol).

## [0.8.2] - 2026-10-22

Myspoti 2.0.1 merged.

### Fixed
- Accounts on which Spotify refuses every play the app relays (402, free accounts on some phones and in some countries): a refused play or transfer is sent once more from the player page itself, and later commands keep going that way if music then plays within 10 seconds. Otherwise the app goes back to relaying them.

### Diagnostics
- `refused command sent again from the page -> <status>` and `commands from the page: kept…` / `dropped…`.

## [0.8.1] - 2026-10-22

### Changed
- A media element of the player joins the audio graph (boost, crossfade) when it starts playing, before its first sound. Until now it joined on the next state report: an element the page created for a new song played its first moments past the graph, at full level and outside the crossfade.

### Diagnostics
- `page xfade` `level` lines, three per crossfade (after 0.5 s, halfway, 1.5 s before its end): the level of the old and the new song on their paths (dBFS, before their fades), the fades at that moment and the level that is heard. They show whether the old song's end is really heard under the new one.
- `overlap` lines carry the old path's delay, `overlap` and `build` lines the media element (`el`); a new element joining the graph is noted (`page boost: {"routed": n}`).
- A flush during a crossfade is always noted (`during: overlap`, seconds after the switch): it ends the old song's tail at once, and was silent before when the new song had no reserve yet.

## [0.8.0] - 2026-10-22

Myspoti 2.0.0 merged, as far as it fits Spotilol's scope.

### Ads
- Ads are left out of the queue Spotify sends to the player, before the page sees it: the song before an ad goes on to the song after it. When Spotify points both the end and the skip of a song at an ad, the next song is played just before the end, so the ad never becomes current. Until now an ad that could not be skipped reloaded the player, and the crossfade reserve was lost with it.
- With crossfade on, that early switch counts as the song's end: the two songs still overlap.

### Playback and stability
- The hidden player page is no longer slowed down by Chromium and no longer redrawn with every frame of the app: fades, resumes and song ends keep their timing with the screen off, and the player screen is smoother and lighter on the battery.
- A player page that fails to load (server error, rate limit, network error) or never finishes loading is loaded again with growing pauses; a page whose player never asks for DRM is moved to a new WebView.
- While Spotify rate-limits audio licences, playback and repairs pause for a while instead of feeding the limit.
- Repairs wait while a sign-in page is open, Google, Facebook and Apple sign-in included; whether you are signed in is asked of the page, never read from a cached token.
- Fast skips land on the song the taps add up to; a song picked just before an ad plays after it; a refused play (402) tries playback on this phone and the list on shuffle before giving up.
- Pause fades out and resume fades in along the ear's curve, the resume starting with the sound. With the volume boost on, these fades also act after the limiter.

### Added
- Collaborative playlists: invite links, members and who added each song; a shared invitation opens the playlist with "Join".
- Blend invitations open in the app and can be joined.
- Add songs to a playlist from a search under its cover.
- Share as a Spotify Code.
- Shared playlist, album, artist and podcast links open their page instead of playing.
- Streaming quality per network (Wi-Fi and mobile data), Canvas on Wi-Fi only, and "Keep playing after closing the app" (off by default: closing the app from the recent apps stops the music).
- A song by several artists opens a picker of its artists.
- A new welcome screen and setup, a player that grows out of the mini player, page transitions.

### Changed
- Notification artwork is cached and retried; covers come only from Spotify's image servers or the phone's media store.
- German texts for Recents, podcasts and playlist editing now come from Myspoti itself.

### Not taken over
- Word-by-word lyrics from LyricsPlus, lrc.red and Kugou, and Genius song meanings: they are third-party lookups. Spotify's own lyrics stay.
- Music videos and their sound, downloads, the update and donation prompts, the diagnostic report export.

### Diagnostics
- `ad left out: …`, `ad ahead: …`, `refusal headers: …`, licence-limit and page-retry lines; crashes and freezes of the last run and main-thread stalls appear in the log.

## [0.7.4] - 2026-10-21

### Fixed
- Crossfade: after a song change, a skip or a seek the reserve kept its old length. The app read the audio path's delay back from the browser, which still reported the value from before the change, and scheduled that old value again. The new song then started with the end of the previous one once more, its position stood still until stall recovery jumped back, and skips and seeks sounded about six seconds late. The app now keeps the delays it schedules itself and never reads them back.

## [0.7.3] - 2026-10-20

### Fixed
- Crossfade: a short dropout when the fade ended. At that moment the new song moved from one audio path to the other and its reserve started with a speed change, both while it played at full level. The new song now stays on its own path from its first second, and its speed is set right at its start, under the fade-in; the next song change uses the other path. Speed changes during a song are also rarer.

## [0.7.2] - 2026-10-20

### Fixed
- Crossfade: Spotify's player sets its playback speed again about every ten seconds. The app took that as a change, dropped its own speed and gave up after three times, so the reserve stopped at 2–3 s. Setting the speed the player already has now changes nothing; only another speed (a podcast speed) still ends the crossfade for that episode.

## [0.7.1] - 2026-10-20

### Fixed
- Crossfade: while the reserve was built, Spotify's player saw its song run faster than the clock and reported its state about four times a second, to the app and to Spotify's servers. The player now sees the speed it set itself and the time that is heard; only the last 1.5 s of a song show the real time, so it still sees the song end.

### Diagnostics
- `page xfade` `overlap` and `end` lines carry the target length and how often the app and the page changed the speed during the song; `reset` lines show the page changing the speed itself.

## [0.7.0] - 2026-10-20

Test release for a real crossfade.

### Changed
- Crossfade now overlaps: the end of a song fades out while the next one already plays and fades in, as in Spotify's apps. The Web Player has only one player, so the app builds a reserve while a song plays: Spotify plays it 3–12 % faster, the app's audio path slows it back down (tempo and pitch unchanged), and the song's last seconds wait in the reserve until the next one starts.
- What is heard lags Spotify by up to the crossfade length; the position, the slider and the lyrics follow what is heard. Pause and play act at once.
- Skips and seeks change at once, without a crossfade. After a jump close to the end of a song, the reserve may not reach the full length.
- Crossfade uses the same audio path as the volume boost; if no sound comes through it on a phone, both stay off until the app restarts.

### Diagnostics
- `page xfade: …` lines: `build` (speed and target per song), `overlap` (seconds crossfaded), `flush` (reserve dropped by a skip or seek), `unsupported` (why a song is not crossfaded).

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
