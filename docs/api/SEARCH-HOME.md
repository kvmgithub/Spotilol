# Search, browse and home (Web Player, GraphQL)

Clean-room spec from captures `01-login`, `03-search`, `04-playlists` (free account,
Web Player `1.3.6.x`, locale `fr`, 2026-10-15). Ids below are examples or `{id}`;
no token, cookie or account value is reproduced.

## Transport

All ops: `POST https://api-partner.spotify.com/pathfinder/v2/query`, body:

```json
{"variables":{...},"operationName":"searchTracks",
 "extensions":{"persistedQuery":{"version":1,"sha256Hash":"<hash>"}}}
```

| Header | Holds |
|---|---|
| `authorization` | `Bearer {web access token}` (from `open.spotify.com/api/token`) |
| `client-token` | token from `clienttoken.spotify.com/v1/clienttoken` |
| `app-platform` | `WebPlayer` |
| `spotify-app-version` | web player build, e.g. `1.3.6.1858.g{hash}` |
| `content-type` | `application/json;charset=UTF-8` |
| `accept` | `application/json` |
| `accept-language` | UI locale (`fr`); drives every label (titles, greeting, chips) |
| `origin` / `referer` | `https://open.spotify.com` / `https://open.spotify.com/` |

Response: `{"data":{...},"extensions":{"requestIds":{...}}}`. Every entity comes as a
`*ResponseWrapper {__typename, data:{__typename, ...}}`; `data.__typename` may be
`NotFound` (seen in home) or a restricted type, so always switch on it.

## Operation table

| operationName | sha256Hash | Trigger |
|---|---|---|
| `recentSearches` | `ece96d7b82c820fb9938dd1df50dee3e53737d808c3883e3d01c467c423601b0` | open search page |
| `searchSuggestions` | `f244254b94c0e824d458ac216e0f8406a73f2f69a9ff52831a2f78fa774ff6ce` | each keystroke (debounced) |
| `searchTopResultsList` | `dd78eaff943eba629ed70ee25517b9cea0dcaa41193e2592ae2727660b21892c` | submit, "All" tab |
| `searchTracks` | `b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751` | Songs tab |
| `searchArtists` | `7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d` | Artists tab |
| `searchAlbums` | `202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9` | Albums tab |
| `searchPlaylists` | `d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1` | Playlists tab |
| `browseAll` | `dbd8b55e09a58afc52eab438bc228ba28fd72ac2f2148c6c26354980e4579001` | search page, empty query |
| `home` | `5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1` | home page |
| `feedBaselineLookup` | `318190494c436b51ab995367bffc66dcdb49dfa023792c60ac15dcb6dfbec42f` | home, after/with `home` |
| `lookupChildEntities` | `91ce02e32b19123de231dc8de91fe4b9ab84eca087d4c015549308d77fbb6d10` | home "Recents" child art |
| `fetchEntitiesForRecentlyPlayed` | `cf5d2e94ffd82788470788ae1f6090cc3e9e774fb8fd383580634c6e6f50f7be` | app boot (restored player context) |
| `decorateContextTracks` | `383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7` | app boot (queue of restored context) |
| *(none)* `pathfinder/v1/query` | `f952da037440f694cc6925b9e3f649d39077a744c4db7dfba01cb883723f4f77` | app boot, alongside the above |

Not captured: podcast/episode/user/genre/audiobook search tabs, genre/category page,
home section paging, home chip filter. See Gaps.

## Search

UI routes: `/search` -> `/search/{q}` -> `/search/{q}/tracks|artists|albums|playlists`.

### Variables

