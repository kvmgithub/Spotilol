# Track extras: lyrics, canvas, colors, recommendations, images

Clean-room notes from Web Player captures (free account, 2026-10-15). Companion to
`CAPTURE-MAP.md`. Ids below are placeholders: `{id}` base62 (22 chars), `{hex}` 32-hex
file id, `{img}` 40-hex image id.

## Common request shape

**GraphQL**: `POST https://api-partner.spotify.com/pathfinder/v2/query`

Headers (names only): `authorization` (Bearer web token), `client-token`, `app-platform`
(`WebPlayer`), `spotify-app-version`, `content-type: application/json;charset=UTF-8`,
`accept`, `accept-language` (drives localized strings and the pickasso locale).

```json
{"variables": {...}, "operationName": "canvas",
 "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "<hash>"}}}
```

Errors come back as HTTP 200 with `data: null` and `errors[]`
(`extensions.classification`, e.g. `ValidationError`).

**spclient REST** (`spclient.wg.spotify.com` or the `{region}-spclient.spotify.com` host from
apresolve): the same headers minus `content-type`.

## Operation table

| operationName | sha256Hash | Trigger |
|---|---|---|
| `canvas` | `575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f` | each new now-playing track |
| `fetchExtractedColors` | `36e90fcaea00d47c695fce31874efeb2519b97d4cd0ee1abfb4f8dc9348596ea` | page headers (playlist, artist, album) |
| `getDynamicColorsByUris` | `f0f112945d6d745bd8ff790317bbf8d310036da75df33130490e9d6dc96c59d9` | now-playing view and lyrics background |
| `internalLinkRecommenderTrack` | `c77098ee9d6ee8ad3eb844938722db60570d040b49f41f5ec6e7be9160a7c86b` | track page, "Recommended" |
| `similarAlbumsBasedOnThisTrack` | `1d1f93a737498adca2c892c73af87fc0b052afe4e1a33c989540c32413dfae17` | track page, similar albums |
| `npvV2TrackSupplementalSections` | `3268e307f08330b620de5c161a03b48acf66ec57164eac446e0653694a485764` | now-playing panel (concerts, merch, videos) |
| `queryNpvArtist` | `e1ae46a21911a3075c1aa29bf09a6c60f9a45b6a2b1132429f10ad06c299b5d7` | now-playing panel, artist card and credits |
| `queryTrackArtists` | `ee2b038198f5e62c679c3996584d9249bbee55fe69fc212271c56492a022c798` | queue view / song menu |
| `decorateQueuedByUsers` | `33fe7085b4a9dde404955eeee4f8f93aa7a8e55028058354bc707727d8a7890d` | queue view ("added by") |
| `centralisedStatePlayerOptions` | `e2dcfcab470854d4d1c7cb1a851438f14fe0a94d57db7f0b9dde492559d5395d` | opening a playable context page |
| `queryAlbumMerch` | `3ef44ed6f17be67299538fe77faffab4075aeaf9e1085f10fc835592266711b5` | album page |

Hashes change when Spotify rebuilds the web player. Treat them as config, not constants.

## Lyrics

`GET https://spclient.wg.spotify.com/color-lyrics/v2/track/{trackId}/image/{coverUrlEncoded}?format=json&vocalRemoval=false&market=from_token`

- `{trackId}`: base62 track id (not the uri).
- `{coverUrlEncoded}`: the 640 px cover URL, fully percent-encoded
  (`https%3A%2F%2Fi.scdn.co%2Fimage%2Fab67616d0000b273{img}`). The cover is used to compute
  `colors`.
- Without `format=json` the server returns protobuf (not captured). Always send it.
- Response: `cache-control: public, max-age=900`. The web player fetches lyrics for the
  current track **and** pre-fetches the next one, even with the lyrics view closed.

