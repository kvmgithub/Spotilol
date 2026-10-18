# Changelog

## Spotilol UI 0.1 — 2026-10-19

First variant build on Myspoti 2.0.0-beta.8 (`5b247b3`): package `com.project.lol.fork.ui`, next to Spotilol Lite.

- Spotilol security rules: listener-only bridge without fallback, bounded relay without redirects or cookies, exact-host navigation, player-only Widevine, bounded direct requests, shared log redaction.
- Spotilol blocking scope: Spotify ad hosts, ad media, `gabo-receiver-service` events, pixels and trackers.
- Removed: SpotiFLAC downloads, Android Auto, YouTube music videos, third-party lyrics and translation, in-app updater, donation prompts, widget, Play services QR scanner (ML Kit logging); Jam joins by invite link.
- Added: encrypted account profiles, switchable diagnostics (the redacted log of the last 10 minutes opens from Settings → App, no floating log button).
- Settings scroll their last rows above the mini player and tabs; texts no longer mention downloads or Myspoti.

Automated checks (JVM tests, signed release build) pass in CI; real-device testing is separate.

# Upstream Myspoti history

Full detail: [docs/CHANGELOG-full.md](docs/CHANGELOG-full.md).

## 2.0.0-beta.8

- **SpotiFLAC**: when it is not verified (or the verification lapsed), Downloads and any download open the verification first, then the songs download; its page is full screen (Retry and Skip were hidden under the mini player); sessions follow SpotiFLAC Mobile's rules (renewed once, in their last hour, and a download refused because another one just renewed it is sent again), so many downloads at once no longer end the verification; you are told when it must be done again.
- **Automatic downloads wait for Wi-Fi**: downloaded lists keep in step only on Wi-Fi; a download you start on mobile data goes ahead with a warning.

## 2.0.0-beta.7

- **Music that keeps playing**: a frozen player page is caught and reloaded in seconds, and the song goes on where it was; no more silent songs after a skip, "previous" or an ad; play right after launch works.
- **Skips about three times faster** (~0.8 s from tap to sound instead of ~2.5 s), and much less work in the background: far fewer calls to the player page and to Spotify.
- **Ads**: an ad that gets through is silent, and ad breaks are shorter.
- **Myspoti plays on this phone again** when the Spotify app (or another device) took playback without being picked here; free accounts refused by Spotify in some countries get a fallback and a message.
- **Canvas**: the player opens straight on it, with a smooth fade when it is still loading; tap it to watch it alone. No more flashing buttons.
- **Faster artist pages and playlists**; your **liked songs by an artist** come first and open their own page; hearts are right on long lists.
- **Smart shuffle** keeps the song going, adds songs to every playlist and has its own icon.
- **New**: followers and following on profiles, episodes in playlists, unavailable songs greyed out, fast scrollbar, share as image (Instagram story), swipe up for the lyrics, foldable-friendly player.
- **Settings**: autoplay, accent colour, screen rotation.
- **New languages**: German, Spanish, Portuguese and Greek.
- **GrapheneOS**: the player loads with "secure app spawning" on.
- **GitHub Sponsors**: support Myspoti on GitHub Sponsors too, next to Ko-fi.

## 2.0.0-beta.6

- **Fewer ads abroad**: ads from other countries' ad servers are blocked, sponsored playlists and promotion shelves are hidden; ads are recognised even when they look like songs; an ad that still gets through is muted and skipped.
- **Steadier player**: play, pause and skips go one at a time (fast taps no longer jam it), and a song that did not start is asked again.
- **No more "pop" between songs**: a very short fade on every skip.
- **Smart shuffle from the player**: tap shuffle twice on a playlist or Liked Songs.
- **Remote control**: with another device playing, the notification and the slider keep up.
- **Sleep timer "end of track"** works with episodes, local files and repeat one; artist pages load the whole discography.
- **Artist discography** (Releases) shows albums and singles again.
- **Long names fit**: long playlist names and artist mixes no longer push the layout; big titles shrink to fit, cards in a row keep the same height.
- **A pause stays a pause**, even right at the end of a song.
- **Downloads are safe**: a damaged download list no longer deletes your downloaded songs.
- **Changes Spotify refuses are undone on screen** (likes, playlist visibility), with a message.
- **Search** keeps your query when you come back, and says when it is loading, empty or failed (with Try again). Artist and profile pages can be reloaded too.
- **Jam**: join with a copied link; sharing an episode works.
- **Logout** stops everything (local songs, video, notification); the data saver applies from launch.
- Player buttons are named for TalkBack.