| Op | Variables (observed) |
|---|---|
| `recentSearches` | `limit:Int=50, includeAuthors:Bool=true, includeEpisodeContentRatingsV2:Bool=true` |
| `searchSuggestions` | `query:String, limit:30, numberOfTopResults:30, offset:0, includeAuthors:true, includeAlbumPreReleases:false, includeEpisodeContentRatingsV2:true` |
| `searchTopResultsList` | `query:String, limit:50, offset:0, numberOfTopResults:50, includeArtistHasConcertsField:false, includeAudiobooks:true, includeAuthors:true, includePreReleases:true, includeAlbumPreReleases:false, includeEpisodeContentRatingsV2:true, isPrefix:null, sectionFilters:["GENERIC","VIDEO_CONTENT"]` |
| `searchTracks` | `searchTerm:String, offset:0, limit:20, numberOfTopResults:20, includeAudiobooks:true, includeAuthors:true, includePreReleases:false, includeAlbumPreReleases:false, includeEpisodeContentRatingsV2:true` |
| `searchArtists` / `searchAlbums` / `searchPlaylists` | same as `searchTracks` but `limit:30` |

Note: the name is `query` for suggestions/top results and `searchTerm` for the tab ops.

### Response paths

| Op | Root | Items |
|---|---|---|
| `recentSearches` | `data.recentSearches.recentSearchesItems.items[]` (`RecentSearchesPage`) | wrappers directly (`ArtistResponseWrapper`, ...) |
| `searchSuggestions` | `data.searchV2.topResultsV2.itemsV2[]` (`TopResultHit`) | `.item` = wrapper |
| `searchTopResultsList` | `data.searchV2.topResultsV2.itemsV2[]` | `.item` = wrapper or `SearchSectionEntity`; `.matchedFields[]` |
| `searchTracks` | `data.searchV2.tracksV2` | `items[].item` = `TrackResponseWrapper` (extra `item` level!) |
| `searchArtists` | `data.searchV2.artists` | `items[]` = `ArtistResponseWrapper` |
| `searchAlbums` | `data.searchV2.albumsV2` | `items[]` = `AlbumResponseWrapper` |
| `searchPlaylists` | `data.searchV2.playlists` | `items[]` = `PlaylistResponseWrapper` |

Tab paging: `{totalCount, pagingInfo:{limit, nextOffset}}` beside `items`; request the
next page with `offset = nextOffset` (presumably null on the last page).

`searchSuggestions` item types: `SearchAutoCompleteEntity` (`item.text` directly on the item, no `data` level; `uri =
spotify:search:{text}`; the UI shows these first as query completions),
`ArtistResponseWrapper`, `TrackResponseWrapper`, `PlaylistResponseWrapper`,
`EpisodeResponseWrapper` (also `AlbumResponseWrapper` in top results).

`searchTopResultsList` extras:
- `searchV2.{tracksV2,artists,albumsV2,playlists,episodes,podcasts,audiobooks,users,genres,authors}.totalCount` (counts only, no items).
- `searchV2.chipOrder.items[].typeName`: tab order, e.g. `TRACKS, VIDEOS, ARTISTS, EPISODES, PLAYLISTS, ALBUMS, USERS, PODCASTS, AUDIOBOOKS, GENRES`.
- The first hit is the "top result" card (here the artist). Hits interleave
  `SearchSectionEntity` shelves: `data:{title.transformedLabel, sectionType:"GENERIC"|"VIDEO_CONTENT", attributionUri, items:[wrappers]}` (e.g. "With {artist}" playlists, "Videos" tracks).

### Entity fields the UI needs (inside `wrapper.data`)

| Type | Fields |
|---|---|
| `Track` | `uri, id, name, duration.totalMilliseconds, contentRating.label (EXPLICIT/NONE), playability.{playable,reason}, trackMediaType (AUDIO), artists.items[].{uri,profile.name}, albumOfTrack.{uri,id,name,coverArt.sources[]}, associationsV3.videoAssociations.totalCount` |
| `Artist` | `uri, profile.name, visuals.avatarImage.sources[]` (may be null), `visuals.avatarImage.extractedColors.colorDark.hex` |
| `Album` | `uri, name, type (ALBUM/SINGLE/EP...), date.year, artists.items[], coverArt.sources[], playability, isAlbumPreRelease, preReleaseEndDateTime` |
| `Playlist` | `uri, name, description (may hold <a href=spotify:...> HTML), format, images.items[0].sources[], ownerV2.data.{uri,name,username,avatar}, attributes[{key,value}]` |
| `Episode` | `uri, name, description, duration, releaseDate.isoString, coverArt, podcastV2.data.{uri,name,publisher.name}, playedState, restrictions.paywallContent` |