```json
{"lyrics": {
   "syncType": "LINE_SYNCED",
   "lines": [
     {"startTimeMs": "11760", "words": "First line", "syllables": [], "endTimeMs": "0", "transliteratedWords": ""},
     {"startTimeMs": "14820", "words": "♪", "syllables": [], "endTimeMs": "0", "transliteratedWords": ""},
     {"startTimeMs": "168140", "words": "", "syllables": [], "endTimeMs": "0", "transliteratedWords": ""}],
   "provider": "MusixMatch", "providerLyricsId": "123", "providerDisplayName": "Musixmatch",
   "syncLyricsUri": "", "isDenseTypeface": false, "alternatives": [],
   "language": "fr", "isRtlLanguage": false, "capStatus": "UNCAPPED",
   "previewLines": [ /* first 5 lines, same shape */ ],
   "translationLanguages": []},
 "colors": {"background": -9472386, "text": -16777216, "highlightText": -1},
 "hasVocalRemoval": false}
```

UI notes:
- `startTimeMs` and `endTimeMs` are **strings**. `endTimeMs` is `"0"` in LINE_SYNCED: a line
  ends where the next line starts. The last line is often empty (end marker), and `♪` marks
  an instrumental gap.
- `syncType`: `LINE_SYNCED` (all captures) or `UNSYNCED` (all `startTimeMs` = `"0"`: show
  as static text). `SYLLABLE_SYNCED` would fill `syllables[]`; it was not seen.
- `colors.*` are signed 32-bit ARGB ints (`-1` = white, `-16777216` = black). In Kotlin,
  `Color(value)` works directly.
- `isRtlLanguage` sets the text direction. Credit `providerDisplayName` under the lyrics.
- `capStatus` probably limits free accounts (e.g. preview lines only). It was always
  `UNCAPPED` here.

## Episode transcripts: timesynced-text-distribution

`GET https://spclient.wg.spotify.com/timesynced-text-distribution/v0/get-timesynced-text/spotify:episode:{id}?start_time_ms=0&end_time_ms=60000`

This is **not** song lyrics. It is a word-level podcast transcript for an episode
**preview window**. The URL is not built by the client: it comes ready-made from gql
`feedBaselineLookup` (home feed preview cards), at
`...data.previewPlayback.audioPreview.transcriptUrl` and `...videoPreview.transcriptUrl`.
The audio preview itself is `previewPlayback.audioPreview.cdnUrl`
(`p.scdn.co/mp3-preview/{40hex}.mp3`). Response `cache-control: max-age=600`.

```json
{"words": [
   {"word": "Hello", "offsets": {"start": "4.480s", "end": "4.920s"}, "speakerId": "1"},
   {"word": "everyone,", "offsets": {"start": "4.920s", "end": "5s"}, "speakerId": "1"}],
 "speakers": {"1": {"id": "1", "displayName": "", "imageUrl": ""}},
 "next": ""}
```

Offsets are protobuf-style duration strings (`"631.68s"`): parse them as seconds. Speaker
names were always empty. `next` was always empty (pagination token).

## Canvas (looping track video)

gql `canvas`, variables `{"trackUri": "spotify:track:{id}"}`.

```json
{"data": {"trackUnion": {"__typename": "Track", "uri": "spotify:track:{id}",
  "canvas": {"fileId": "{hex}", "type": "VIDEO_LOOPING_RANDOM",
             "uri": "spotify:canvas:{id}",
             "url": "https://canvaz.scdn.co/upload/artist/{id}/video/{hex}.cnvs.mp4"}}}}
```

- `canvas: null` means the track has no canvas.
- `canvas.url` is a plain progressive MP4 (portrait, short loop). A client can play it
  directly in ExoPlayer with loop on. This is the simplest path.
- The web player does not fetch `canvaz.scdn.co`. It uses the adaptive pipeline with `fileId`:
  1. `GET {spclient}/manifests/v9/json/sources/{fileId}/options/supports_drm` (gql headers).
  2. `GET https://video-cf.spotifycdn.com/segments/v1/origins/{origin}/sources/{fileId}/encodings/{enc}/profiles/{profileId}/inits/mp4?<signed>`
     then `.../profiles/{profileId}/{segmentStartSeconds}.mp4?<signed>`.
- Manifest fields: `contents[0].encoding_id`, `segment_length` (4 s), `end_time_millis`,
  `profiles[]` (`id`, `file_type` mp4/ts/webm, `video_width`/`video_height`, `max_bitrate`),
  `encryption_infos: []` (canvas is unencrypted), `base_urls[]`
  (`video-cf.spotifycdn.com/segments/`, `video-fa.scdn.co/segments/`),
  `initialization_template` and `segment_template` (relative, with `{{profile_id}}`,
  `{{file_type}}` and `{{segment_timestamp}}`, already carrying signed query params
  `fauth`, `token_ak` and `token_cf`). Segment requests need no auth header. Segments are
  immutable (`max-age=315360000`).
