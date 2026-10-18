# Web Player session and transport

Clean-room notes from network captures of open.spotify.com (free account, Chrome on
Linux, 2026-10-15, builds `1.3.6.1858` and `1.3.6.1874`). Values below are placeholders:
`{token}` web access token, `{ct}` client token, `{dev}` 40-hex device id, `{conn}`
dealer connection id, `{user}` account id. See CAPTURE-MAP.md for the feature calls.

## 1. Tokens

### Web access token

`GET https://open.spotify.com/api/token?reason={r}&productType=web-player&totp={code}&totpServer={code}&totpVer={n}`

| Param | Meaning |
|---|---|
| `reason` | `init` on page load, `transport` when the player refreshes it before expiry |
| `productType` | always `web-player` |
| `totp`, `totpServer` | 6-digit TOTP codes, computed in the page from local time and from server time (usually equal) |
| `totpVer` | version of the TOTP secret (61 in the capture) |

Auth is the `sp_dc` cookie (session cookie set by accounts.spotify.com; `sp_t`, `sp_key`
also sent). No Authorization header. Before a `transport` refresh the page calls
`GET open.spotify.com/api/server-time` -> `{"serverTime": <unix seconds>}`.

Response (JSON): `clientId`, `accessToken`, `accessTokenExpirationTimestampMs`,
`isAnonymous`, `_notes`. Lifetime is about 1 hour (55 min left on first fetch). The
server caches the token per cookie: reloads within the hour return the same token and
expiry. A `transport` call returned a new token with a fresh hour.

The token is used as `authorization: Bearer {token}` on pathfinder and spclient, and as
the `access_token` query param of the dealer WebSocket.

### Client token

`POST https://clienttoken.spotify.com/v1/clienttoken`, `content-type` and `accept`
`application/json`, no auth header.

```json
{"client_data": {
  "client_version": "1.3.6.1858.gc66299947cb4",
  "client_id": "<same value as clientId from /api/token>",
  "js_sdk_data": {"device_brand": "unknown", "device_model": "unknown", "os": "linux",
                  "os_version": "unknown", "device_id": "<36-char id, not {dev}>",
                  "device_type": "computer"}}}
```

Response: `response_type` = `RESPONSE_GRANTED_TOKEN_RESPONSE`, `granted_token`:
`token`, `expires_after_seconds` (1216800, about 14 days), `refresh_after_seconds`
(1209600, 14 days), `domains` (`spotify.com`, `spotify.net`). Fetched once per page
load; can be cached for days.

### Headers on API calls

| Header | api-partner (pathfinder) | spclient.wg | {region}-spclient | Meaning |
|---|---|---|---|---|
| `authorization` | always | almost always | most | `Bearer {token}` |
| `client-token` | always | always | most | `{ct}` from clienttoken |
| `app-platform` | yes | yes | some | `WebPlayer` |
| `spotify-app-version` | yes | yes | some | build `clientVersion`, e.g. `1.3.6.1858.gc66299947cb4` |
| `accept-language` | `fr` (UI locale) | `fr` | browser list | content language |
| `accept` | `application/json` | `application/json` or `application/protobuf` | `*/*` | |
| `content-type` | `application/json;charset=UTF-8` | JSON | JSON or `text/plain` | |

Extra per-endpoint headers: `x-spotify-connection-id: {conn}` on
`PUT connect-state/v1/devices/hobs_*`; `x-cache-hint: <random 16-hex>` on
`GET connect-state/v1/cluster`; `x-client-id` on `pendragon/.../FetchMessageList`.

Exceptions: `gabo-receiver-service/public/v3/events` and
`widevine-license/v1/application-certificate` carry no `authorization`;
`gabo-receiver-service/*/events` carry no `client-token`. Every pathfinder call
sent both `authorization` and `client-token`, so a client should always send both;
whether the web token alone is accepted was not tested (offline capture).

Pathfinder calls of `getAlbum` and `queryArtistOverview` sometimes sent
`spotify-app-version: 896000000` instead of the dotted version; both were accepted.

