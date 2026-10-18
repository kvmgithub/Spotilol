# Catalog API (Web Player)

Clean-room spec of how open.spotify.com (free account, 2026-10-15 captures) fetches
track, album, artist and playlist data. Derived only from network captures.
Placeholders: `{token}` web access token, `{client-token}`, `{id}` base62 id (22 chars),
`{hex}` 32-char hex id, `{user}` a user id. Ids in examples are fake.

## Common request shape

### GraphQL (pathfinder v2)

`POST https://api-partner.spotify.com/pathfinder/v2/query`

| Header | Holds |
|---|---|
| `authorization` | `Bearer {token}` (web token from `open.spotify.com/api/token`) |
| `client-token` | `{client-token}` from `clienttoken.spotify.com/v1/clienttoken` |
| `app-platform` | constant platform name of the web player (`WebPlayer`) |
| `spotify-app-version` | web player build string (`1.2.x.xxxx.gxxxxxxxx` shape) |
| `content-type` | `application/json;charset=UTF-8` |
| `accept` | `application/json` |
| `accept-language` | UI language (`fr` in captures); localises type labels such as `identityTrait.type` |

Body:

```json
{"variables": {...}, "operationName": "getAlbum",
 "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "<hash>"}}}
```

Response: `{"data": {...}}`, `content-type: application/json`, gzip or zstd,
`cache-control: private, max-age=0`. No `errors` key appeared in any capture. A missing
or expired entity is not an HTTP error: it comes back as `200` with
`{"data":{"playlistV2":{"__typename":"NotFound","message":"Object with uri '...' not found"}}}`.
Always check `__typename` (`Track`, `Album`, `Artist`, `Playlist`, `NotFound`).

### spclient REST

Host `https://spclient.wg.spotify.com` (from `apresolve`). Same `authorization`,
`client-token`, `app-platform`, `spotify-app-version` headers as GraphQL.

## Operation index

| Operation | Hash (sha256, version 1) | Used for |
|---|---|---|
| `getTrack` | `a8ef9e9f02b836feb0da3003c31dbb30decc6f4b473ef89ca88c882386d668de` | Track page |
| `getAlbum` | `11c9cad9cb24c4f9e25ef9456f68621cfd6006a90f679e729c1d2f2fd70dfe16` | Album page (first page of tracks) |
| `queryArtistOverview` | `9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6` | Artist page |
| `queryNpvArtist` | `e1ae46a21911a3075c1aa29bf09a6c60f9a45b6a2b1132429f10ad06c299b5d7` | Now-playing view: artist card + track credits |
| `queryTrackArtists` | `ee2b038198f5e62c679c3996584d9249bbee55fe69fc212271c56492a022c798` | Artist list of one track |
| `queryAlbumMerch` | `3ef44ed6f17be67299538fe77faffab4075aeaf9e1085f10fc835592266711b5` | Merch row on album page (not needed) |
| `fetchPlaylist` | `21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf` | Playlist page: header + first 25 items |
| `fetchPlaylistContents` | same hash as `fetchPlaylist` | Next pages of items |
| `fetchPlaylistMetadata` | same hash as `fetchPlaylist` | Header + light item list (uri, duration) |
| `playlistPermissions` | `e43d1d35f231cf289c23c9d9c489f4a4f502e4eda09839c530608f107b6556b8` | Owner/members, edit rights |
| `isCurated` | `e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6` | "In your library/playlists" flag per uri |
| (unnamed, v1) | `f952da037440f694cc6925b9e3f649d39077a744c4db7dfba01cb883723f4f77` | Batch track lookup (`lookupEntities`) |

The three playlist operations share one persisted query; the server picks the selection
by `operationName`, so the name must be sent and must match.

## Shared fragments

