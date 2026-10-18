package app.myspoti.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Entry point the UI talks to. One instance per signed-in account. */
interface MyspotiApi {
    val player: Player
    val search: Search
    val catalog: Catalog
    val library: Library
    val playlists: Playlists
    val home: Home
    val podcasts: Podcasts
    val users: Users
    val extras: TrackExtras
    val devices: Devices
    val jam: Jam

    /** False until the playback engine is ready to take commands. */
    val ready: StateFlow<Boolean>
}

interface Player {
    /** Latest state, null when no playback session exists yet. */
    val state: StateFlow<PlayerState?>
    val queue: StateFlow<Queue?>

    /** Plays [contextUri] (playlist, album, artist, show, collection...), starting at [trackUri] or [index] if given. */
    /**
     * Plays [contextUri] from [trackUri] (or [index]). With [order], the context's songs play
     * in that order instead of the context's own (a sorted playlist).
     */
    suspend fun play(contextUri: Uri, trackUri: Uri? = null, index: Int? = null, positionMs: Long? = null, order: List<Uri>? = null)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun next()
    suspend fun previous()
    suspend fun setShuffle(on: Boolean)
    suspend fun setRepeat(mode: Repeat)
    suspend fun addToQueue(uris: List<Uri>)
    suspend fun removeFromQueue(items: List<NowPlaying>)
    suspend fun clearQueue()
    /** Jumps to an item of [Queue.queued] or [Queue.nextUp]. */
    suspend fun skipTo(item: NowPlaying)
    /** "Play next": before everything already queued. */
    suspend fun playNext(uris: List<Uri>)
    /** Moves [item] just before or after [target], in the same part of the queue. */
    suspend fun moveInQueue(item: NowPlaying, target: NowPlaying, after: Boolean)

    /** Volume from 0.0 to 1.0. */
    suspend fun volume(): Double
    suspend fun setVolume(level: Double)
    /** Playback speed (podcasts): 0.5 to 3.5. */
    suspend fun setSpeed(speed: Double)
}

