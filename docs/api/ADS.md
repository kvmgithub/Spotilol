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
