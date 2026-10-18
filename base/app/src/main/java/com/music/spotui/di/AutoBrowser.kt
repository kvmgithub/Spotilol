package com.music.spotui.di

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import app.myspoti.engine.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ContextPageModel
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Auto / media browser tree, built from the app's own data (not the web page):
 * Liked Songs, playlists, albums, followed artists, and search. Tapping a track plays it
 * in its context, the same way the album / playlist screens do.
 *
 * Media ids: "liked", "playlists", "albums", "artists" (lists); "pl:<id>", "al:<id>",
 * "ar:<id>" (contexts); "t|<context uri>|<play key>" (a track to play).
 */
object AutoBrowser {
    const val ROOT = "root"
    private const val TIMEOUT_MS = 15_000L

    // Contexts recently listed, so a tap plays with the right queue.
    private val pages = java.util.concurrent.ConcurrentHashMap<String, ContextPageModel>()

    suspend fun children(context: Context, parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browsable("liked", context.getString(R.string.collection_liked_songs_title), null, grid = false),
            browsable("playlists", context.getString(R.string.aa_playlists), null, grid = false),
            browsable("albums", context.getString(R.string.aa_albums), null, grid = false),
            browsable("artists", context.getString(R.string.aa_artists), null, grid = false),
        )
        parentId == "playlists" || parentId == "albums" -> {
            val wantPlaylists = parentId == "playlists"
            val entries = (firstResult { Graph.api.getLibrary() } ?: emptyList())
                .filter { it.isPlaylist == wantPlaylists && it.spotifyId != com.music.spotui.data.api.Api.HomeCache.LIKED_SONGS_ID }
            entries.map { e ->
                browsable(if (wantPlaylists) "pl:${e.spotifyId}" else "al:${e.spotifyId}", e.name, e.coverUri, subtitle = e.subtitle)
            }
        }
        parentId == "artists" -> Graph.api.getFollowedArtists().filter { it.id.isNotBlank() }.map { a ->
            browsable("ar:${a.id}|${a.name}", a.name, a.coverUri)
        }
        parentId == "liked" -> tracksOf(firstResult { Graph.api.getLikedPage() })
        parentId.startsWith("pl:") -> tracksOf(firstResult { Graph.api.getPlaylistPage(parentId.removePrefix("pl:")) })
        parentId.startsWith("al:") -> tracksOf(firstResult { Graph.api.getAlbumPage(parentId.removePrefix("al:")) })
        parentId.startsWith("ar:") -> {
            val (id, name) = parentId.removePrefix("ar:").split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val overview = firstResult { Graph.api.getArtistOverview(name, id) }
            overview?.topTracks?.map { playable("spotify:artist:$id", it.song) }.orEmpty()
        }
        else -> emptyList()
    }

    suspend fun search(query: String): List<MediaItem> {
        val r = firstResult { Graph.api.searchEverything(query) } ?: return emptyList()
        return r.songs.take(20).map { playable("", it) } +
            r.albums.filter { it.spotifyId.isNotBlank() }.take(8).map { browsable("al:${it.spotifyId}", it.name, it.coverUri, subtitle = it.artists) }
    }

    /** Voice search ("play X on Myspoti"): the best matching track. */
    suspend fun playFromSearch(query: String): Boolean {
        val song = (firstResult { Graph.api.searchEverything(query) })?.songs?.firstOrNull() ?: return false
        play("t||${song.url}")
        return true
    }

    fun play(mediaId: String) {
        val parts = mediaId.split("|", limit = 3)
        if (parts.size < 3 || parts[0] != "t") return
        val contextUri = parts[1].ifBlank { null }
        val key = parts[2]
        val page = contextUri?.let { pages[it] }
        val song = page?.tracks?.firstOrNull { it.url == key }
        if (page != null) {
            Graph.currentSongState.updateQueue(page.tracks)
            if (page.type == "liked") SongPlayer.registerLikedSongs(page.tracks.map { it.url })
        }
        SongPlayer.playSong(key, contextUri = contextUri)
        song?.let { Graph.currentSongState.updateSongState(it.coverUri, it.title, it.singer, true, it.id, 0, it.album) }
    }

    private fun tracksOf(page: ContextPageModel?): List<MediaItem> {
        page ?: return emptyList()
        pages[page.uri] = page
        return page.tracks.map { playable(page.uri, it) }
    }

    private suspend fun <T> firstResult(load: suspend () -> kotlinx.coroutines.flow.Flow<Response<T>>): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            (load().first { it !is Response.Loading } as? Response.Success)?.data
        }

    private fun browsable(id: String, title: String, cover: String?, subtitle: String = "", grid: Boolean = true): MediaItem {
        val extras = Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", if (grid) 2 else 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        }
        val d = MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
            .setIconUri(ArtworkProvider.uriFor(cover)).setExtras(extras).build()
        return MediaItem(d, MediaItem.FLAG_BROWSABLE)
    }

    private fun playable(contextUri: String, s: SongsModel): MediaItem {
        val d = MediaDescriptionCompat.Builder().setMediaId("t|$contextUri|${s.url}")
            .setTitle(s.title).setSubtitle(s.singer).setIconUri(ArtworkProvider.uriFor(s.coverUri)).build()
        return MediaItem(d, MediaItem.FLAG_PLAYABLE)
    }
}

/**
 * Cover art for Android Auto, which only shows local (content://) artwork: serves an
 * image from Spotify's image CDN through Glide's disk cache. Other hosts are refused.
 */
class ArtworkProvider : android.content.ContentProvider() {
    companion object {
        private val AUTHORITY = app.myspoti.engine.BuildConfig.APPLICATION_ID + ".artwork"
        private val allowedHosts = setOf("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com", "thisis-images.spotifycdn.com", "seeded-session-images.scdn.co", "pickasso.spotifycdn.com")

        fun uriFor(url: String?): Uri? {
            if (url.isNullOrBlank() || !allowed(url)) return null
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("img").appendQueryParameter("u", url).build()
        }

        /**
         * Only https URLs on the CDN hosts: a `file:` URL whose authority happens to be
         * an allowed host (`file://i.scdn.co/data/data/...`) must never reach Glide,
         * which would open the local path and hand this app's private files to any caller
         * (the provider is exported, for Android Auto).
         */
        private fun allowed(url: String): Boolean {
            val u = Uri.parse(url)
            return u.scheme == "https" && u.host in allowedHosts
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val url = uri.getQueryParameter("u") ?: return null
        if (!allowed(url)) return null
        // Bounded: a binder thread must not wait on the network forever.
        val file = runCatching {
            com.bumptech.glide.Glide.with(context!!).asFile().load(url).submit()
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrNull() ?: return null
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(uri: Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
}