- Canvas profiles are portrait: mp4 ids 9→4 = 180x320 … 1080x1920. The web player picked
  profile 6 (480x854) first, then 4 (1080x1920).
- Canvas also appears inline in `queryNpvArtist.trackUnion.canvas` and in
  `feedBaselineLookup` items, with an extra `thumbnail.sources[]` (`ab67ba69…`, 288x512 /
  144x256).

### The other video-cf traffic: music-video prefetch (not canvas)

There are two `origins` ids in segment URLs. Canvas uses one; music videos use another.
Music video segments are fetched after `GET track-playback/v1/media/spotify:track:{id}?manifestFileFormat=…`.
For a track with a music video, the media response has a top-level
`video_version_uri: "spotify:track:{videoTrackId}"`, plus a second `media` entry for that
uri with `item.track_type: "VIDEO"`, `item.video_id: "{hex}"` and
`item.manifest.manifest_ids_video[].file_id`. The player then fetches the
`manifests/v9` for that id and **prefetches only the first segment** (init + `0.mp4` of
video profile 0 at 1080 and of an audio profile), so "Switch to video" starts at once.
This manifest has `encryption_infos` (Widevine/PlayReady `cenc/ctr`,
`license_server_endpoint: /widevine-license/v1/video/license`), 4x4 `background_profiles`,
`subtitle_template` (webvtt) and spritemaps. It needs DRM, so it is out of scope for a simple
client. To offer "has video", check `video_version_uri != null`.

## Colors

**fetchExtractedColors**: variables `{"imageUris": ["https://i.scdn.co/image/{img}", ...]}`
(pickasso URLs work too). Used for page header gradients.

```json
{"data": {"extractedColors": [{"__typename": "ExtractedColors",
  "colorDark": {"hex": "#505068", "isFallback": false},
  "colorLight": {"hex": "#737396", "isFallback": false},
  "colorRaw": {"hex": "#505068", "isFallback": false}}]}}
```
The output order follows the input order. Use `colorDark.hex` for a dark-theme header.

**getDynamicColorsByUris**: same variables (one 640 cover `ab67616d0000b273…`, or a
`ab67616d0000aa54…` variant). This is the full now-playing palette:

```
data.dynamicColors[].bestFit            "DARK" | "LIGHT"
data.dynamicColors[].{dark|light}.encoreBaseSetTextColor      {red,green,blue,alpha}
data.dynamicColors[].{dark|light}.{minContrast|highContrast|higherContrast}
    .backgroundBase / .backgroundTintedBase / .textBase / .textSubdued / .textBrightAccent
```
The colors are 0..255 RGBA objects. Most of the time `dark.highContrast.backgroundBase` is
the now-playing background. Lyrics carry their own `colors` (see above).

Many responses also embed colors in `coverArt.extractedColors.colorDark.hex`. Prefer those
when they are present, to save a call.

## Recommendations and now-playing sections

**internalLinkRecommenderTrack**: `{"uri": "spotify:track:{id}", "limit": 5}` → recommended tracks.
```
data.seoRecommendedTrack.items[].data: { uri, id, name, playcount (string),
  duration.totalMilliseconds, contentRating.label ("EXPLICIT"|"NONE"), playability.playable,
  artists.items[].{uri, profile.name}, albumOfTrack.{uri, coverArt.sources[] (300 px)} }
```

**similarAlbumsBasedOnThisTrack**: `{"uri": "spotify:track:{id}", "limit": 50, "albumsOnly": true}`.
```
data.seoRecommendedTrackAlbum.totalCount
data.seoRecommendedTrackAlbum.items[].data: { uri, name, type ("ALBUM"|"SINGLE"|…),
  date.{year,isoString,precision}, coverArt.sources[], artists.items[].{uri,profile.name},
  playability.playable, sharingInfo.shareUrl }
```