## 2. Hosts

`GET https://apresolve.spotify.com/?type=dealer-g2&type=spclient` (no auth). Response:

```json
{"dealer-g2": ["gew4-dealer.g2.spotify.com:443", "guc3-dealer.g2.spotify.com:443", "..."],
 "spclient":  ["gew4-spclient.spotify.com:443",   "guc3-spclient.spotify.com:443",   "..."]}
```

Lists are ordered by preference (nearest first); the page took the first entry of
each (`gew4-*` from Europe) and falls back to the next on failure. Called several
times per page load.

| Host | Used for |
|---|---|
| `api-partner.spotify.com` | GraphQL: `POST /pathfinder/v2/query` (plus one `v1` call, see 3) |
| `spclient.wg.spotify.com` | Fixed global alias. Content and library APIs: `metadata/4`, `extended-metadata`, `collection/v2`, `playlist/v2`, `playlistextender`, `color-lyrics`, `timesynced-text-distribution`, `ads/v2/config`, `gander`, `library-import`, `widevine-license/.../application-certificate` |
| `{region}-spclient.spotify.com` (from apresolve) | Session-bound and playback APIs: `track-playback/v1`, `connect-state/v1`, `manifests`, `storage-resolve`, `widevine-license/.../license`, `melody`, `gabo-receiver-service`, `remote-config-resolver`, `pendragon`, `social-connect` |

Both hosts accept the same auth headers. Connect-state and track-playback must use the
regional host (same region as the dealer, since the dealer connection id is bound to it).

## 3. GraphQL persisted queries

Body of `POST https://api-partner.spotify.com/pathfinder/v2/query`:

```json
{"variables": {...}, "operationName": "fetchPlaylist",
 "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "<64 hex>"}}}
```

One odd call goes to `pathfinder/v1/query` without `operationName`
(`variables.uris` = list of track URIs, response `data.lookupEntities`, hash
`f952da037440f694cc6925b9e3f649d39077a744c4db7dfba01cb883723f4f77`); it is hard-coded
in `vendor~web-player.*.js` as a raw `sha256Hash:"..."` string.

### Where hashes live in the bundles

Each operation is a module constant built with a minified class constructor:

```js
let p=new C.l("concertCount","query","29be9d48...b141",null)
```

Extraction regex (worked on every bundle, no false positives; class alias varies):

```
new [\w$]+(?:\.[\w$]+)?\("(\w+)","(query|mutation|subscription)","([0-9a-f]{64})"
```

Groups: operationName, kind, sha256Hash. A second pass for raw literals:
`sha256Hash:"([0-9a-f]{64})"`.

Most operations of the main pages (`home`, `fetchPlaylist`, `libraryV3`, `getAlbum`,
`queryArtistOverview`, `addToLibrary`...) are defined in the 4.4 MB entry bundle
`web-player.{hash}.js`, whose body was not captured. Their hashes in the table come from
the request bodies instead. Every operation had a single hash across both builds seen.

### Refreshing hashes automatically

1. `GET https://open.spotify.com/` (HTML, with the session cookie or anonymous).
2. Read `<script id="appServerConfig" type="text/plain">` (base64 JSON): fields
   `clientVersion` (use for `spotify-app-version` and clienttoken `client_version`),
   `buildVersion` (`web-player_{date}_{ms}_{sha}`), `buildDate`, `serverTime`,
   `market`, `locale`, `isPremium`, `isAnonymous`. Compare `buildVersion` with the
   cached one; refresh only when it changed.
3. Read `<script id="__CDN_FILE_URLS__" type="text/plain">` (base64 JSON map, about
   1000 entries): key `build/web-player/{chunk}.js` (and the hashed name) ->
   full URL `https://open.spotifycdn.com/cdn/build/web-player/{chunk}.{hash8}.js`.
   About 164 web-player JS chunks, plus `web-player.js` (entry),
   `vendor~web-player.js`, `encore~web-player.js`. The entry, vendor and encore
   scripts are also plain `<script src>` tags in the HTML.