Images: `sources[] {url, width, height}`; album covers come as 300/64/640, artists
640/160/320 (order not sorted, pick by size). Colors: `extractedColors.colorDark.{hex,isFallback}`;
`visualIdentity.squareCoverImage.extractedColorSet` holds richer RGBA palettes (ignorable).

```json
{"data":{"searchV2":{"query":"{q}","tracksV2":{
  "items":[{"matchedFields":[],"item":{"__typename":"TrackResponseWrapper","data":{
    "__typename":"Track","uri":"spotify:track:{id}","id":"{id}","name":"Song",
    "duration":{"totalMilliseconds":204664},"contentRating":{"label":"EXPLICIT"},
    "playability":{"playable":true,"reason":"PLAYABLE"},"trackMediaType":"AUDIO",
    "artists":{"items":[{"uri":"spotify:artist:{id}","profile":{"name":"Artist"}}]},
    "albumOfTrack":{"uri":"spotify:album:{id}","id":"{id}","name":"Album",
      "coverArt":{"extractedColors":{"colorDark":{"hex":"#305868","isFallback":false}},
        "sources":[{"url":"https://i.scdn.co/image/{hex}","width":300,"height":300}]}}}}}],
  "pagingInfo":{"limit":20,"nextOffset":20},"totalCount":429}}}}
```

## Browse (`browseAll`)

Variables: `pagePagination:{offset:0,limit:10}, sectionPagination:{offset:0,limit:99},
browseEndUserIntegration:"INTEGRATION_WEB_PLAYER"`.

Root `data.browseStart` (`BrowseSectionContainer`, `uri spotify:page:{id}`):
`sections.items[] {uri: spotify:section:{id}, data:{__typename, title.transformedLabel}, sectionItems.items[]}`.
No paging info was returned.

- Section `BrowseUnsupportedSectionData` holds a display ad slot (`spotify:promotion:...`, content `UnknownType`): skip.
- Section `BrowseGridSectionData` "Browse all": 70 cards.
  - `content.__typename = BrowseSectionContainerWrapper` (uri `spotify:page:{id}`, a category/genre page):
    `content.data.data.cardRepresentation.{title.transformedLabel, backgroundColor.hex, artwork.sources[]}`.
  - `content.__typename = BrowseXlinkResponseWrapper` (uri `spotify:xlink:{id}`, data `BrowseClientFeature`, e.g. live events):
    `content.data.{title.transformedLabel, backgroundColor.hex, artwork.sources[], iconOverlay.sources[], featureUri}`.

```json
{"uri":"spotify:page:{id}","content":{"__typename":"BrowseSectionContainerWrapper",
 "data":{"__typename":"BrowseSectionContainer","data":{"cardRepresentation":{
  "title":{"transformedLabel":"Music"},"backgroundColor":{"hex":"#dc148c"},
  "artwork":{"sources":[{"url":"https://i.scdn.co/image/{hex}","width":300,"height":300}]}}}}}}
```

## Home

### `home`

Variables: `timeZone:String` (IANA, e.g. `Europe/Zurich`), `sp_t:String` (value of the
`sp_t` cookie set on `.spotify.com` by the first accounts/open page; a per-browser id,
placeholder `{sp_t}`), `facet:""` (chip filter, empty = all), `sectionItemsLimit:10`,
`homeEndUserIntegration:"INTEGRATION_WEB_PLAYER"`, `includeHomeChapterVideoCards:false`,
`includeEpisodeContentRatingsV2:true`.

Root `data.home` (`HomeResponsePayload`):
- `greeting.transformedLabel` ("Good evening").
- `homeChips[] {id, label.transformedLabel, subChips[{id,label}]}`: ids `music-chip`,
  `podcasts-chip`, `audiobooks-chip`; sub `*-following-chip`. Presumably passed as `facet`.