**npvV2TrackSupplementalSections**: `{"trackUri": "spotify:track:{id}", "enableRelatedVideos": true}`.
```
data.trackUnion.artists.items[].{id, goods.concerts.{totalCount, items[].data:
     {uri, title, startDateIsoString, location.{name, city}}}}
data.trackUnion.merch.items[].{uri, nameV2, description, price, url, image.sources[].url}
data.trackUnion.relatedVideos.items[].trackOfVideo.data.uri   (uri only here)
```
Empty sections come back as `items: []`.

**queryNpvArtist**: variables
`{"artistUri", "trackUri", "contributorsLimit": 10, "contributorsOffset": 0, "enableRelatedVideos": true, "enableRelatedAudioTracks": true}`.
This is the "About the artist" card plus credits:
```
data.artistUnion: { uri, id, profile.name, profile.biography.{text,type},
  profile.externalLinks.items[].{name,url}, stats.{followers, monthlyListeners, worldRank,
  topCities.items[].{city,country,region,numberOfListeners}},
  visuals.avatarImage.sources[], visuals.gallery.items[].sources[], headerImage.data.sources[],
  onPlatformReputationTrait.verification.isVerified, goods.concerts }
data.trackUnion: { canvas, merch, creditsTrait.contributors.items[].{name, role, roleGroup.name, uri},
  creditsTrait.sources.items[].name (label/distributor),
  relatedVideos.items[].{uri "spotify:video:…", trackOfVideo.data.{uri,name,artists,
     albumOfTrack.coverArt.sources[] (16:9 ab6742d3…), associationsV3.audioAssociations.items[].trackAudio._uri}},
  associationsV3.unmappedVideoTrackAssociations }
```
The biography text can contain HTML links. `role` strings follow the UI language.

**queryTrackArtists**: `{"trackUri"}` → `data.trackUnion.artists.items[].{uri, profile.name}`
(drives "Go to artist" when there are several artists).

**decorateQueuedByUsers**: `{"userUris": ["spotify:user:{user}"]}` →
`data.users[].{uri, id, name, username, avatar, socialHandle}` (queue "added by").

**centralisedStatePlayerOptions**: variables
`{"uri": "<context uri>", "deviceInfo": {"deviceId", "deviceType": "computer", "clientId", "clientVersion", "productId": "1"}}`.
`deviceInfo` comes from the session (never hard-code it).
```json
{"data": {"playerOptions": {"__typename": "PlayerOptions", "shufflingContext": false,
  "modes": [{"modeKey": "context_enhancement", "modeValue": "NONE"},
            {"modeKey": "media", "modeValue": "audio"}, {"modeKey": "jam", "modeValue": "off"}],
  "restrictions": {"disallowTogglingShuffleReasons": [],
    "disallowSettingModes": [
      {"modeKey": "context_enhancement", "modeRestrictions": [
         {"modeValue": "NONE", "reasons": ["already_set"]},
         {"modeValue": "RECOMMENDATION", "reasons": ["not_supported_by_content_type"]}]},
      {"modeKey": "media", "modeRestrictions": [
         {"modeValue": "video", "reasons": ["not_playing_track"]}]}]}}}}
```
`context_enhancement = RECOMMENDATION` is Smart Shuffle / "enhance". It is allowed (empty
`reasons`) only on some playlists. Only ARTIST, ALBUM, PLAYLIST, PLAYLIST_V2, SHOW and
COLLECTION uris are valid. A `spotify:list:…` uri returns a `ValidationError`.

**queryAlbumMerch**: `{"uri": "spotify:album:{id}"}` →
`data.albumUnion.{name, merch.totalCount, merch.items[].{uri, nameV2, description, price, url, image.sources[].url}}`.
`price` is preformatted and localized (it can be `""`). `url` goes to the external store.

### Radio and mixes

No song-radio request was captured (the "Go to song radio" menu entry exists in the
locale strings, but it was not clicked). What was seen:
- An artist radio is a normal editorial **playlist** (`spotify:playlist:37i9dQZF1E4…`,
  name "Radio {artist}", owner Spotify), listed in `queryArtistOverview` related playlists.
  It plays like any playlist.
- `inspiredby-mix` is a playlist `format` value in `home` / search results (a "mix" built
  around an artist).
