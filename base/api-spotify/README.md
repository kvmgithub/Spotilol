# api-spotify

Spotify implementation of the Myspoti API (`:api`), on top of the official Web Player.

## Modules

| Module | Role |
|---|---|
| `:api` | Pure Kotlin contract the UI sees: models and interfaces, no Android, no Spotify details |
| `:api-spotify` | This module: `SpotifyApi` implements `:api` |
| `:host-cdp` | Desktop host (Chrome over CDP) and the live integration tests |

Reads go straight to Spotify over HTTP (`SpotifyHttp`: the page's web token plus a client
token minted here). Playback, library writes, folders, Connect and Jam go through the page's
own services via the bridge. `SpotifyApi.raw` reaches all 179 GraphQL operations
(`gql-ops.json`, variables in `gql-schema.json`) and the 89 page services for anything not typed.

## Coverage

All of these are exercised by `host-cdp` live tests against a free account:

| Domain | Methods |
|---|---|
| Player | play (any context), pause, resume, seek, next, previous, repeat, shuffle, queue add/remove/clear, volume, speed (podcasts only) |
| Search | all, tracks, artists, albums, playlists, shows, episodes, audiobooks, users, genres, suggestions, recent, clear recent |
| Catalog | track, album, album tracks, artist, top tracks, discography, related artists, appears on, similar albums |
| Library | contains/add/remove, library list with filters, sort and folders, liked songs, pin/unpin, create/rename/delete folders, move to folder |
| Playlists | playlist, tracks (paged, with row uids), sorted tracks (client side, ascending or descending), mine, editable, create, rename, description, public, collaborative, delete, follow/unfollow, add/remove/move tracks, recommended songs |
| Home | feed with filters, categories, category pages |
| Podcasts | show, episodes, episode, audiobook, chapters, saved episodes, save or unsave an episode |
| Users | me, public profile, top artists/tracks, follow/unfollow/is following, history |
| Track extras | lyrics (line and word sync, colours), canvas, credits, radio for a track or artist, taste-profile exclusion, don't play this artist |
| Devices | list, transfer, pull here |
| Jam | availability, session, start, join, leave, end, invite link, remove member, queue-only, participant volume |

Added without a live check (same mechanism as a verified neighbour, see the notes in
[feature-inventory.md](../../docs/api/feature-inventory.md)): don't play this artist (`artistban`),
save or unsave an episode (`listenlater`), public and collaborative (`PlaylistAPI.updateDetails`,
read back with `getMetadata`). The full feature list, with what needs a capture, is in that file.

Limits seen on the Web Player with a free account:
- Jam: Spotify answers `RESTRICTED`, so `start` fails; join and the rest are wired but unverified.
- Private session: not supported by the Web Player (`isSupported: false`).
- Friend activity: not implemented by the Web Player; `friendActivity()` returns an empty list.
- Device transfer: needs a second Connect device to verify.

Tools (`tools/`): `refresh_gql_ops.py` (operation hashes from the public bundles),
`extract_gql_variables.py` (variables from captures), `infer_gql_variables.py`,
`build_gql_schema.py` (required variables and types from the server), `capability_report.py`.

## Player bridge

[`src/main/resources/bridge/player-bridge.js`](src/main/resources/bridge/player-bridge.js) runs inside the
official Web Player page (open.spotify.com), which keeps doing the playback with the
browser's Widevine CDM. The bridge finds the page's service registry through React's
context tree and calls its `PlayerAPI`, `PlaybackAPI`, `LibraryAPI` and `TokenProvider`.
It patches nothing in the page.

The same file runs in the Android WebView and in desktop Chrome over CDP.

### Channels

| Direction | Android | Desktop (CDP) |
|---|---|---|
| Host → page | `evaluateJavascript("Myspoti.call(id, method, args)")` | `Runtime.evaluate`, same call |
| Page → host | `MyspotiHost.postMessage(json)` (web message listener, open.spotify.com only) | `Runtime.addBinding("__mspHostPost")` |

`Myspoti.call` also returns a Promise, but hosts should rely on the `result` message:
Android's `evaluateJavascript` does not wait for Promises.

### Messages (page → host)

| `type` | Fields |
|---|---|
| `ready` | `version` |
| `state` | `state`: see below, or `null` when no playback session exists |
| `queue` | `queue`: `{current, queued[], nextUp[]}` of tracks |
| `result` | `id`, `ok`, `value` or `error` |
| `error` | `error` (boot failure) |

`state`: `{track, contextUri, isPaused, isBuffering, positionMs, timestamp, durationMs,
shuffle, repeat (0 off, 1 context, 2 track), speed, restrictions, playbackId}`.
`positionMs` is the position at `timestamp` (epoch ms). Extrapolate it while playing.

`track`: `{kind ("track", "episode" or "ad"), uri, uid, name, advertiser, durationMs, artists[{uri, name}],
album{uri, name}, image,
explicit, mediaType, provider}`. An ad has `kind: "ad"`, a `spotify:ad:` uri, an `advertiser`,
and restrictions that forbid skip and seek.

### Methods

| Method | Args | Returns |
|---|---|---|
| `bridge.version` | | `1` |
| `player.state` | | state or `null` |
| `player.queue` | | queue |
| `player.play` | `contextUri`, optional `trackUri`, `trackUid`, `index`, `positionMs` | |
| `player.pause` / `player.resume` | | |
| `player.seek` | `positionMs` | |
| `player.next` / `player.previous` | | |
| `player.setShuffle` | `on` | |
| `player.setRepeat` | `mode` (0, 1, 2) | |
| `queue.add` | `uris[]` | |
| `queue.remove` | `items[{uri, uid}]` | |
| `queue.clear` | | |
| `volume.get` / `volume.set` | `level` (0 to 1) | number |
| `library.contains` | `uris[]` | `boolean[]` |
| `library.add` / `library.remove` | `uris[]` (tracks, albums, artists) | |
| `token.get` | | `{accessToken, expiresAtMs}` for the HTTP API |

Notes:
- `player.previous` near the start of a track goes back. Later, it restarts the track.
- `queue.add` lands within about a second, and a `queue` message follows.
- A command can be refused by `restrictions`, for example while the next track loads. The error message says so.

### Tests

Kotlin live tests: `./gradlew :host-cdp:test -Pcdp=http://localhost:9222`.

The older Python harness [`bridge-tests/run_bridge_tests.py`](bridge-tests/run_bridge_tests.py) drives a logged-in Web Player in
desktop Google Chrome (Widevine included) over CDP. Each test restores what it changes.

```bash
google-chrome --user-data-dir="$HOME/.myspoti-chrome" --remote-debugging-port=9222
```

```bash
python run_bridge_tests.py            # all tests
python run_bridge_tests.py -k queue   # some tests (comma-separated)
```

Not covered yet: ads on free accounts, Connect (other devices), podcasts.
