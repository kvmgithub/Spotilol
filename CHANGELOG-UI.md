# MySpotilol changelog

All notable changes to MySpotilol (formerly Spotilol UI), newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 0.3.0 on. Releases before 0.3.0 used the CI run number as their patch version.

Each version section is the text of the GitHub release tagged `ui-v<version>` (pre-releases up to 0.13.1); CI adds the commit, package and check note below it. To release, rename `## [Unreleased]` to `## [X.Y.Z] - YYYY-MM-DD` with a higher version: the next push to `myspoti-ui` builds, signs and publishes it.

## [Unreleased]

### Diagnostics
- With diagnostics on (Settings), the app's log notes the volume gain (`gain_db`) Spotify's playback answers offer for each song and file candidate, with the answer and context it came from, and the page player's volume settings at each change of song, context, player, element or volume: a measurement for a later check of the volume normalization. Nothing about playback, volume or requests changes; with diagnostics off nothing is read or written.

## [1.4.0] - 2026-11-12

Taken from Orpheon 1.0.0-beta.1, the name Myspoti continues under. Myspoti 2.4.1 and 2.4.2-beta.1 only offer to install Orpheon and bring nothing here. Not taken: Orpheon's ad handling (it leaves the ads on the phone's own player and acts on Spotify Connect devices only; MySpotilol keeps its own), the native playback engine, the choice of a music account (YouTube Music or none) and the catalogue providers behind it, the sign-in of a computer by QR code, the Wear OS app, the token kept on storage between launches, the volume of another Connect device, the page throttled while idle, and as before AutoMix, extensions, the Immersive look, tablet and TV layouts and the updater.

### Added
- Your Library: a row's menu takes someone else's playlist, an album, an artist or a show out of the library, without opening it.