- No `radio-apollo`, `inspiredby-mix` endpoint or `spotify:station:` uri was observed.

## Images

### i.scdn.co

`https://i.scdn.co/image/{40-hex}` = an 8-hex **type** prefix, an 8-hex **size** code,
then a 24-hex **hash** shared across sizes. To change the size, swap the size code. The
`image-cdn-fa.spotifycdn.com` and `image-cdn-ak.spotifycdn.com` hosts serve the same ids.

| Kind | Type prefix | Size codes |
|---|---|---|
| Album cover | `ab67616d` | `00004851` 64, `00001e02` 300, `0000b273` 640 (also `0000aa54`, size unknown) |
| Artist avatar | `ab676161` | `0000f178` 160, `00005174` 320, `0000e5eb` 640 |
| Artist gallery | `ab676167` | `0000ecd4` 640 |
| Artist header | `ab676186` | `00000194` 1494x640, `0000101f`/`00001016`/`00001667`/`00009d80`/`0000eab1` (header variants, sizes unknown) |
| Playlist (editorial / uploaded) | `ab67706f` / `ab67706c` | `00000002` / `0000da84`, `0000d72c` (no size given, about 300-640) |
| Show / episode | `ab676563` | `0000f68d` 64, `00005f1f` 300, `0000ba8a` 640 |
| Episode (other) | `ab676663` | `0000703b` 64, `0000db5b` 300, `000022a8` 640 |
| User avatar | `ab677570` | `00003b82` 64, `0000ee85` 300 |
| Music video thumb (16:9) | `ab6742d3` | `000052b7` 640x360, `000053b7` 1280x720 |
| Video episode thumb | `ab6772ab` | `0000e0e7` 640x360, `000030ae` 1280x720 |
| Canvas thumbnail | `ab67ba69` | `00002ea6` 144x256, `00002e9f` 288x512 |
| Merch | `ab676d63` | `000076a0`, `0000e1f0` 300 |

Responses list `sources[]` with `width`/`height` (or `maxWidth`/`maxHeight`, or `null`).
Choose from those first, and fall back to swapping the size code.

### mosaic.scdn.co (user playlists without an uploaded cover)

`https://mosaic.scdn.co/{60|300|640}/{img1}{img2}{img3}{img4}`: four album-cover ids
(always the `ab67616d00001e02…` 300 px variant) concatenated with no separator. Change only
the leading size.

### pickasso.spotifycdn.com (generated covers)

`https://pickasso.spotifycdn.com/image/ab67c0de{size}/dt/v1/img/{kind}/{path}/{locale}`
- `{kind}/{path}`: `radio/artist/{artistId}`, `artistmix/{artistId}`, `thisisv3/{id}`,
  `topic/{topic}/{id}`, `dw/cover` (Discover Weekly).
- `{size}`: `0000f00d` 64, `0000cafe` 640, `0000bada` 1280, `0000deef` default (sent
  with `height: null`).
- `{locale}`: the UI language (`fr`, `en`), because the title is drawn into the image.

Other static images: `misc.scdn.co/liked-songs/liked-songs-{64|300}.{png|jpg}`.

## Gaps

- **Lyrics 404 / no lyrics**: every captured lyrics call returned 200 LINE_SYNCED. Expected
  behaviour: 404 for tracks without lyrics (hide the lyrics button), but this is not verified.
  No `UNSYNCED` or `SYLLABLE_SYNCED` sample, and no free-tier `capStatus` other than `UNCAPPED`.
- Lyrics protobuf form (without `format=json`) was not captured. `vocalRemoval=true` was not seen.
- Size of `ab67616d0000aa54` and the artist-header `ab676186` variants is unknown.
- **Song radio**: no request captured. The uri and endpoint used by "Go to song radio" are
  unknown and need a new capture (`__mark__/song-radio`).
- Canvas `type` values other than `VIDEO_LOOPING_RANDOM` (e.g. `VIDEO_LOOPING`, `IMAGE`)
  were not seen. Whether `canvaz.scdn.co` URLs need auth is not tested (offline).
- Music video playback (beyond the prefetch) and the video license flow were not captured.
- `timesynced-text` `next` pagination and speaker names were always empty.
- The `queryNpvArtist` contributors pagination beyond 10 was not exercised.