- `sectionContainer.sections {totalCount, items[]}` (31 sections). Each section:
  `{uri: spotify:section:{id}, data:{__typename, title.transformedLabel, subtitle?, headerEntity?, iconName?},
  sectionItems:{items[], totalCount, pagingInfo.nextOffset}}`; each item:
  `{uri, data (HomeRecsItemData|null), content: wrapper}`.

| Section `data.__typename` | Seen titles | Items |
|---|---|---|
| `HomeShortsSectionData` | (none; top quick-access grid) | 1 `Playlist` |
| `HomeGenericSectionData` | "Recommended radios", "Your favourite mixes", "Your favourite artists", "For fans of {artist}", new audiobooks | up to 10 of `Playlist`, `Artist`, `Audiobook` (`PodcastOrAudiobookResponseWrapper`); `totalCount` larger, `nextOffset` 10 |
| `HomeRecentlyPlayedSectionData` | "Recents" | 1 `ListResponseWrapper/List` uri `spotify:list:recents:main` |
| `HomeFeedBaselineSectionData` | "Made for you", "Based on recent listening", "Videos you might like" | 1 `Playlist` or `EpisodeOrChapterResponseWrapper/Episode` (vertical feed cards) |

`headerEntity` is `ArtistResponseWrapper` (artist avatar + name before the title) or `UnknownType`.
Playlist cards: `name, description, format, images.items[0].sources[0].url, ownerV2.data.name`.
Artist cards: `profile.name, visuals.avatarImage`. Audiobook: `name, authorsV2[].name, coverArt`.

Recents list: `content.data.items.items[] {uid, entity, formatListAttributes[{key,value}]}`;
`entity` = `EntityResponseWrapper {_uri, data:{entityTypeTrait.type (ENTITY_TYPE_ARTIST|ENTITY_TYPE_PLAYLIST...),
identityTrait.{name,type,description,contributors}, visualIdentityTrait.squareCoverImage, typedEntity (wrapper or UnknownType), sharingInfo}}`.
`spotify:collection:tracks` (Liked Songs) appears here; its `group_metadata` attribute
(base64 protobuf) names a child playlist uri, which is then fetched with `lookupChildEntities`.

```json
{"data":{"home":{"__typename":"HomeResponsePayload","greeting":{"transformedLabel":"Good evening"},
 "homeChips":[{"id":"music-chip","label":{"transformedLabel":"Music"},"subChips":[{"id":"music-following-chip"}]}],
 "sectionContainer":{"uri":"spotify:page:{id}","sections":{"totalCount":31,"items":[
  {"uri":"spotify:section:{id}",
   "data":{"__typename":"HomeGenericSectionData","title":{"transformedLabel":"Recommended radios"},
           "subtitle":{"transformedLabel":"..."},"headerEntity":{"__typename":"UnknownType"}},
   "sectionItems":{"totalCount":15,"pagingInfo":{"nextOffset":10},"items":[
    {"uri":"spotify:playlist:{id}","data":{"__typename":"HomeRecsItemData"},
     "content":{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist",
       "uri":"spotify:playlist:{id}","name":"Radio X","format":"inspiredby-mix",
       "images":{"items":[{"sources":[{"url":"https://pickasso.spotifycdn.com/image/{...}"}]}]},
       "ownerV2":{"data":{"__typename":"User","name":"Spotify","uri":"spotify:user:spotify"}}}}}]}}]}}}}}
```

### `feedBaselineLookup`

Variables `uris:[String]`: the content uris of all `HomeFeedBaselineSectionData` items (19, playlists and episodes).
Response `data.lookup[]` wrappers (`_uri` + `data`) with preview media for the vertical feed:
- Playlist: `data.previewItems.items[]` = `TrackResponseWrapper` with `name, uri, albumOfTrack.coverArt,
  previews.audioPreviews.items[].url` (mp3 preview on `p.scdn.co`), `canvas.{url,type,thumbnail}` (may be null).
