# Ads on a free account (Web Player)

Observed on 2026-10-15 with a free account in desktop Chrome, playing a playlist for
about 16 minutes. One audio ad break happened. Raw captures stay out of the repo.

## Where ads come from

| Kind | Source |
|---|---|
| Audio ad between tracks | Inserted by the server into the playback state machine |
| Banner ("leaderboard") | Page ad manager `leaderboard`, loaded into an iframe |
| Home ads, carousels | Page ad managers `home` and friends |
| Podcast pre/mid/post-rolls | Slots in the ad config, not observed yet |

The page groups its managers in `Platform.getAdManagers()`: `config`, `audio`,
`inStreamApi`, `leaderboard`, `home`, `vto`, `survey`, `adOpportunityLogger`. The registry
also has a `ForceVolumeAPI`.

## Audio ad timeline

1. At startup the page fetches `GET spclient.wg.spotify.com/ads/v2/config`. The `stream`
   slot carries `stream_time_elapsed_ms: 870000` (14.5 min) and `linear: true`.
2. Once enough listening time has passed, the server adds the ad to the reply of
   `PUT track-playback/v1/devices/{device}/state`. It arrives one track ahead: it was in
   the reply at the start of the previous track, about 4 minutes before it played.
3. The ad sits in `state_machine.tracks[]` like a track, with `content_type: "AD"`,
   `metadata.uri: "spotify:ad:{id}"`, a name, an advertiser, a duration (27 s here) and
   `manifest.file_urls_mp3`. The matching state has `disallow_seeking: true` and empty
   skip transitions (`disallow_seeking_reasons: ["ad_disallow"]`).
4. The audio is a plain MP3 from `adstudio-assets.scdn.co/mp3/{hash}.mp3`. It does not
   use Widevine.
5. During the ad, the page reports it like a track: `melody/v1/msg/batch`
   (`jssdk_playback_start` with `play_track: spotify:ad:…`), `gabo-receiver-service`
   events, `aet.spotify.com/v2/t`, and the usual `track-playback` state updates. The
   dealer WebSocket pushes the cluster state with the ad as current track.
6. After the ad ends, the next playlist track starts normally.

`PlayerAPI.getState().item` during the ad: `type: "ad"`, `uri: "spotify:ad:…"`, `title`
(no `name`), `advertiser`, `url` (click-through), `duration`, `images`. Restrictions:
`canSkipNext`, `canSkipPrevious` and `canSeek` are false; `canPause` is true.

## What the bridge does now

The bridge reports ads as items with `kind: "ad"`, plus `name`, `advertiser`, duration
and restrictions. The UI can show them: who the advertiser is, a countdown, and no skip
button.

## Options for the engine

| Option | What happens | Accounts risk | Notes |
|---|---|---|---|
| Show ads | The ad plays and the UI shows it | None | Matches Spotify's terms |
| Lower the volume during ads | The ad plays and is reported, the user hears little | Low to medium | `ForceVolumeAPI` exists. The page may push the volume back up or pause a muted ad. Needs a test. |
| Block the ad on the network | The ad item or its MP3 never plays | Higher | Changes what the page reports. It's the kind of tampering Spotify can detect, and it breaks if they change the format. |

Blocking ads goes against Spotify's Terms of Use (see the README disclaimer). Whatever
the choice, it should live in its own module behind the `:api` interface, so the policy
can change without touching the UI or the player bridge.

## Tested: blocking the ad audio (2026-10-16)

Setup: free account, desktop Chrome, requests to `adstudio-assets.scdn.co` aborted
(`blockedbyclient`) from the start of a long listening session.

1. At 00:20:13 the server put an ad (`spotify:ad:…`, 27 s) next in the state machine, as before.
2. The player tried to load its MP3; the request was aborted.
3. The page reported a playback error for the ad itself: `melody` `jssdk_playback_error`
   (`content_type: "ad"`), then `PUT track-playback/.../state` with `debug_source: "error"`.