| Fragment | JSON | Notes |
|---|---|---|
| Image list | `coverArt.sources[] {url, width, height}` | Album covers: 64, 300, 640 px (`i.scdn.co/image/ab67616d00004851…`, `…00001e02…`, `…0000b273…`). Artist avatars: 160, 320, 640. Playlist `images.items[].sources[]` often has `width`/`height` = `null` (mosaics, editorial covers). |
| ImageV2 | `data.sources[] {url, maxWidth, maxHeight, imageFormat}` | Header images, watch-feed thumbs, `itemV3` covers. `maxWidth`/`maxHeight` may be 0. |
| Colors | `coverArt.extractedColors.{colorRaw,colorDark,colorLight}.hex` (`"#D8B8C8"`) | `colorRaw` always present; dark/light on album. |
| Color set | `visualIdentity.squareCoverImage.extractedColorSet.{highContrast,higherContrast,minContrast}.{backgroundBase,backgroundTintedBase,textBase,textBrightAccent,textSubdued}.{red,green,blue,alpha}` | 0-255 ints. Also `encoreBaseSetTextColor`. |
| Artists | `artists.items[] {uri, profile.name}` (+ `id`, `visuals.avatarImage` on album/artist) | |
| Duration | `duration.totalMilliseconds` (track, album) / `trackDuration.totalMilliseconds` (playlist items) | |
| Playability | `playability {playable: bool, reason: "PLAYABLE"}` | Only `PLAYABLE` observed; `reason` sometimes absent. |
| Content rating | `contentRating.label` = `EXPLICIT` / `NONE`; `contentRatingV2.labels[]` (or `null`) | |
| Date | `date {isoString, precision, year, month?, day?}` | precision `DAY`, `YEAR`, `MINUTE` seen. |
| Album type | `type` = `ALBUM`, `SINGLE`, `EP`, `COMPILATION` | |
| Play count | `playcount` | String, not number (`"513599385"`). |
| Sharing | `sharingInfo {shareId, shareUrl}` | Not needed. |
| Paging | `{items[], totalCount, pagingInfo{offset, limit}}` | Some lists use `pagingInfo.nextOffset` (null = end). |

## getTrack

Variables: `uri` (string, `spotify:track:{id}`), `includeVideoAssociationItems` (bool, `true`).

Root `data.trackUnion` (`__typename: "Track"`):

| Path | Content |
|---|---|
| `id`, `uri`, `name`, `trackNumber`, `mediaType` (`AUDIO`) | identity |
| `duration.totalMilliseconds`, `playcount`, `saved` | |
| `playability`, `contentRating`, `contentRatingV2` | |
| `albumOfTrack {id, uri, name, type, date, coverArt, copyright.items[{text,type}], courtesyLine, playability, tracks{totalCount, items[].track{uri,trackNumber}}}` | album, with full track uri list |
| `firstArtist.items[0]` / `otherArtists.items[]` | `{id, uri, profile.name, visuals.avatarImage}`; first artist also has `discography {topTracks, popularReleasesAlbums, albums, singles}` (10 each) and `relatedContent.relatedArtists` (20 of `totalCount`) |
| `visualIdentity.squareCoverImage.extractedColorSet` | colors |
| `associationsV3.videoAssociations` | music video variant (`mediaType: VIDEO`), not needed |

No disc number in `getTrack` (use `metadata/4` or album).

```json
{"data":{"trackUnion":{"__typename":"Track","id":"{id}","uri":"spotify:track:{id}",
 "name":"Some Song","trackNumber":6,"mediaType":"AUDIO","saved":false,
 "duration":{"totalMilliseconds":317404},"playcount":"122666293",
 "playability":{"playable":true,"reason":"PLAYABLE"},
 "contentRating":{"label":"EXPLICIT"},"contentRatingV2":{"labels":["EXPLICIT"]},
 "albumOfTrack":{"id":"{id}","uri":"spotify:album:{id}","name":"Some Album","type":"ALBUM",
  "date":{"isoString":"2019-03-29T00:00:00Z","precision":"DAY","year":2019},
  "coverArt":{"extractedColors":{"colorRaw":{"hex":"#002858"}},
   "sources":[{"url":"https://i.scdn.co/image/{img}","width":300,"height":300}]},
  "tracks":{"totalCount":18,"items":[{"track":{"uri":"spotify:track:{id}","trackNumber":1}}]}},
 "firstArtist":{"totalCount":2,"items":[{"id":"{id}","uri":"spotify:artist:{id}",
  "profile":{"name":"Artist A"},
  "visuals":{"avatarImage":{"sources":[{"url":"…","width":640,"height":640}]}}}]},
 "otherArtists":{"items":[{"id":"{id}","uri":"spotify:artist:{id}","profile":{"name":"Artist B"}}]}}}}
```

## getAlbum

Variables: `uri` (`spotify:album:{id}`), `locale` (string, `intl-fr`, the URL locale
prefix; empty string likely for default), `offset` (int, `0`), `limit` (int, `50`).

Root `data.albumUnion` (`__typename: "Album"`), plus `data.meV2.ianaTimezoneId`.