interface Search {
    /** Top results of every music kind at once, for the search screen's first view. */
    suspend fun all(query: String, limit: Int = 10): SearchResults
    suspend fun tracks(query: String, offset: Int = 0, limit: Int = 30): Page<Track>
    suspend fun artists(query: String, offset: Int = 0, limit: Int = 30): Page<Artist>
    suspend fun albums(query: String, offset: Int = 0, limit: Int = 30): Page<Album>
    suspend fun playlists(query: String, offset: Int = 0, limit: Int = 30): Page<Playlist>
    suspend fun shows(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun episodes(query: String, offset: Int = 0, limit: Int = 30): Page<Episode>
    suspend fun audiobooks(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun users(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    suspend fun genres(query: String, offset: Int = 0, limit: Int = 30): Page<MediaCard>
    /** Completions while typing. */
    suspend fun suggestions(query: String, limit: Int = 10): List<String>
    suspend fun recent(): List<MediaCard>
    suspend fun clearRecent()
}

interface Catalog {
    suspend fun track(uri: Uri): Track
    suspend fun album(uri: Uri): Album
    suspend fun albumTracks(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Track>
    suspend fun artist(uri: Uri): Artist
    suspend fun artistTopTracks(uri: Uri): List<Track>
    suspend fun artistPage(uri: Uri): ArtistPage
    /** The recording's ISRC (to match the same recording on other platforms). */
    suspend fun isrc(trackUri: Uri): String?
    /** Albums, singles and compilations, newest first. */
    suspend fun discography(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Album>
    suspend fun relatedArtists(uri: Uri): List<Artist>
    suspend fun appearsOn(uri: Uri): List<Album>
    /** "More like this" albums for a track. */
    suspend fun similarAlbums(trackUri: Uri, limit: Int = 20): List<Album>
}

interface Library {
    /** Whether each uri (track, album, artist, show, playlist) is saved, in the same order. */
    suspend fun contains(uris: List<Uri>): List<Boolean>
    suspend fun add(uris: List<Uri>)
    suspend fun remove(uris: List<Uri>)

    /** The "Your Library" list, as the Web Player shows it. [folderUri] opens a folder. */
    suspend fun items(
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.RECENTS,
        folderUri: Uri? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Page<LibraryItem>

    suspend fun likedTracks(offset: Int = 0, limit: Int = 50): Page<SavedTrack>
    suspend fun pin(uri: Uri)
    suspend fun unpin(uri: Uri)

    suspend fun createFolder(name: String, parentFolderUri: Uri? = null): Uri
    suspend fun renameFolder(folderUri: Uri, name: String)
    suspend fun deleteFolder(folderUri: Uri)
    /** Moves a playlist or folder into [folderUri] (null = top level). */
    suspend fun moveToFolder(uri: Uri, folderUri: Uri?)

    /** Pushed when the library changes, from this device or another one. */
    val changes: Flow<Unit>
}

interface Playlists {
    suspend fun playlist(uri: Uri): Playlist
    suspend fun tracks(uri: Uri, offset: Int = 0, limit: Int = 100): Page<Track>
    /** Every row, sorted on the client (the server has no sort). Use [sortedFor] to sort rows already loaded. */
    suspend fun sortedTracks(uri: Uri, sort: PlaylistSort = PlaylistSort.CUSTOM, descending: Boolean = false): List<Track>
    /** The user's own playlists and the ones they follow, in library order, folders flattened. */
    suspend fun mine(): List<Playlist>
    /** Playlists the user may add tracks to. */
    suspend fun editable(): List<Playlist>

    /** A new playlist at the top of the library, or at the top of [folderUri]. */
    suspend fun create(name: String, folderUri: Uri? = null): Uri
    suspend fun rename(uri: Uri, name: String)
    suspend fun setDescription(uri: Uri, description: String)
    /** Who may see or change the playlist (owner only). */
    suspend fun access(uri: Uri): PlaylistAccess
    suspend fun setAccess(uri: Uri, access: PlaylistAccess)
    /** Public playlists show on the profile and in search; private ones only to the owner. */
    suspend fun setPublic(uri: Uri, public: Boolean) = setAccess(uri, if (public) PlaylistAccess.PUBLIC else PlaylistAccess.PRIVATE)
    /** Collaborative playlists let other users add and remove tracks (owner only). */
    suspend fun setCollaborative(uri: Uri, collaborative: Boolean) = setAccess(uri, if (collaborative) PlaylistAccess.COLLABORATIVE else PlaylistAccess.PUBLIC)
    /** A new cover (JPEG bytes), or null to go back to the generated one. */
    suspend fun setCover(uri: Uri, jpeg: ByteArray?)
    /** Removes the playlist from the library (owners: deletes it; it can be restored for 90 days). */
    suspend fun delete(uri: Uri)
    suspend fun follow(uri: Uri)
    suspend fun unfollow(uri: Uri)

    suspend fun addTracks(uri: Uri, trackUris: List<Uri>)
    /** Rows are addressed by [Track.uid]. */
    suspend fun removeTracks(uri: Uri, uids: List<String>)
    suspend fun moveTracks(uri: Uri, uids: List<String>, beforeUid: String?)
    /** Tracks Spotify suggests adding at the bottom of the playlist. */
    suspend fun recommended(uri: Uri, limit: Int = 10): List<Track>
}

interface Home {
    suspend fun feed(filter: HomeFilter = HomeFilter.ALL, itemsPerShelf: Int = 10): HomeFeed
    suspend fun categories(): List<Category>
    suspend fun category(uri: Uri): BrowsePage
    /** New releases from the artists the user follows, newest first ("What's new"). */
    suspend fun whatsNew(offset: Int = 0, limit: Int = 50): Page<Album>
}

interface Podcasts {
    suspend fun show(uri: Uri): Show
    suspend fun episodes(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Episode>
    suspend fun episode(uri: Uri): Episode
    suspend fun audiobook(uri: Uri): Audiobook
    suspend fun chapters(uri: Uri, offset: Int = 0, limit: Int = 50): Page<Episode>
    /** Episodes the user saved ("Your Episodes"). */
    suspend fun savedEpisodes(offset: Int = 0, limit: Int = 50): Page<Episode>
    suspend fun setEpisodeSaved(uri: Uri, saved: Boolean)
}

interface Users {
    suspend fun me(): Account
    suspend fun profile(userUri: Uri): UserProfile
    /** People following this user. */
    suspend fun followers(userUri: Uri): List<UserRef>
    /** People this user follows. */
    suspend fun following(userUri: Uri): List<UserRef>
    suspend fun top(range: TimeRange = TimeRange.SHORT_TERM, limit: Int = 10): TopContent
    suspend fun follow(userUris: List<Uri>)
    suspend fun unfollow(userUris: List<Uri>)
    suspend fun isFollowing(userUris: List<Uri>): List<Boolean>
    suspend fun friendActivity(): List<FriendActivity>
    /** Contexts played lately (Recents). */
    suspend fun history(): List<HistoryEntry>
    suspend fun setPrivateSession(on: Boolean)
}

interface TrackExtras {
    suspend fun lyrics(trackUri: Uri): Lyrics?
    suspend fun canvas(trackUri: Uri): Canvas?
    suspend fun credits(trackUri: Uri): Credits
    /** The track's radio, as a playlist uri to play. Works with artist uris too. */
    suspend fun radio(seedUri: Uri): Uri?
    /** Hide this track from recommendations, or show it again. */
    suspend fun setExcludedFromTaste(trackUri: Uri, excluded: Boolean)
    suspend fun isExcludedFromTaste(trackUris: List<Uri>): List<Boolean>
    /** "Don't play this artist": the artist is kept out of recommendations and radios. */
    suspend fun setArtistBanned(artistUri: Uri, banned: Boolean)
    suspend fun isArtistBanned(artistUris: List<Uri>): List<Boolean>
}

interface Devices {
    /** Spotify Connect devices of the account, this app's player included. */
    val devices: StateFlow<List<Device>>
    suspend fun refresh()
    suspend fun transfer(deviceId: String, play: Boolean = true)
    /** Brings playback back to this app. */
    suspend fun pullHere()
}

interface Jam {
    suspend fun availability(): JamAvailability
    val session: StateFlow<JamSession?>
    suspend fun refresh()
    suspend fun start(): JamSession
    suspend fun join(joinToken: String): JamSession
    suspend fun leave()
    suspend fun end()
    suspend fun inviteLink(): String?
    suspend fun removeMember(userUri: Uri)
    suspend fun setQueueOnly(on: Boolean)
    suspend fun setParticipantVolumeControl(on: Boolean)
}