4. The server moved on: the next state machine no longer held the ad, and the next playlist track
   started within one second (`jssdk_playback_start`, `started_playing`, then
   `played_threshold_reached` 30 s later, as for any track).
5. The ad was on screen for less than 0.5 s. No skip command was needed.

So the engine blocks ads by refusing the ad audio host, and lets the page report what really
happened (a failed ad load). It never reports an ad as played.

Engine rules:
- Android WebView: in `shouldInterceptRequest`, answer requests to `adstudio-assets.scdn.co` with an
  empty error response. Desktop CDP host: `Fetch.failRequest` (`BlockedByClient`) for the same pattern.
- Fallback, if an ad item stays current for more than 1 s (new ad host, video ad): play the next
  context track with `player.play {contextUri, trackUri}`, and add the new host to the block list.
- Banner, home and carousel ads never reach the user: the Web Player page is hidden and the UI is
  native. Their requests can still be blocked to save data.
- The UI drops `kind: "ad"` items from what it shows.

## Still to capture

- Whether blocked ads come back sooner (rhythm after a failed ad), over a 1-hour session.
- Video ads and podcast ad slots, which may use other hosts.

## Measured again (2026-10-20): a refused ad no longer moves on

Setup: free account, desktop Chrome, the app's current bridge, about two hours of listening.

- The page's ad-manager latch (`ads.off`) does not stop audio ads: one ad after ~17 min with the latch
  on, one after ~19 min without it. The server queues them whatever the page's managers say.
- With the ad MP3 refused (aborted, or answered `403` as on Android), the ad now stays current for
  good: buffering at 0:00, 20 min in one run. `inStreamApi.skipToNext()`, `player.next` and
  `player.play` on the next song are all refused (`ad_disallow`). The 2026-10-16 behaviour (error,
  next song within a second) is gone.
- A page reload leaves it: the new page registers a new device, which the ad does not follow, and
  playing the context again starts a song within a second. This is the app's last resort.
- The "Test ad" (`spotify:ad:test`) seen in device logs was `tools/playback_test.py`'s own fake.

## Where the ad sits in the state machine

The ad arrives in a `PUT track-playback/v1/devices/{device}/state` reply when the song before it
starts (about 3 min ahead). The song's state has these transitions:

| transition | first try | after an ad was left out |
|---|---|---|
| `advance` (natural end) | the ad state | the ad state |
| `skip_next` | the next song | the ad state |
| `show_next` (shown as next) | the next song | the next song |

The ad state itself has no transitions and `ad_disallow` restrictions.

## What the engine does now

Three steps, measured on desktop (free account, 20/10, ~1 h 30 of listening, 5 ad tries):

1. `AdBlock.withoutAds` rewrites every reply that leads to an ad before the page sees it: the
   `advance` of a song that leads to an ad goes where its `skip_next` goes. The next song started
   60 ms after the end of the song, and every state the page reported was answered `200`.
   `show_next` is not a usable target: it points at a display-only state, and a page sent there
   had every report refused (`502 unknown_state_ref`) and stopped playing.
2. When `skip_next` leads to the ad as well (Spotify's next try, after an ad was left out once),
   `AdBlock.adAhead` names the song the page shows as next, and the player plays it 1 s before
   the song ends (`player.play` in the same context). A new play puts the due ad first, but an ad
   at the start of a play, its audio refused, moves on by itself: the next song came within about a
   second (three tries, at the end and in the middle of a song), every report was `200`, the songs
   after it went on with no ad, and songs the user had queued stayed queued. A "next" while an ad
   is due is sent the same way, since Spotify points the skip at the ad too and a page that steps
   onto an ad stays on it. The UI shows an ad only after 2 s, so this one never shows.
   Seen on a tester's phone: while Spotify refuses licences (429, after fast skipping), the song
   after the ad cannot start and the ad stays until the limit is over.
3. The ad audio host stays refused, and the reload stays as the last resort for an ad that is
   already the current state.

On Android the replies pass through `ControlRelay`, which applies steps 1 and 2. The desktop CDP
host does not rewrite replies yet.

Over the measured session: no ad heard or shown, no reload.