| Path | Content |
|---|---|
| `uri`, `name`, `type`, `label`, `date {isoString, precision}` | |
| `isPreRelease`, `preReleaseEndDateTime` | pre-release flag |
| `coverArt {extractedColors{colorDark,colorLight,colorRaw}, sources[]}` | |
| `artists.items[] {id, uri, profile.name, visuals.avatarImage}` | |
| `copyright.items[{text, type}]` (`C`, `P`), `courtesyLine` | footer |
| `discs {totalCount, items[{number, tracks.totalCount}]}` | disc grouping |
| `tracksV2 {totalCount, items[]}` | page `offset..offset+limit` |
| `tracksV2.items[] {uid, track{uri, name, trackNumber, discNumber, duration, playcount, playability, contentRating, saved, relinkingInformation, artists.items[]}}` | track rows (no `id`; take it from `uri`) |
| `releases.items[{uri, name}]` | other editions |
| `moreAlbumsByArtist.items[0].discography.popularReleasesAlbums.items[]` | "More by" row |
| `playability`, `saved` | |

Paging: only `offset`/`limit` variables; `tracksV2` has `totalCount`. The captured album
had 2 tracks, so the follow-up request for albums over 50 tracks was not seen (see Gaps).

```json
{"data":{"albumUnion":{"__typename":"Album","uri":"spotify:album:{id}","name":"Some Single",
 "type":"SINGLE","label":"Some Label","isPreRelease":false,
 "date":{"isoString":"2026-05-22T00:00:00Z","precision":"DAY"},
 "playability":{"playable":true,"reason":"PLAYABLE"},"saved":false,
 "coverArt":{"extractedColors":{"colorDark":{"hex":"#86727C"},"colorLight":{"hex":"#D8B8C8"},
  "colorRaw":{"hex":"#D8B8C8"}},"sources":[{"url":"…","width":300,"height":300}]},
 "artists":{"totalCount":1,"items":[{"id":"{id}","uri":"spotify:artist:{id}",
  "profile":{"name":"Artist A"}}]},
 "copyright":{"totalCount":2,"items":[{"text":"© 2026 Label","type":"C"}]},
 "discs":{"totalCount":1,"items":[{"number":1,"tracks":{"totalCount":2}}]},
 "tracksV2":{"totalCount":2,"items":[{"uid":"1c367241bacbef393075","track":{
  "uri":"spotify:track:{id}","name":"Song","trackNumber":1,"discNumber":1,
  "duration":{"totalMilliseconds":297090},"playcount":"513599385",
  "contentRating":{"label":"EXPLICIT"},"playability":{"playable":true},"saved":false,
  "relinkingInformation":null,
  "artists":{"items":[{"uri":"spotify:artist:{id}","profile":{"name":"Artist A"}}]}}}]}},
 "meV2":{"ianaTimezoneId":"{tz}"}}}
```

## queryArtistOverview

Variables: `uri` (`spotify:artist:{id}`), `locale` (`intl-fr`), `preReleaseV2` (bool, `true`).

Root `data.artistUnion` (`__typename: "Artist"`):

| Path | Content |
|---|---|
| `id`, `uri`, `profile.name`, `saved` (= followed) | |
| `profile.biography {text, type}`, `profile.externalLinks.items[{name, url}]` | About |
| `onPlatformReputationTrait.verification {isVerified, isRegistered}` | verified badge |
| `stats {followers, monthlyListeners, worldRank, topCities.items[{city, country, region, numberOfListeners}]}` | |
| `visuals.avatarImage {sources[], extractedColors.colorRaw}`, `visuals.gallery.items[].sources[]` | |
| `headerImage.data.sources[] {url, maxWidth, maxHeight}` | banner (up to 1494x640) |
| `discography.topTracks.items[] {uid, track{id, uri, name, discNumber, duration, playcount, playability, contentRating, artists, albumOfTrack{uri, coverArt}}}` | Popular (10) |
| `discography.latest` | latest release (single object) |
| `discography.popularReleasesAlbums {totalCount, items[]}` | release objects directly |
| `discography.albums / singles / compilations {totalCount, items[].releases.items[0]}` | first 10 (albums 5 here); release = `{id, uri, name, type, label, date{year,month,day,precision}, coverArt, tracks.totalCount, playability, copyright}` |
| `relatedContent.relatedArtists {totalCount, items[{id, uri, profile.name, visuals.avatarImage}]}` | Fans also like (20) |
| `relatedContent.appearsOn {totalCount, items[].releases.items[0]{id, uri, name, type, date.year, coverArt, artists}}` | (20) |
| `relatedContent.featuringV2.items[].data {id, uri, name, description, images, ownerV2.data.name}` | "Featuring" playlists |
| `relatedContent.discoveredOnV2.items[]` | Discovered on playlists |
| `profile.playlistsV2.items[].data` | Artist playlists |
| `profile.pinnedItem {type, uri, title, subtitle, itemV2.data}` | Artist pick |
| `goods.concerts`, `goods.merch`, `relatedMusicVideos`, `unmappedMusicVideosV2`, `watchFeedEntrypoint`, `preReleaseV2` | not needed |