4. Download the entry bundle and every `build/web-player/*.js` chunk, run the regex,
   merge into an `operationName -> hash` map, persist it with the build version.
5. Fallback at runtime: if pathfinder returns a `PersistedQueryNotFound`-style error
   for an op, trigger step 1-4 once and retry (error shape not captured).

### Table

99 operations: 69 defined in captured chunk bodies, 30 more only seen in request
bodies (they are in the uncaptured entry bundle). `kind` is `-` when only the request
was seen.

| operationName | kind | sha256Hash | seen in |
|---|---|---|---|
| `accountAttributes` | - | `d48062d0df0a256ff78ed9e232b83d238dcd710b371efd304b0e178b62595582` | request (main bundle) |
| `addToLibrary` | - | `896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148` | request (main bundle) |
| `addToPlaylist` | - | `b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a` | request (main bundle) |
| `applyCurations` | - | `05b739a3a73091c213385233b9d3ed8a857c2ca29d2eebadb3d04ed12e288697` | request (main bundle) |
| `areEntitiesInLibrary` | - | `134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed` | request (main bundle) |
| `ArtistConcerts` | query | `ef53c43b865496b9890b7167eab1dc614a8949ef9451b3c41184ea888de8bd2b` | bundle `xpui-routes-artist.8dd6925c` |
| `ArtistConcertsPageLocation` | query | `320698465a352f0d0247ec8ed02471244106d4199820f99de4d0a785561c2b03` | bundle `xpui-routes-artist.8dd6925c` |
| `browseAll` | query | `dbd8b55e09a58afc52eab438bc228ba28fd72ac2f2148c6c26354980e4579001` | bundle `xpui-routes-search.1472683a` + request |
| `canvas` | - | `575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f` | request (main bundle) |
| `centralisedStatePlayerOptions` | - | `e2dcfcab470854d4d1c7cb1a851438f14fe0a94d57db7f0b9dde492559d5395d` | request (main bundle) |
| `concert` | query | `9b582a69061f6648858515c2aa6d4a2273ac49b74eb5dbc612bc1299a9f2f13e` | bundle `xpui-root-dialogs.7582d917` |
| `concertCount` | query | `29be9d486e073a49268e13ed9e2d2180187e669fcb7a19b98011aca7ab61b141` | bundle `1272.990d4950` |
| `concertLocationsByLatLon` | query | `8a059d072a17a1199feb21fe846271f1680eda87010c832852ced0c55c6c7c96` | bundle `1272.990d4950` |
| `decorateContextTracks` | - | `383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7` | request (main bundle) |
| `decorateQueuedByUsers` | query | `33fe7085b4a9dde404955eeee4f8f93aa7a8e55028058354bc707727d8a7890d` | bundle `dwp-panel-section.0a1acab1` + request |
| `deleteLocation` | mutation | `2f681a7c30da3bc905c469374aa2983d0d00a1b6ba88e5fdd6a23caa02057971` | bundle `1272.990d4950` |
| `editablePlaylists` | - | `d5c4b8096437dcc2ac9528c91dfcd299e35b747cda2f8f75d28f41f49c5092ba` | request (main bundle) |
| `feedBaselineLookup` | - | `318190494c436b51ab995367bffc66dcdb49dfa023792c60ac15dcb6dfbec42f` | request (main bundle) |
| `fetchEntitiesForRecentlyPlayed` | - | `cf5d2e94ffd82788470788ae1f6090cc3e9e774fb8fd383580634c6e6f50f7be` | request (main bundle) |
| `fetchExtractedColorAndImageForAlbumEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorAndImageForArtistEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorAndImageForEpisodeEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorAndImageForPlaylistEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorAndImageForPodcastEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorAndImageForTrackEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForAlbumEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForArtistEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForEpisodeEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForPlaylistEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForPodcastEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColorForTrackEntity` | query | `b2d6d99fb6237952dfa6638385f9c6085f1b1fb9d0468753ca6ad98ff45adc6f` | bundle `xpui-root-dialogs.7582d917` |
| `fetchExtractedColors` | - | `36e90fcaea00d47c695fce31874efeb2519b97d4cd0ee1abfb4f8dc9348596ea` | request (main bundle) |
| `fetchPlaylist` | - | `21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf` | request (main bundle) |
| `fetchPlaylistContents` | - | `21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf` | request (main bundle) |
| `fetchPlaylistMetadata` | - | `21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf` | request (main bundle) |
| `getAlbum` | - | `11c9cad9cb24c4f9e25ef9456f68621cfd6006a90f679e729c1d2f2fd70dfe16` | request (main bundle) |
| `getAudiobooksMetadata` | query | `523c71c64749a628f83e6b31a122c76663243730bf02df01fd64abf0f62f572f` | bundle `1675.fa3bc7da` |
| `getDynamicColorsByUris` | - | `f0f112945d6d745bd8ff790317bbf8d310036da75df33130490e9d6dc96c59d9` | request (main bundle) |
| `getTrack` | - | `a8ef9e9f02b836feb0da3003c31dbb30decc6f4b473ef89ca88c882386d668de` | request (main bundle) |
| `home` | - | `5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1` | request (main bundle) |
| `inferredUserLocation` | query | `5db4c507ea735d2a1f37bd1166eca2c1a0e3387bb875ebca5d6031b6eccceeba` | bundle `1272.990d4950` |
| `internalLinkRecommenderTrack` | query | `c77098ee9d6ee8ad3eb844938722db60570d040b49f41f5ec6e7be9160a7c86b` | bundle `xpui-routes-track-v2.f48fb16e` + request |
| `isCurated` | - | `e4ed1f91a2cc5415befedb85acf8671dc1a4bf3ca1a5b945a6386101a22e28a6` | request (main bundle) |
| `libraryV3` | - | `390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455` | request (main bundle) |
| `lookupChildEntities` | query | `91ce02e32b19123de231dc8de91fe4b9ab84eca087d4c015549308d77fbb6d10` | bundle `7338.5565a19f` + request |
| `npvV2TrackSupplementalSections` | query | `3268e307f08330b620de5c161a03b48acf66ec57164eac446e0653694a485764` | bundle `dwp-panel-section.0a1acab1` + request |
| `playlistPermissions` | - | `e43d1d35f231cf289c23c9d9c489f4a4f502e4eda09839c530608f107b6556b8` | request (main bundle) |
| `playlistSection` | query | `2615df403a9043c1d7d3094fbeb4c9653b07b11a33d8081fbd31f0f7959ff4a1` | bundle `xpui-routes-playlist.7090f328` |
| `profileAttributes` | - | `08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0` | request (main bundle) |
| `queryAlbumMerch` | query | `3ef44ed6f17be67299538fe77faffab4075aeaf9e1085f10fc835592266711b5` | bundle `xpui-routes-album.7c466d0f` + request |
| `queryArtistAboutModal` | query | `702e29a866e0b4b4fabdf974454512a2c32d93643b1969b7a3a0dd92084f05f6` | bundle `xpui-root-dialogs.7582d917` |
| `queryArtistAppearsOn` | query | `9a4bb7a20d6720fe52d7b47bc001cfa91940ddf5e7113761460b4a288d18a4c1` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscographyAlbums` | query | `5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscographyAll` | query | `5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscographyCompilations` | query | `5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscographyOverview` | query | `5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscographySingles` | query | `5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistDiscoveredOn` | query | `71c2392e4cecf6b48b9ad1311ae08838cbdabcfd189c6bf0c66c2430b8dcfdb1` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistFeaturing` | query | `20842d6d9d2d28ef945984b68cb927bb33edd00eab84a8da1667def21f1f2c54` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistMinimal` | query | `53d3f76582c49ad0a05dc685955f20dc2a5f2209b192e5446e5e4e623ce23a48` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistOverview` | - | `9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6` | request (main bundle) |
| `queryArtistPlaylists` | query | `54f7e5a5a2af05b7dc98526df376a46c6b15c05440c8dfdc8f6cecb1a807eca7` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistRelated` | query | `3d031d6cb22a2aa7c8d203d49b49df731f58b1e2799cc38d9876d58771aa66f3` | bundle `xpui-routes-artist.8dd6925c` |
| `queryArtistRelatedVideos` | query | `e4bbb4f420ad5786caa7028e2943542f13c8138f5c40736292b8499ac9672ce6` | bundle `xpui-routes-artist.8dd6925c` |
| `queryInlineCurationSearchAlbum` | query | `122fd4817f7e9cd7b944c3103d9c81a950668423ba9d2008413b5291f9d7e547` | bundle `xpui-routes-playlist.7090f328` |
| `queryInlineCurationSearchArtist` | query | `be42f25d92bdadac4082e6581d35b771c3b5e8bbdd1b76c279d0924a5975f12e` | bundle `xpui-routes-playlist.7090f328` |
| `queryInlineCurationSearchV2` | query | `331ddd828a4db217046345833863f59f907d269947dd6f924009f6ab35743b70` | bundle `xpui-routes-playlist.7090f328` |
| `queryInlineCurationSearchV2Booklists` | query | `331ddd828a4db217046345833863f59f907d269947dd6f924009f6ab35743b70` | bundle `xpui-routes-playlist.7090f328` |
| `queryNpvArtist` | - | `e1ae46a21911a3075c1aa29bf09a6c60f9a45b6a2b1132429f10ad06c299b5d7` | request (main bundle) |
| `queryTrackArtists` | - | `ee2b038198f5e62c679c3996584d9249bbee55fe69fc212271c56492a022c798` | request (main bundle) |
| `queryTrackCreditsGroupedModal` | query | `f135fb9be58a72d041ab5d214d817021a272405d883860468e2627afb01a3ca9` | bundle `xpui-root-dialogs.7582d917` |
| `recentSearches` | - | `ece96d7b82c820fb9938dd1df50dee3e53737d808c3883e3d01c467c423601b0` | request (main bundle) |
| `removeFromLibrary` | - | `896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148` | request (main bundle) |
| `saveLocation` | mutation | `5502351e9f201ae29014ca55d3b24b755ba261a1a9eb35fb498cb4c7df419353` | bundle `1272.990d4950` |
| `searchAlbums` | query | `202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9` | bundle `xpui-routes-search.1472683a` + request |
| `searchArtists` | query | `7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d` | bundle `xpui-routes-search.1472683a` + request |
| `searchAudiobooks` | query | `e05ac765d02c084f8783d3c1572b23d57761c43f47eb8b87ce2f9ccced3fa068` | bundle `xpui-routes-search.1472683a` |
| `searchAuthors` | query | `4a9d403a7cbc7e19da5520d619a865472b35382b043bfa458154e73a5c6f46bd` | bundle `xpui-routes-search.1472683a` |
| `searchConcertLocations` | query | `43ededefcba8b3f519fd0c2d6c025dfeec9f742cf47d04a3c3711d95b27deda3` | bundle `1272.990d4950` |
| `searchDesktop` | query | `eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03` | bundle `xpui-routes-search.1472683a` |
| `searchEpisodes` | query | `dbc56e15e7c1254f11d499e8647343bbf28a5594e14a8f5c8b1e7eee67a55740` | bundle `xpui-routes-search.1472683a` |
| `searchFullEpisodes` | query | `397a4e93a21d140d088cfd26641133c5146a2f5f08e420654868153e720f5571` | bundle `xpui-routes-search.1472683a` |
| `searchGenres` | query | `9e1c0e056c46239dd1956ea915b988913c87c04ce3dadccdb537774490266f46` | bundle `xpui-routes-search.1472683a` |
| `searchModalEntityPage` | query | `25d73ce01f3011ff8b37f44c23ebbad18610db1a2cec2c84f20c1e822c748524` | bundle `xpui-root-dialogs.7582d917` |
| `searchModalResults` | query | `9a79751378a306d87d9789fa0c4a8f7c7213891c47a3993a0d0b58ff26514e59` | bundle `xpui-root-dialogs.7582d917` |
| `searchPlaylists` | query | `d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1` | bundle `xpui-routes-search.1472683a` + request |
| `searchPodcasts` | query | `0195d9f61b43606d490bca64c3456e3593528cea6cc05c7e822c7c42beed0f4e` | bundle `xpui-routes-search.1472683a` |
| `searchSuggestions` | - | `f244254b94c0e824d458ac216e0f8406a73f2f69a9ff52831a2f78fa774ff6ce` | request (main bundle) |
| `searchTopResultsList` | query | `dd78eaff943eba629ed70ee25517b9cea0dcaa41193e2592ae2727660b21892c` | bundle `xpui-routes-search.1472683a` + request |
| `searchTopResultsOnly` | query | `cb32d0ff697131db6d34f174f7d2fa931c6a8b6be22aabc8c6cc086da4a0f744` | bundle `xpui-routes-search.1472683a` |
| `searchTracks` | query | `b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751` | bundle `xpui-routes-search.1472683a` + request |
| `searchUsers` | query | `8f358dd82e62f61dd4ceaa9f8cd0889e644c9b707f1b724fbfb356a757cb7e5a` | bundle `xpui-routes-search.1472683a` |
| `showItemsPlayedState` | query | `4a070b9bfab2e8537a5271e6839bc3ef51501dcac8170b5fb69a98f967c5fb60` | bundle `xpui-root-dialogs.7582d917` |
| `similarAlbumsBasedOnThisTrack` | - | `1d1f93a737498adca2c892c73af87fc0b052afe4e1a33c989540c32413dfae17` | request (main bundle) |
| `thisisPlaylistExtension` | query | `906c2e14927400c8a71645c647576ce032f837a112d5e6f9375b19c80445c8cf` | bundle `xpui-routes-playlist.7090f328` |
| `trackPreview` | query | `fc26ffc7a1a4f93bd4c2d705649f7dba1de34005b3dc2915549847a9959405d8` | bundle `1675.fa3bc7da` |
| `userLocation` | query | `079939378ca79b67c6d047be9152ea940d21f10bbfa2f5d4cf4d8320d87774c2` | bundle `1272.990d4950` |
| `watchFeedEntity` | query | `d1a805f815ee0c20281330317d8c00c8fce9026686a86c38bc700ccaefb9ac5c` | bundle `dwp-watch-feed-view-container.7da8db88` |
| `watchFeedView` | query | `ce10864fccc53ca77c422143ee027bacf220f9d714e27ec592905334079ad743` | bundle `dwp-watch-feed-view-container.7da8db88` |

## 4. Dealer WebSocket

Connect: `wss://{dealer-host}/?access_token={token}` (host from apresolve
`dealer-g2`, e.g. `gew4-dealer.g2.spotify.com`; only query param is `access_token`).
Reconnect (new socket, new `{conn}`, re-register) with a fresh token when it expires; the
capture shows a new socket per page load, and the exact refresh behaviour was not isolated.