### Changed
- Larger touch targets: the player's close, menu, +, shuffle, previous, next, repeat, share and queue buttons, the mini player's buttons, the ⋮ of song and library rows and the sort button answer in squares of 44 to 48 dp around their glyphs. Nothing moves on screen.
- The lyrics keep the screen on for 10 minutes after the last touch, without limit while the phone charges, instead of for as long as the song plays: a phone left with the lyrics open stayed lit until its battery ran out.
- The Canvas clip gives its video decoder back while the app is in the background, plays at 720p at most (the energy saver's limit below that) and loads no audio track.

### Fixed
- The app closed when the X of a "Find in…" field was tapped while the field folded away. The field stays while it is being typed in.
- The player's controls stay where they are whether the song has lyrics or not (they moved down on songs without lyrics), and the player no longer shakes under a held swipe on such songs.
- A player opened again before its pull-down had finished closing sat too low and could not be pulled again.
- The search field's cursor handle no longer shows over the player, and the search hint starts after the magnifier, on one line.
- An artist line too long for its row scrolls as one tap target: a tap on one name opened another artist.
- Queue: dragging a song is smoother, and a swipe takes a song out only past 40 % of the row (a slanted scroll removed songs).
- Podcast player: a tap on the show's name opens the show also when the episode was not played from the show's page.
- Older playlists whose address still names their owner (`spotify:user:…:playlist:…`, some of Spotify's own among them) were left out of the library and the playlist pickers.
- Your Library, Artists: "Recently added" sorts by the date an artist was followed.
- Artist page: releases newest first also where Spotify gives only the year of some.
- "Add to playlist" says so when there is no playlist to add to.
- Closing the app with "Keep playing after closing the app" off also pauses a player that is silent at that moment (between two songs, held by a call); it started again by itself once the app was gone.

## [1.3.3] - 2026-11-09

Fixes from the reviewer's check of 1.3.2.

### Fixed
- A skip made while the page was loaded again is played once the new page is up (1.3.2 dropped it together with the play it was newer than). A pause made at any time during the reload, not only while the new page's device joins, keeps the song from being played on; so does a pause while a skip past an ad is being tried. The queue is counted as played from only when a song was really played on.
- The volume is put back through Spotify Connect only when the page says right before the write, past its cache of a few seconds, that this phone is the active device and no other device plays; an answer that could not be read is no. The guard during a song also keeps a volume set while it was reading.
- The limiter's hold after the boost or the volume evening-out is lowered counts on the audio clock: a pause no longer runs it out while the raised audio still waits in the delay, and it lasts as long as that audio takes to be heard (longer than the delay itself while a reserve is being built).
- Shuffle on a list that could not be read to its end plays in Spotify's own shuffle instead of sending the part that loaded as the whole list's order. A list already on screen is kept whenever a newer reading of it is incomplete, also when it was opened from the app's memory.
- A move made after a refused one: the page is read again once it is in, so the screen and the copy on the phone show it.
- New-release notifications: the app's own switch turned off while the feed is read announces nothing either.
- "Added to your profile" and "Removed from your profile" are said once Spotify has taken it, and a refusal puts it back.

### Security
- Requests made by a page's service worker (they reach no page handler) follow the same rules: nothing with the data saver on, ads refused, telemetry answered empty.
- Spotify limiting audio licences is only recognised on the audio licence route itself.
- A request of the account before is checked against the session once more right where it is sent.

## [1.3.2] - 2026-11-08

Fixes from a second code review (of 1.3.1).

### Fixed
- Turning the crossfade off near a song's end: while the end of that song is still played from the reserve, the playback watchdog no longer takes the next song's element for stalled (it watched the heard time, which stands still there) and no longer cuts that end with a repair. A reserve under 0.3 s is played away too, instead of staying behind song after song.
- The limiter stays on as long as audio raised by the boost or by evening out the volume is still heard from the crossfade's delay, and no longer than that (it stayed on until the next song change after a raised song).
- A pause during the wait for a new page's device, or while the play before an ad is being prepared, is no longer overridden by that play.
- With repeat one, the songs you queued come back in full after the page is loaded again (the head of the queue was taken as played).
- A volume set while the page is being read after a reload is kept, and the volume is only repaired when the page says, in that very reading, that this phone is the active device.
- After a failed profile switch, repairs can load the player page again (they stayed off until the next explicit load).
- A shuffled pick from a long list still loading no longer stops a file of the phone started meanwhile.
- A playlist that ended with a page missing no longer replaces the whole copy kept on the phone or the longer list on screen, and is not queued or shuffled as if it were all of it.
- Two moves by hand in a row, the first refused: the copy on the phone no longer keeps the refused one.
- New-release notifications: notifications turned off while the feed is read, or a sign-out meanwhile, no longer mark releases as announced; the notification left after a long pause is removed at sign-out even when the playback service is gone.
- After the player page is rebuilt (its renderer died), it no longer goes back into a screen that was closed meanwhile.

### Security
- A document of another site (a form POST included) is refused before it is sent, in the player and on the account pages, not only stopped once it has started.
- With the data saver on, the player page itself sends nothing either (sign-in, sign-out and profile switch included), and an account page already open stops loading when the data saver starts to apply.
- Audio licence limits count only Spotify's own licence requests (POST): not the certificate requested under the same route, and not a status the page reads of another host.
- Account boundary: a request is checked against the session again after it waited for the tokens, and a token the page gives after a change of account is not kept; library writes still waiting at a sign-out are dropped, the library list on screen is read again, and a saved (heart) answer of the account before is not kept.
- A profile store moved to the Android Keystore counts as moved only once it reads back; until then the old store is kept and moved again.
- Release builds remove merged work branches only when their commit is part of the build (a change made only in a merge commit counts).

## [1.3.1] - 2026-11-08

### Changed
- Turning the crossfade off (or shorter) during a song no longer jumps ahead by its reserve, as 1.3.0 did: the song plays on as it is heard, no more reserve is built, and the reserve is played away without being heard (the page's element runs slightly slower, tempo and pitch stay as recorded) by the end of the song, or on into the next song when the end is too near. The diagnostic log names it (`page xfade: drain`).

### Fixed
- A reserve still being played away counts like a crossfade's for the play before an ad (it goes out in time, and the end of the song is not cut) and for the track-change log.

## [1.3.0] - 2026-11-08

Fixes from a code review of 1.2.1.

### Added
- Moving songs of your playlist by hand also works without dragging: the handle of each row offers "Move up" and "Move down" to TalkBack and keyboards.

### Fixed
- Data saver: on mobile data the app really uses no data now. Searches, pages, covers in the notification, short links and the account pages no longer went out with a token still in hand, and no repair loads the player again until Wi-Fi is back.
- A pause tapped while the player was still starting is kept: the player's first state no longer started the song again. A play sent again after Spotify lost the new page's device, a song picked or skipped while an ad was being avoided, and a shuffled pick made while a long list was still loading no longer override a newer pause or pick.
- After the page is loaded again to get past an ad or a stall, songs you queued come back in full: a song resumed from the album was taken for the same song in the queue, and the songs queued before it were lost.
- Even out volume turned off while the loudness was still being read stays off. The limiter keeps working while the louder end of the previous song still plays under the next one. A seek right after a song change also drops the crossfade's reserve, and turning the crossfade off drops it at once instead of leaving every later song that much late.
- A volume set to 0 stays at 0 when the page is loaded again; it went to 100 %.
- A quiet podcast from its own host is noticed like a louder one (it played silent through Web Audio).
- "Make private" says the playlist is private only once Spotify has taken it, and a refusal is reported (with what Spotify really has read back) even when the menu is already closed.
- A playlist that failed to load to its end can no longer be reordered by hand as if it were complete; a move writes only its own order to the copy kept on the phone; a copy served from the phone no longer counts as freshly loaded.
- The saved (heart) state of songs could be swapped when two screens asked about the same songs in a different order.
- New-release notifications: nothing counts as announced while notifications are off or not allowed; signing out removes the ones already shown.
- The lock screen, notification and Bluetooth show a short seek at once (the position was only sent again after 10 seconds of difference). A notification left after a long pause is removed when you sign out.
- Playing from the app starts the playback service at once, not only once the page answers (on Android 17 audio without it can be silenced).
- Account pages: a page whose renderer died closes instead of taking the whole app with it, and they do not open with the data saver on.
- Signing out, another profile, or a lost session: answers and writes of the account before (a late library change, a liked-songs read, a listen in progress, a new-release check) no longer reach the next account. After a lost session, another account than the last one gets a clean start; the same account keeps its history and caches.

### Security
- Requests repeated for the page are checked again exactly as they are sent, and encoded dot segments are refused, so one cannot leave the two allowed playback routes.
- A form sent from a page to another site is stopped like any other navigation, in the player and on the account pages. Refused licences and expired tokens count only from Spotify's own hosts.
- The page's play reports (`melody` message batches) are blocked in the page too, not only in the request relay; the licence address melody also serves stays reachable.
- Switching profiles needs the profile's sign-in cookie to be accepted before the player loads, and the old page's bridge ends first. A profile store move cut off before the old store was deleted is finished at the next start.
- OkHttp 5.5.1, which blocks the compromised eMudhra intermediate certificates on phones without Google Play's update.
- The diagnostic report's page address is written without its query.
- Release builds check the signing certificate against the fork key, pin the Gradle setup action to a commit and the Gradle download to its checksum, publish only from `myspoti-ui`, and remove merged work branches only as they were checked.

## [1.2.1] - 2026-11-07

### Fixed
- With the crossfade on, the next song before an ad that cannot be left out is played a full second before the end again: the element plays slightly faster while the crossfade builds up, so the song ended sooner than counted and the play went out only half a second before the end.

## [1.2.0] - 2026-11-07

### Added
- Even out volume (Settings → Playback, off by default): every song plays at a similar loudness, from Spotify's loudness value for it. Loud songs get a little quieter, quiet ones up to 6 dB louder with the limiter catching the peaks. With the crossfade on, the end of one song keeps its level while the next starts at its own. Episodes and songs without a value play as they are. Like the boost and the crossfade, it plays the music through Web Audio. The diagnostic log names each song's loudness and gain.

### Changed
- Account profiles are sealed with an AES-256-GCM key of the Android Keystore instead of androidx EncryptedSharedPreferences, which Google deprecated. Saved profiles move over once on first use; the old store is removed only after the copy is written, and a store that cannot be read is left as it is.

## [1.1.3] - 2026-11-06

### Changed
- A podcast from its own host reloads the page as soon as it plays (about 2 seconds of silence) instead of after 10 seconds of silence.

## [1.1.2] - 2026-11-06

### Fixed
- A notification for a new podcast episode opens that episode's page and names the show; it opened an album page that could not load the episode, also on reload.
- Podcasts from their own host (not from Spotify's servers) were silent with the crossfade or the volume boost on: Web Audio never gets their sound, and the switch to direct output was skipped when the episode had just been resumed at its saved position. The page now reloads and plays the episode on; crossfade and boost stay off until the app is restarted.

## [1.1.1] - 2026-11-06

### Changed
- The search field on the Search tab is dark like the rest of the app (grey field, white text, accent cursor) instead of white with black text (Myspoti 2.4.0).

## [1.1.0] - 2026-11-05

Taken from Myspoti 2.4.0. Not taken: AutoMix and spatial audio (they work on songs the app plays itself), Live Radio, Android TV, Android Auto, picture-in-picture, several accounts in separate web player profiles (the encrypted profiles stay), voice search, the Friends tab and Jam, Sonos and other speaker logins, downloads and extensions, the Immersive look, the settings search and the regrouped settings, the Following chip on Home, other lyrics sources and romanized lyrics, and one APK per architecture. The dark search field followed in 1.1.1.

### Added
- What's New: a bell on Home opens the new releases and episodes of the artists and shows you follow, newest first, with play, add to library, and add to the queue or a playlist.
- New release notifications (Settings, on by default): while the app runs, the What's New feed is read every 6 hours at most, and what came out since the last read is said in a notification (five at most); a tap opens the release.
- Your own playlists in their own order can be reordered by hand: Move puts a handle on each song, a drag moves it, and a refusal by Spotify puts the order back.
- Albums can be shared from their menu (Spotify Code and link).
- The green check on a saved song in a list opens Saved in, to take the song out.
- A Browse category lists its shows, episodes and books too (Podcasts and Documentaries showed empty).
- Keep the screen on with lyrics (Settings, on by default).
- A phone call pauses the music, and it plays again after the call unless you touched the player meanwhile (Android 12 and later).

### Changed
- Long titles scroll instead of ending in "…": the player, the mini player, the podcast player, and the top bars of albums, playlists and artists.
- Home is read again when it shows after 30 minutes away.
- The library's sort orders the followed artists too.
- The playlist pickers (Add to playlist, Saved in) show the last list at once, also after a restart, and read it again behind it.
- The podcast player closes when pulled down.
- Liked Songs and long library lists load four pages at a time.
- The client token is kept across launches, and Spotify's web player build is looked at every 6 hours at most (its bundles only off mobile data).
- Covers wait up to 10 s on a slow network instead of giving up after 2.5 s.

### Fixed
- Repeat one keeps looping through ads, stalls and reloads, and is set again after a play taken back from another device and at a cold start; a song brought by autoplay or a station is not replayed from the start of its list.
- Autoplay goes on when it loads more songs instead of starting the list over.
- A failed session check (offline, a slow page) no longer signs you out: only an anonymous token or Spotify's sign-in page does.
- Without a network, stall repairs (seek, reload, skip) wait instead of throwing the page away, and a player page that failed offline loads again as soon as the network is back.
- When the page's token goes stale after a network change (a VPN, another cell), the page is reloaded and the song goes on; a stall whose repair changes nothing reaches a reload within about 14 s.
- A play sent to a reloaded page whose device has not joined Spotify Connect is sent again once it joins, and the page is loaded again if it never does.
- After a reload the song plays as soon as the new page has settled, and the songs you queued survive an ad got past by a reload.
- A song stuck past its end moves on after about 1 s instead of up to 5.5 s of silence.
- An ad stays unheard after a song is picked: the page raised its own volume after the mute.
- An ad or a song still loading keeps the playback service in front with its locks, so Android does not freeze or silence the app during a long ad break.
- The media session tells play and pause at once (Bluetooth headsets and stereos), and has the last song before anything plays.
- A song playing on another device no longer starts on the phone when the player page reloads, and a skip there no longer brings the sound back to the phone.
- A Connect speaker's volume is never set by a repair you did not ask for.
- Licence refusals: the message says what to do, and on a phone whose Widevine is older than Spotify's web player accepts, it says so.
- MySpotilol shuffle turned on or off no longer cuts the song: the new order starts with the next song.
- A cold start no longer throws the player page away while it is still loading.
- Sign-up: the birth month list opens.
- An artist unfollowed (an album or a show removed) leaves the Library at once.
- The Podcasts chip lists the followed shows again (those in folders too), and says when there are none or they could not be loaded.
- Duplicates in the queue are kept after a reload, and a closed activity is no longer held by the player page.

### Diagnostics
- Network lost and back, and phone calls (the audio mode), are logged; page state lines carry the repeat mode; a native crash's readable tombstone strings come with the next report.

## [1.0.0] - 2026-11-04

MySpotilol is now the main app of this repository and replaces Spotilol Lite, which is no longer developed. The app itself is the same as 0.13.1.

### Changed
- Releases are regular GitHub releases, the newest marked "Latest", instead of pre-releases. Spotilol Lite's update check reads that release, but does not offer MySpotilol as an update: the two apps have different packages and install side by side.

## [0.13.1] - 2026-11-02

### Removed
- With crossfade on, Spotify's player again sees its buffered audio as it is. Since 0.11.2 it saw it moved back by the crossfade reserve, to make it fetch earlier. The dropouts that change answered turned out to come from a failing radio cell, and the moved ranges made the player fetch the same audio again and again until a guard switched them off (seen in two logs). The crossfade, its time shown to the player and the bridging of stalls stay as they were.

### Diagnostics
- `page xfade` `hold` lines keep the stall's length, the reserve it met and what of it was heard; the fields about moved ranges (`moved`, `reads`, `fetches`) are gone.

## [0.13.0] - 2026-11-01

Taken from Myspoti 2.3.0. Not taken: Chromecast and Cast speakers, downloads, extensions, the Immersive look, the equalizer and Stats for nerds (they cover only songs the app plays itself), the source switch, third-party lyrics sources, repeat once, one APK per architecture, the support reminders and Brazilian Portuguese.

### Added
- Not interested in the song menu: the song is kept in a list on this phone only (nothing goes to Spotify), leaves the queue, is skipped when a list, a radio or the queue comes to it, and is dimmed in song lists; a tap still plays it. Undo right after, and Settings → Hidden songs to show it again.
- Share lyrics: a long press on a line, or Share lyrics in the lyrics menu, picks up to 6 lines in a row to send as text with the song's link.
- MySpotilol shuffle (Settings → Playback, off by default): with shuffle on, a playlist, an album or Liked Songs plays every song once, in an order of its own with the picked song first, before it starts over. A skip goes on in that order, songs added to the list join what is to come. A list Spotify keeps on its own shuffle for the account stays with Spotify's, with a message.
- Library: a By me chip with the playlists you own, and a Podcasts chip with Your Episodes, every show you follow and a New episodes row.
- Liked Songs leads Home's shortcuts when Spotify's grid does not carry it.
- Hide the DJ (Settings): removes Spotify's DJ from Home.
- Lyrics preview in the player (Settings): off, the lyrics card under the player is gone; a flick up on the cover still opens the lyrics.
- The sleep timer fades the song out before it pauses.
- Sign-in: a loading screen until Spotify's page has content; a page that stays blank, fails or does not answer within 25 s shows Try again.
- Samsung: a hint under Background playback to add the app to Never sleeping apps, with a button to the app's settings.

### Changed
- A tap on a search result puts the keyboard away.
- Likes and follows kept for later stay with the account that made them, and go out as soon as Spotify answers. A session lost without a sign-out forgets whose account it was and reads the profile again.
- The repeat mode chosen while a local file plays is kept for the next start.

### Fixed
- Repeat one keeps looping when Spotify puts an ad after the song.
- A pause during an ad stays a pause: the recovery past a stuck ad no longer starts the music, and a song picked around the ad waits for your resume.
- Spotify refusing the account's audio licences (three refusals within 10 s) pauses playback once with a message instead of a run of skips and reloads.
- A page that moves on by itself through songs right after a play is stopped.
- A play sent after a reload of the page waits up to 5 s for the new page's device to join Spotify Connect and is sent again once when Spotify answers that the device is gone; a song picked during a reload plays on the new page.
- While Spotify limits the audio licences, the page reads a proper rate-limit answer with Retry-After, and songs played after the hold's pause are not paused again.
- A call to the page that cannot reach it fails at once, and runs only in the page it was made for.
- Signing out drops a play, pause or skip still waiting for the page.

### Diagnostics
- A power section in the report (battery exemption, power saver, standby bucket, process importance, maker), and the screen going off and on is logged while a song plays.
- The media element line says whether it has its keys and how often it waited for one.

## [0.12.1] - 2026-10-31

### Fixed
- With crossfade on, a short stall of the connection is no longer heard. The crossfade keeps the last seconds of the song in reserve, but when Spotify's player ran out of audio, the silence went into that reserve too and was heard in full a few seconds later (log seen: 4.5 s and 11.6 s without data with the screen off, each heard in full with 6 s in reserve). The reserve now plays on through the stall and the silence is cut out: a stall shorter than the reserve goes unheard, a longer one is heard only for what the reserve did not cover. The reserve then builds up again.
- Spotify's player holding its speed at 0 while it waits for data no longer counts as a speed change of its own: three stalls in one song switched its crossfade off.

### Diagnostics
- `page xfade` `hold` lines replace the `starve` lines: each stall with its length (`gap`), the reserve it met, what of it was heard (`heard`), and whether it came from the player waiting for data (`wait`) or holding at speed 0.

## [0.12.0] - 2026-10-29

Taken from Myspoti 2.2.0. Not taken: Chromecast, Android Auto, tablet layouts, downloads, extensions, the Immersive look and the dependency updates.

### Added
- Tap a word in word-synced lyrics to play from that word (between two words, from the one before).
- Links in show, episode, artist and playlist descriptions can be tapped: Spotify playlists, albums, artists and shows open in the app, other links in the browser (always over HTTPS).
- Each artist name in the player is its own tap target.
- Playlists, albums and Liked Songs can be queued whole: Play next and Add to queue in their menu and in the library's long-press menu, in the list's sort order.
- Podcasts: unplayed first or hide played on a show page (kept per show), a New mark on episodes released since your last visit, a dot on followed shows on Home with a new episode, played ticks and progress bars.
- The playlist header shows the song count next to the duration.

### Changed
- Artist releases and the latest release are ordered by the full release date, not the year alone.
- Playlist pickers show the covers of playlists without their own cover.
- No empty strip under the player for songs without lyrics; tighter mini player lines; cover colours dark enough for white text; edge to edge on Android 14 and older.

### Fixed
- Slow networks: the clock stops while the sound waits for data, a stuck ad break is read again, a command stuck for 3 s gives way to a newer one, the skipped-to song is shown at once from every control and put back when the skip never lands, a seek is held against older positions and sent again, connections to Spotify that went dead fail after 10 s instead of 15.
- A song picked at row 100 or later of a big list plays that song, not another one.
- Skips and pauses without a click: the sound stays down until the next song really plays, and the old song is cut only once its fade is low.
- One skip no longer goes two songs on when the song ends while the skip waits.
- The liked heart of a song restored at start is checked (once more when the check fails), and again when you return to the app or open the player.
- The repeat button keeps a tap until Spotify shows it; a local file keeps its own repeat.
- Connect volume changes are sent one at a time, so the device ends on the last one.
- Discover: new releases of followed podcasts open their show or episode.

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