```json
{"data":{"artistUnion":{"__typename":"Artist","id":"{id}","uri":"spotify:artist:{id}",
 "saved":false,"profile":{"name":"Artist A","biography":{"text":"…","type":"AUTOBIOGRAPHY"}},
 "onPlatformReputationTrait":{"verification":{"isVerified":true,"isRegistered":true}},
 "stats":{"followers":57694926,"monthlyListeners":67636683,"worldRank":28},
 "visuals":{"avatarImage":{"sources":[{"url":"…","width":640,"height":640}]}},
 "headerImage":{"data":{"sources":[{"url":"…","maxWidth":1494,"maxHeight":640}]}},
 "discography":{
  "topTracks":{"items":[{"uid":"7f50dad9287d30ea940b","track":{"id":"{id}",
   "uri":"spotify:track:{id}","name":"Song","discNumber":1,
   "duration":{"totalMilliseconds":297090},"playcount":"513599385",
   "contentRating":{"label":"EXPLICIT"},"playability":{"playable":true,"reason":"PLAYABLE"},
   "artists":{"items":[{"uri":"spotify:artist:{id}","profile":{"name":"Artist A"}}]},
   "albumOfTrack":{"uri":"spotify:album:{id}","coverArt":{"sources":[]}}}}]},
  "albums":{"totalCount":5,"items":[{"releases":{"items":[{"id":"{id}",
   "uri":"spotify:album:{id}","name":"Album","type":"ALBUM",
   "date":{"year":2026,"month":6,"day":12,"precision":"DAY"},
   "tracks":{"totalCount":13},"coverArt":{"sources":[]}}]}}]},
  "singles":{"totalCount":25,"items":[]}},
 "relatedContent":{"relatedArtists":{"totalCount":40,"items":[{"id":"{id}",
  "uri":"spotify:artist:{id}","profile":{"name":"Artist B"}}]}}}}}
```

### Other artist operations

| Op | Variables | Returns |
|---|---|---|
| `queryNpvArtist` | `artistUri`, `trackUri`, `contributorsLimit` (10), `contributorsOffset` (0), `enableRelatedVideos` (true), `enableRelatedAudioTracks` (true) | `data.artistUnion {id, uri, profile{name, biography, externalLinks}, stats, visuals.avatarImage, headerImage, onPlatformReputationTrait, goods.concerts}` and `data.trackUnion {creditsTrait.contributors.items[{name, role, roleGroup.name, uri, url}], creditsTrait.sources.items[{name}], canvas, merch, relatedVideos}`. Credits roles are localised strings. |
| `queryTrackArtists` | `trackUri` | `data.trackUnion {uri, artists.items[{uri, profile.name}]}` |
| `queryAlbumMerch` | `uri` (album) | merch only |

## Playlists

### fetchPlaylist

Variables: `uri` (`spotify:playlist:{id}`), `offset` (0), `limit` (25),
`enableWatchFeedEntrypoint` (true), `includeEpisodeContentRatingsV2` (true).

Root `data.playlistV2` (`Playlist` or `NotFound`):