Frames are JSON text.

- Client -> server: only `{"type":"ping"}` every 30 s.
- Server -> client: `{"type":"pong"}` per ping.
- First server frame right after open: the connection id:

```json
{"type": "message", "method": "PUT",
 "uri": "hm://pusher/v1/connections/{conn, url-encoded base64}",
 "headers": {"Spotify-Connection-Id": "{conn}"}}
```

  `{conn}` goes into the `x-spotify-connection-id` header of
  `PUT connect-state/v1/devices/hobs_*` and into `connection_id` of the
  track-playback register body. No subscribe message is sent: the server pushes
  everything for the user once the device is registered.

- Push messages: `{"type":"message","uri":"hm://...","headers":{...},"payloads":[...]}`.
  `payloads` items are either JSON objects or strings (base64 protobuf, or plain JSON
  text). No message in the capture used `Transfer-Encoding: gzip`, but a client should
  gunzip when that header is present or bytes start with `1f 8b`.
- No `type:"request"` frames (with reply) were seen.

| uri pattern | headers | payload | carries |
|---|---|---|---|
| `hm://pusher/v1/connections/{conn}` | `Spotify-Connection-Id` | none (`method: PUT`) | connection id, once per socket |
| `hm://connect-state/v1/cluster` | `content-type: application/json` | JSON object: `cluster`, `update_reason`, `ack_id`, `devices_that_changed` | full cluster (`active_device_id`, `devices`, `player_state`, `timestamp`, `started_playing_at`, `not_playing_since_timestamp`...). Reasons seen: `DEVICE_STATE_CHANGED`, `DEVICE_VOLUME_CHANGED` |
| `hm://track-playback/v1/command` | `content-type: application/json` | JSON object, `type` = `replace_state` (`state_machine`, `state_ref`, `prev_state_ref`, `seek_to`, `selected_alias_id`, `context_metadata`, `registration_token`) or `set_volume` (`volume`, `command_id`, `registration_token`) | commands to the local player (play queue state machine) |
| `hm://collection/collection/{user}` | `Content-Type: application/octet-stream`, `MC-ETag`, `Collection-Source-Revision`, sometimes `Collection-Update-Id` | base64 protobuf | Liked Songs change |
| `hm://collection/collection/{user}/json` | same, `Content-Type: text/plain` | JSON string `{"items":[{"type":"track","unheard","addedAt","removed","identifier","itemData"?}]}` | same change, JSON form (easier to use) |
| `hm://collection/artist/{user}` and `.../json` | same as above | protobuf / JSON with `type:"artist"` | followed artists change |
| `hm://playlist/v2/playlist/{id}` | none | base64 protobuf: field 1 playlist URI, then revision bytes and ops | playlist content or attributes changed |
| `hm://playlist/v2/user/{user}/rootlist`, `hm://playlist/user/{user}/rootlist` | none | base64 protobuf: rootlist URI + revision | library playlist list changed (both forms pushed) |
| `hm://playlist/v2/list/liked-songs-artist/{artistId}` | none | base64 protobuf: `spotify:list:liked-songs-artist:{id}` | liked-songs-by-artist list changed |
| `hm://herodotus/uri/spotify:list:play-history:v1/resume-point-revision/{uuid}` | `Content-Type: application/octet-stream` | base64 protobuf: uuid, `spotify:list:play-history:v1`, track or ad URI | play history entry added (one per played track/ad) |

