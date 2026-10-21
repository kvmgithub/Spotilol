package com.music.spotui.data.api

import app.myspoti.api.LibraryFilter
import app.myspoti.api.MediaKind
import app.myspoti.api.best
import app.myspoti.host.android.MyspotiRuntime

/**
 * A playlist the user can add tracks to, for the "Add to playlist" pickers. With a
 * [folderUri] the row is a library folder instead, opened in place.
 */
data class EditablePlaylist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val imageUrl: String?,
    val folderUri: String? = null,
) {
    val isFolder: Boolean get() = folderUri != null
}

object EditablePlaylists {
    /** Ids of the playlists this account can change, read at most every [IDS_TTL_MS]. */
    @Volatile private var ids: Set<String>? = null
    @Volatile private var idsAt = 0L
    private const val IDS_TTL_MS = 5 * 60_000L

    suspend fun ids(): Set<String> {
        val now = System.currentTimeMillis()
        ids?.takeIf { now - idsAt < IDS_TTL_MS }?.let { return it }
        return runCatching { load().mapTo(HashSet()) { it.id } }.getOrNull()?.also { ids = it; idsAt = now } ?: ids.orEmpty()
    }

    /** The user's own and collaborative playlists, in library order, folders flattened. */
    suspend fun load(): List<EditablePlaylist> =
        runCatching { MyspotiRuntime.api.playlists.editable() }.getOrDefault(emptyList()).map {
            EditablePlaylist(it.uri.substringAfterLast(':'), it.name, it.trackCount, it.images.best(160)?.url)
        }

    /**
     * One level of the library as Spotify shows it: its folders, then the playlists the user
     * can change, with their covers (Spotify's playlist list has none for mosaic covers;
     * the library has them). Falls back to [load] when the library cannot be read.
     */
    suspend fun level(folderUri: String?): List<EditablePlaylist> {
        val api = MyspotiRuntime.api
        val editable = runCatching { api.playlists.editable() }.getOrNull()?.associateBy { it.uri } ?: return load()
        val items = ArrayList<app.myspoti.api.LibraryItem>()
        runCatching {
            var offset = 0
            repeat(40) {
                val page = api.library.items(filter = LibraryFilter.ALL, folderUri = folderUri, offset = offset, limit = 50)
                items += page.items
                offset = page.nextOffset ?: return@runCatching
            }
        }.onFailure { if (items.isEmpty()) return if (folderUri == null) load() else emptyList() }
        return items.mapNotNull { item ->
            when (item.kind) {
                MediaKind.FOLDER -> EditablePlaylist(item.uri, item.name, item.count ?: 0, null, folderUri = item.uri)
                MediaKind.PLAYLIST -> editable[item.uri]?.let {
                    EditablePlaylist(item.uri.substringAfterLast(':'), item.name, it.trackCount,
                        item.images.best(160)?.url ?: it.images.best(160)?.url)
                }
                else -> null
            }
        }
    }
}