| Path | Content |
|---|---|
| `uri`, `name`, `description` | description may hold HTML links: `<a href=spotify:playlist:{id}>name</a>` |
| `images.items[].sources[] {url, width, height}` | usually one source, size `null`; empty `items` for an empty user playlist |
| `ownerV2.data {__typename:"User", uri, username, name, avatar.sources[]}` | owner |
| `members {totalCount, items[{isOwner, permissionLevel, user.data}]}` | |
| `followers`, `following`, `format` (`""` for user playlists, e.g. `artist-mix-reader`), `revisionId` | |
| `basePermission` (`VIEWER`), `currentUserCapabilities {canView, canEditItems, canEditMetadata, canAdministratePermissions, canCancelMembership, canAbuseReport, canMixPlaylist}` | edit rights |
| `attributes[{key, value}]` | e.g. `primary_color`, `header_image_url_desktop`, `status`, `autoplay` |
| `content {totalCount, pagingInfo{offset, limit}, items[]}` | first page |
| `content.items[] {uid, addedAt.isoString, addedBy, attributes[], itemV2, itemV3}` | |
| `addedBy` | `null` for editorial; `{data:{uri, username, name, avatar}}` on user playlists |
| `itemV2.__typename` / `itemV2.data.__typename` | `TrackResponseWrapper`/`Track` (only kind captured; episodes expected as `EpisodeOrChapterResponseWrapper`/`Episode`, as in `isCurated`) |
| `itemV2.data {uri, name, trackNumber, discNumber, trackDuration.totalMilliseconds, playcount, playability, contentRating, mediaType, artists.items[], albumOfTrack{uri, name, date, artists, coverArt}}` | row |
| `itemV2.data.playlistAudioAttributes {bpm, key{key, mode, camelotKey{value, color}}}` | optional |
| `itemV3.data` | trait form (`identityTrait`, `visualIdentityTrait`, `consumptionExperienceTrait`); duplicate of itemV2, not needed |

`uid` is the per-row id needed to address a row (remove, move, play from row).

### fetchPlaylistContents

Variables: `uri`, `offset`, `limit` (50), `includeEpisodeContentRatingsV2` (true).
Returns `data.playlistV2 {__typename, content}` only, same item shape.
Paging seen: first page `fetchPlaylist(offset 0, limit 25)`, then
`fetchPlaylistContents(offset 25, limit 50)`, next `offset 75`, until
`offset >= content.totalCount`. Note `pagingInfo.limit` echoes the request limit,
not the returned count.

### fetchPlaylistMetadata

Variables: `uri`, `offset` (0), `limit` (100), `enableWatchFeedEntrypoint` (true).
Same header fields as `fetchPlaylist`; `content.items[]` are light:
`{itemV2:{__typename, data:{__typename:"Track", uri, trackDuration.totalMilliseconds}}}`
(no `uid`). `pagingInfo` has only `limit`. Used for whole-playlist duration and
play/queue uri lists. Called repeatedly for library rows; returns `NotFound` for
expired personal mixes.

```json
{"data":{"playlistV2":{"__typename":"Playlist","uri":"spotify:playlist:{id}",
 "name":"Top Hits","description":"The hottest 50.","followers":33718960,"following":false,
 "format":"","revisionId":"AAAA…","basePermission":"VIEWER",
 "currentUserCapabilities":{"canView":true,"canEditItems":false,"canEditMetadata":false},
 "images":{"items":[{"sources":[{"url":"https://i.scdn.co/image/{img}","width":null,"height":null}]}]},
 "ownerV2":{"data":{"__typename":"User","uri":"spotify:user:{user}","username":"{user}",
  "name":"Owner","avatar":{"sources":[]}}},
 "content":{"__typename":"PlaylistItemsPage","totalCount":50,
  "pagingInfo":{"offset":0,"limit":25},
  "items":[{"uid":"654e44597543482f767755","addedAt":{"isoString":"2026-09-04T04:02:23.110Z"},
   "addedBy":null,"itemV2":{"__typename":"TrackResponseWrapper","data":{"__typename":"Track",
    "uri":"spotify:track:{id}","name":"Song","trackNumber":1,"discNumber":1,
    "trackDuration":{"totalMilliseconds":198840},"playcount":"100749741",
    "contentRating":{"label":"NONE"},"playability":{"playable":true,"reason":"PLAYABLE"},
    "artists":{"items":[{"uri":"spotify:artist:{id}","profile":{"name":"Artist"}}]},
    "albumOfTrack":{"uri":"spotify:album:{id}","name":"Album",
     "coverArt":{"sources":[{"url":"…","width":64,"height":64}]}}}}}]}}}}
```

### playlistPermissions

Variables: `uri`. Returns `data.playlistV2 {basePermission, currentUserCapabilities
{canAdministratePermissions, canCancelMembership, canEditItems, canEditMetadata, canView,
grantableLevel[]}, members.items[{isOwner, permissionLevel, user.data{uri, username, name, avatar}}]}`.