## 5. Connect state and device registration

Order on startup: token + client token -> apresolve -> dealer open (get `{conn}`) ->
register track-playback device -> PUT connect-state -> pathfinder calls.

### Register the player: `POST {spclient}/track-playback/v1/devices`

```json
{"device": {
   "brand": "spotify", "model": "web_player", "name": "Web Player (Chrome)",
   "device_type": "computer", "device_id": "{dev}",
   "platform_identifier": "web_player linux undefined;chrome 155.0.0.0;desktop",
   "is_group": false, "is_public": false, "metadata": {},
   "correlation_id": "<uuid>", "client_version": "harmony:4.81.1-1f3d48b7f",
   "capabilities": {"change_volume": true, "enable_play_token": true,
     "supports_file_media_type": true, "play_token_lost_behavior": "pause",
     "disable_connect": false, "audio_podcasts": true, "video_playback": true,
     "manifest_formats": ["file_ids_mp3", "file_urls_mp3", "manifest_urls_audio_ad",
       "manifest_ids_video", "file_urls_external", "file_ids_mp4", "file_ids_mp4_dual"],
     "supports_preferred_media_type": true, "supports_playback_offsets": true,
     "supports_playback_speed": true}},
 "connection_id": "{conn}", "client_version": "harmony:4.81.1-1f3d48b7f",
 "outro_endcontent_snooping": false, "volume": 65535}
```