## 2.0.0-beta.5

- **Encrypted downloads**: downloaded songs only play in Myspoti, on your phone.
- **Offline queue**: the queue of downloaded songs shows what really plays next, and can be edited.
- **Downloaded songs are no longer reported to Spotify**: no more album starting over a downloaded song.
- **In-app updates**: download and install without leaving the app.
- **Jam QR scan** no longer crashes; a banner tells when downloads need the SpotiFLAC verification.
- **Your playlists, edited in the app**: name, description, cover (from your photos), public, private or collaborative, delete.
- **Profiles**: search people, open a profile (followers, public playlists), follow; a playlist's owner opens theirs.
- **More from Spotify**: "Don't play this artist", save episodes, search suggestions, "More like this" under albums, library sorted by recents or recently added, pin and move items into folders.
- **Sort in both directions** (tap the chosen sort again), remembered for each playlist.
- **Fixes**: AMOLED black everywhere, lists no longer end under the mini player, full-screen music videos show, the Spotify account page stays under the status bar, "Saved in" confirms each change, fast skips no longer jam the player, no white screen on phones in light mode.
- **Security and reliability fixes** (Android Auto, library pages, logout, links, video lookups).

## 2.0.0

A new engine, written from scratch, and a cleaner app on top of it.

- **No ads**, with a free account: the ad audio is refused and the next song starts.
- **New engine**: faster screens, playback that works on every phone, Spotify updates picked up by themselves.
- **Lossless downloads** (FLAC CD or hi-res, or small AAC), encrypted, with their own offline queue.
- **Sound quality** for streaming and downloads, and a **Settings** page.
- **Connect** shows your headphones; music pauses when they go away.
- **Podcasts and audiobooks**, **Jam**, **New releases**, **Browse**, **Your recap**, **widget**.
- **Library**: sort by title, artist, album, duration or date added; smart shuffle; folders.
- **In-app updates**, **Italian**, new icon; beta and release install side by side.

## 1.1.1

- **Data saver** (profile menu): on mobile data Myspoti uses no data at all. Only downloaded songs and Local Files play, downloads wait for Wi-Fi, and the Spotify player comes back on its own on Wi-Fi.
- **Find in a list**: search inside Liked Songs, a playlist or Local Files (title, artist or album).
- **Your Library**: moving a playlist (hold, then drag) works, in the list and in the grid; the drag stopped as soon as it started.
- **Settings** open in full, so the list scrolls without moving the sheet.

## 1.1.0

- **Downloads**: download a song (from its menu), an album, a playlist or Liked Songs, and listen without a connection, from **Downloaded** in Your Library or from the album or playlist page.
  - The audio comes from YouTube Music, as YouTube serves it (no re-encoding). Each song is checked against the original recording; when none matches, the song is marked as not downloadable instead of saving the wrong one.
  - Files stay inside Myspoti. A downloaded album or playlist stays in step: songs added to it are downloaded, songs that left it (a renewed Blend, a removed song) are deleted, unless another downloaded list or your own download keeps them.
  - Once a list is downloaded, its button becomes a bin. In Downloaded, swipe a song from right to left to remove it, or remove everything from the profile menu.