### isCurated

Variables: `uris` (string list; tracks, episodes, ads; 1 to ~25 seen).
Returns `data.lookup[]` in request order:
`{"__typename":"TrackResponseWrapper","data":{"__typename":"Track","isCurated":false}}`
(`EpisodeOrChapterResponseWrapper`/`Episode` for episodes). `isCurated` = already saved
somewhere in the user's library (drives the check vs plus icon).

## Batch track lookup (pathfinder v1, unnamed)

`POST https://api-partner.spotify.com/pathfinder/v1/query`, body without
`operationName`: `{"variables":{"uris":[…]},"extensions":{"persistedQuery":{"version":1,
"sha256Hash":"f952da03…4f77"}}}`. Sent without `app-platform`/`spotify-app-version`.
Returns `data.lookupEntities[] {uri, entityTypeTrait.type ("ENTITY_TYPE_TRACK"),
consumptionExperienceTrait.duration{seconds,nanoSeconds}, identityTrait{name,
contentHierarchyParent.uri}, typedEntity.data{uri, name, duration.totalMilliseconds,
playability.playable, relinkingInformation, albumOfTrack{uri, name, coverArt.sources[]},
artists.items[]}}`. Useful to hydrate a list of uris (queue, recently played) in one call.

## REST: extended-metadata

`POST {spclient}/extended-metadata/v0/extended-metadata`
Request headers add `content-type: application/json;charset=UTF-8`, `accept: application/protobuf`.
Request is JSON; response is protobuf (`application/protobuf`, zstd).

Request:

```json
{"entityRequest":[{"entityUri":"spotify:track:{id}",
  "query":[{"extensionKind":178,"etag":""},{"extensionKind":179,"etag":""}]}]}
```

Response (inferred field numbers):

```
BatchedExtensionResponse
  2: repeated EntityExtensionDataArray      // one per extensionKind
       1: header {1: status(int, 200), 2: cache ttl s (3600), 3: offline ttl s (2592000)}
       2: extension_kind (int)
       3: repeated EntityExtensionData
            1: header {1: status (200 / 404), 2: etag (string), 4: cache ttl, 5: offline ttl}
            2: entity_uri (string)
            3: google.protobuf.Any {1: type_url, 2: value}   // absent when status 404
```

| Kind | Entities seen | `type_url` (`type.googleapis.com/…`) | Inferred value fields |
|---|---|---|---|
| 178 | track, artist, playlist, list | `spotify.contentagnostic.v2.IdentityTrait` | 1 type label (localised), 2 name, 3 description, 4 parent `{1 name, 2 uri}` (album), 5 repeated contributor `{1 name, 2 uri}` |
| 179 | track, artist, playlist | `…v2.VisualIdentityTrait` | 1 square cover `{1 repeated image {1 {1 url}, 2 size enum}, 2 color set}`; 2 16:9 image; 4 video (canvas url). Size enum: 1 = 64 px, 2 = 300, 3 = 640, 4 = large/640 |
| 182 | track | `…v2.ConsumptionExperienceTrait` | 1 duration `{1 seconds}`, 2 content ratings (packed enum), 4 formats (packed enum) |
| 184 | artist | `…v2.DetailedEvaluationTrait` | not decoded |
| 185 | track, artist | `…v2.OnPlatformReputationTrait` | track: 3 = unix seconds; artist: verification |
| 186 | track | `…v2.CreditsTrait` | 1 repeated contributor `{1 name, 2 role, 3 uri, 4 {1 uri}, 5 url, 6 {1 role group}}` |
| 205 | playlist | `spotify.list.v1.model.Attributes` | 3 name, 4 description, 5 `{1 timestamp}`, 7 owner username, 9 capabilities |
| 220 | track | `…v2.EntityTypeTrait` | 1 type enum (1 = track) |
| 246 | track, episode | `…v2.CurationExperienceTrait` | 1 uri, 2 `{2 {1 playlist uri}}`, 3 `{4 repeated playlist uri}` (where the item is saved) |
| 249 | track, ad | `…v2.ContentExperienceTrait` | 1 enum (1 = music) |
| 22, 80, 164, 270 | track, artist | none | always 404 for this account |

For catalog rows the GraphQL ops already carry everything; extended-metadata is only
needed if the client wants cheap batched name/cover/duration (178 + 179 + 182).

## REST: metadata/4/track