`{dev}` is 40 hex chars, random per page load. Response: `initial_seq_num`,
`device_keep_alive_update_seconds` (21600), `endsongs`, `file_format_filter`,
`supports_observing`, `effective_license` (`tft` on free). Unregister:
`DELETE track-playback/v1/devices/{dev}` with body `seq_num`, `state_ref`, `sub_state`,
`previous_position`, `debug_source: "deregister"`.

### Join connect-state: `PUT {spclient}/connect-state/v1/devices/hobs_{dev35}`

Path id = `"hobs_" + first 35 chars of {dev}` (40 chars total). Header
`x-spotify-connection-id: {conn}`. Body:

```json
{"member_type": "CONNECT_STATE",
 "device": {"device_info": {"capabilities": {"can_be_player": false, "hidden": true,
   "needs_full_player_state": true}}}}
```

The hobs entry is a hidden observer; the real playable device is the track-playback
one. Response is the cluster JSON (same as GET below). Leave with
`DELETE connect-state/v1/devices/hobs_{dev35}` (204).

### `GET {spclient}/connect-state/v1/cluster`

Header `x-cache-hint`. Response: `timestamp`, `server_timestamp_ms`,
`active_device_id`, `devices` (map `{dev}` -> `can_play`, `volume` (0..65535), `name`,
`capabilities` (`can_be_player`, `is_controllable`, `volume_steps`, `supported_types`,
`command_acks`, `supports_*`...), `device_software_version`, `device_type`, `device_id`,
`client_id`, `brand`, `model`, `license`, `is_same_network`, `public_ip`),
`player_state`, `started_playing_at`, `need_full_player_state`,
`needs_state_updates`, `needs_local_devices`.