- Episode: `data.previewPlayback.{audioPreview.cdnUrl, videoPreview.{fileId, offset, placeholderImage}}`.
Optional for a client that does not show previews.

### `lookupChildEntities`

Variables `uris:["spotify:playlist:{id}"]`. Response `data.lookupEntities[] {__typename:"Entity", uri,
visualIdentityTrait.squareCoverImage.{image.data.sources[].url, originalInstances[].flatFile.cdnUrl}}`
(Liked Songs art `misc.scdn.co/liked-songs/liked-songs-{300|640}.png`).

## Boot-time player decoration

Fired on every app load (also after navigating to `/search` with reload) once the
restored player state is known (from Connect cluster, see CAPTURE-MAP).

| Call | Variables | Response |
|---|---|---|
| `decorateContextTracks` | `uris:[50 x spotify:track]` (queue of current context) | `data.tracks[] {__typename:"Track", uri, name, duration.totalMilliseconds, contentRating.label, artists.items[].{uri,profile.name}, albumOfTrack.{uri,name,coverArt.sources[]}, associationsV3}` |
| `fetchEntitiesForRecentlyPlayed` | `uris:[1 context uri]` (current/last playlist) | `data.lookup[]` wrapper: `Playlist {uri,name,description,format,images,ownerV2.data.{name,uri,username}, attributes[]}` |
| `pathfinder/v1/query` | `uris:[3..5 spotify:track]` (current track and its neighbours) | `data.lookupEntities[]` (see below) |

`pathfinder/v1/query` is an unnamed persisted query (no `operationName` in the body)
sent by a different client module: headers lack `app-platform` and `spotify-app-version`,
`accept: */*`, `content-type: application/json`; still `authorization` + `client-token`.
Body `{"variables":{"uris":[...]},"extensions":{"persistedQuery":{"version":1,"sha256Hash":"f952da03..."}}}`.
It is a generic entity lookup (likely now-playing / media-session metadata):

```json
{"data":{"lookupEntities":[{"__typename":"Entity","uri":"spotify:track:{id}",
 "entityTypeTrait":{"type":"ENTITY_TYPE_TRACK"},
 "consumptionExperienceTrait":{"duration":{"seconds":215,"nanoSeconds":0}},
 "identityTrait":{"name":"Song","contentHierarchyParent":{"uri":"spotify:album:{id}"}},
 "typedEntity":{"data":{"__typename":"Track","uri":"spotify:track:{id}","name":"Song",
   "albumOfTrack":{"uri":"spotify:album:{id}","name":"Album","coverArt":{"sources":[...]}},
   "artists":{"items":[{"uri":"spotify:artist:{id}","profile":{"name":"Artist"}}]},
   "duration":{"totalMilliseconds":215813},"playability":{"playable":true},
   "relinkingInformation":null}}}]}}
```

## Gaps

| Unknown | Capture that answers it |
|---|---|
| Search ops for podcasts, episodes, users, genres, audiobooks (names, hashes) | 03-search: click those chips on `/search/{q}` |
| Genre/category page op (`spotify:page:{id}` from browse cards) and its section layout | open a browse card (e.g. Pop) |
| Home section "Show all" / paging op (`nextOffset` use) | click a home section title |
| `home` with `facet` set (chip value format) | click the Music / Podcasts chip on home |
| Second page of a search tab (`nextOffset` null at end?) | scroll a tab to the end |
| Adding/removing/clearing recent searches (mutations) | click a result, then remove/clear recents |
| `searchSuggestions` with `offset>0`; `isPrefix` non-null | unknown trigger; type then scroll the dropdown |
| Error shapes (expired token, unknown hash -> `PersistedQueryNotFound`?) | replay with stale token / bad hash |
| Exact source of `fetchEntitiesForRecentlyPlayed` / v1 uris (cluster vs local storage) | boot capture with dealer frames correlated |