`GET {spclient}/metadata/4/track/{hex}?market=from_token`, `accept: application/json`.
Response JSON, `cache-control: public, max-age=…`, `etag`.

`{hex}` is the 128-bit base62 id as 32 lowercase hex chars. Alphabet
`0-9a-zA-Z` (`0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ`):

```kotlin
const val B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
fun base62ToHex(id: String): String =
    id.fold(BigInteger.ZERO) { n, c -> n * 62.toBigInteger() + B62.indexOf(c).toBigInteger() }
      .toString(16).padStart(32, '0')
fun hexToBase62(hex: String): String {
    var n = BigInteger(hex, 16); val sb = StringBuilder()
    while (n > BigInteger.ZERO) { val (q, r) = n.divideAndRemainder(62.toBigInteger()); sb.append(B62[r.toInt()]); n = q }
    return sb.reverse().toString().padStart(22, '0')
}
```

Verified on capture: `55vPnuXfuzXNW75JpuSczF` <-> `a734c1d4e4d84c4093c32d5bce9797a3`.

Fields: `gid` (hex), `name`, `number`, `disc_number`, `duration` (ms), `popularity`,
`external_id[{type:"isrc", id}]`, `has_lyrics`, `language_of_performance[]`,
`earliest_live_timestamp`, `artist[{gid, name}]`, `artist_with_role[{artist_gid,
artist_name, role:"ARTIST_ROLE_MAIN_ARTIST"}]`, `album{gid, name, type, label,
date{year,month,day}, artist[], cover_group.image[{file_id, size: SMALL|DEFAULT|LARGE,
width, height}]}`, `canonical_uri`, `original_audio{uuid, format}`, `audio_formats[]`,
`media_type`, `content_authorization_attributes` (opaque hex). Cover url =
`https://i.scdn.co/image/{file_id}`. Note `canonical_uri` may differ from the
requested id (relinking). No audio `file[]` list appeared in this web response.

## REST: artist popular-release segments

`GET {spclient}/playlist/v2/list/popular-release-segments-main-roles/artist_{id}?decorate=revision,length,attributes,timestamp,owner,capabilities`
(`decorate` URL-encoded), `accept: application/json`. Called on the artist page; the
list is the full "Popular" track list (used to play the artist).

```json
{"revision":"AAAA…","length":44,"timestamp":"1792099180000","ownerUsername":"spotify",
 "isUserCreated":false,"abuseReportingEnabled":false,
 "attributes":{"name":"Artist A Popular","collaborative":false,
  "format":"popular-release-segments-main-roles",
  "formatAttributes":[{"key":"reporting.uri","value":"spotify:artist:{id}"},
   {"key":"total_number_of_tracks","value":"44"}]},
 "capabilities":{"canView":true,"canEditItems":false,"canEditMetadata":false},
 "contents":{"pos":0,"truncated":false,
  "items":[{"uri":"spotify:track:{id}","attributes":{"itemId":"XU0dKAQW5IRVSA=="}}]}}
```

All `length` items came in one response (`truncated:false`); only uris, so hydrate with
the v1 batch lookup or extended-metadata.

## Gaps

| Missing | Capture that would answer it |
|---|---|
| Album track paging past 50 (op name/hash, e.g. a `queryAlbumTracks`) | Open an album with > 50 tracks and scroll to the end |
| Full artist discography (Albums / Singles "Show all", e.g. `queryArtistDiscographyAll/Albums/Singles`), "Appears on", related artists page | Artist page, click each "Show all" and scroll |
| Liked Songs listing op (CAPTURE-MAP says `fetchPlaylist` on `collection/tracks`, not present in these captures) | Open Liked Songs and scroll |
| Playlist items that are episodes, local files or unavailable tracks (`playable:false`, other `reason` values, `relinkingInformation` non-null) | Playlist containing a podcast episode, a local file and a region-blocked/removed track |
| `getAlbum` with empty `locale`, other `locale` values; whether `locale` changes text | Same album from `open.spotify.com/album/…` without `/intl-xx/` |
| GraphQL error format (`errors[]`), behaviour on expired token or wrong hash | Replay with an old token / bad hash (not done: offline rule) |
| Pre-release albums (`isPreRelease:true`, `preReleaseV2`) | Artist with an announced pre-save album |
| Extended-metadata kinds 22, 80, 164, 184, 270 payloads (404 or undecoded) | Premium account capture, or same calls on other entity types |