### Commands: `POST {spclient}/connect-state/v1/player/command/from/{dev}/to/{target}`

`from` = own device, `to` = target device (both own `{dev}` when playing locally).
Body `{"command": {"endpoint": "...", "logging_params": {"command_id": "<hex>", ...}, ...}}`.
Response `{"ack_id": "..."}`; the effect arrives as a dealer `track-playback/v1/command`
and a `connect-state/v1/cluster` push.

| endpoint | extra fields |
|---|---|
| `play` | `context` (`uri`, `url` = `context://{uri}`, `metadata`), `play_origin` (`feature_identifier`, `feature_version`, `referrer_identifier`), `options` (`license: "tft"`, `skip_to` {`track_index`/...}, `player_options_override`), `logging_params.page_instance_ids`, `interaction_ids` |
| `set_options` | `repeating_context`, `repeating_track` |
| `add_to_queue` | `track` (`uri`, `metadata.is_queued: "true"`, `provider: "queue"`) |

Volume: `PUT connect-state/v1/connect/volume/from/{dev}/to/{target}` body
`{"volume": 0..65535}` -> `{"ack_id"}`.

## Gaps

- Main entry bundle `web-player.{hash}.js` body not captured: 30 op hashes come only from
  requests, and their `query`/`mutation` kind and any never-called main-bundle ops are
  unknown. Re-capture it (or download it at refresh time) to complete the table.
- TOTP algorithm and secret behind `totp`/`totpVer` are not documented here (live in
  the page JS); without them `/api/token` needs a valid `sp_dc` cookie and fresh codes.
- Whether pathfinder or spclient accept a request without `client-token` was not tested.
- Error shapes: expired token (401), unknown persisted query, apresolve fallback were
  not observed.
- No gzip dealer payloads and no `type:"request"` dealer frames seen; Connect to
  another device (transfer, remote commands) not captured.
- Protobuf schemas of collection, playlist and herodotus pushes are only described by
  their visible fields.
- No connect-state command was seen for pause, resume, next or seek on the local device;
  those only showed `PUT track-playback/v1/devices/{dev}/state` reports (playback doc).