- **Folders**: Your Library shows your Spotify folders and opens them level by level. The + at the top creates a playlist, a folder (a real Spotify folder) or a playlist on this device; a folder can be renamed.
- **Sort and reorder Your Library**: sort by custom order, alphabetical or creator; hold a playlist, then drag it to move it (kept on this phone). Liked Songs, Local Files and Downloaded stay on top.
- **Local files in playlists**: the + of a song in Local Files adds it to a playlist on this device, or to one of your Spotify playlists, under **Added on this device** (Spotify can't receive phone files, so they stay on the phone).
- **The + of a song**: the first tap saves it in Liked Songs; tapping the green check then opens **Saved in** to add it to your playlists. Saved in only lists the playlists you can change, and says so when Spotify refuses a change.
- **Player**: pull it down from the top to close it, like the back button.
- **Go to song radio** in every song's menu (player, search, playlists…).
- **Music videos**: a title with an apostrophe ("J'SAVAIS") no longer gets the clip of another song; instrumental uploads are skipped.
- **Home** in the bottom bar always opens Home.
- **Search**: playlists in the results, and filters for songs, artists, albums and playlists.
- **Podcasts and audiobooks are hidden for now**; they will come back in a later version.
- **Artist pages**: the whole discography, not only the releases Spotify highlights.
- **Language**: French on a French phone, English on any other, with no more mixed languages. **App language** in the profile menu picks another one (Spotify's labels are translated, Myspoti's own texts stay in English).
- **Steadier and lighter player**: the hidden Spotify player stays on a tested version kept on the phone, so a Spotify website update can no longer break the app overnight, and it no longer loads covers, Canvas loops and fonts nobody sees (about half the memory and a quarter of the requests at startup).
- **Restart the player** in the profile menu, for when the music stops responding.
- **Battery saver**: Myspoti's own saver is independent of Android's (which already lowers the screen rate) and only follows its switch. The AMOLED theme is its own choice too, and returning to the app no longer looks up a music video for nothing.
- **Crossfade**: no background work when it is off, and the setting is kept when the player reloads.
- **About** in the profile menu: the version, the [Discord server](https://discord.gg/jXKnGVFnGS) and the source code.
- **Support Myspoti**: Myspoti is free and has no ads; you can now support it on Ko-fi from the profile menu or About. A short reminder appears a day after installing this version, then every 2 weeks; "No thanks" closes it, and nothing is locked behind a donation.
- New versions are checked every 3 hours instead of once a day.
- Your Library still opens Local Files and Downloaded without a connection, and the notification shows the cover of local and downloaded songs.

## 1.0.4

- **Local Files**: a folder in Your Library, right under Liked Songs, with the music stored on your phone. It plays in the app like the rest (player, mini player, notification, lock screen, headset buttons).
- **Spotify Connect**: the notification now follows track changes on the other device; it could stay on the previous song.
- Fewer updates from the player while it plays on another device (less CPU and battery).
- No like button on local files.
- One more Spotify ad server blocked.
- The update window shows what's new.

## 1.0.3

- **Spotify Connect**: music no longer jumps back to the phone or stops by itself.
  - Playing from Liked Songs goes to the device that is actually playing.
  - Swiping the app away no longer pauses a Connect device.
  - A Connect session closed by the system no longer pulls playback back to the phone.
- **Crossfade** (Settings, 0–12 s): the end of a track fades out and the next one fades in.
- **Background playback**: Myspoti asks once to lift battery optimization, so music and Connect keep going with the screen off. Also in Settings.
- **Android Auto** works again: Liked Songs, playlists, albums, artists, search and voice play.
- **Android 10 and 11** are supported.
- The update window matches the app's style.

## 1.0.2

- New welcome screen before signing in, and a new crash screen.
- Every screen follows the phone's language (official labels in 73 languages, English and French for Myspoti's own texts).
- New icons, new notification icon, older screens removed.

## 1.0.1

- **Spotify Connect**: the notification keeps following the other device with the screen off.
- Reopening the app no longer moves playback back to the phone.

## 1.0.0

- First release: Spotify with a free account and no ads, native interface, synced lyrics, official music videos, Spotify Connect.
