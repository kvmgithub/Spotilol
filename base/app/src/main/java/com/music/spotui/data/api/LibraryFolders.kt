package com.music.spotui.data.api

import app.myspoti.host.android.MyspotiRuntime

/**
 * Library folders: real Spotify folders, synced to the account (the public Web API has none).
 */
object LibraryFolders {
    /** Creates a folder at the top of the library; its uri, or null when Spotify refused. */
    suspend fun create(name: String): String? =
        runCatching { MyspotiRuntime.api.library.createFolder(name.trim()) }.getOrNull()?.also { changed() }

    /** Renames a folder; false when Spotify refused. */
    suspend fun rename(uri: String, name: String): Boolean =
        runCatching { MyspotiRuntime.api.library.renameFolder(uri, name.trim()) }.isSuccess.also { if (it) changed() }

    /** Deletes a folder (its playlists go to the level above); false when Spotify refused. */
    suspend fun delete(uri: String): Boolean =
        runCatching { MyspotiRuntime.api.library.deleteFolder(uri) }.isSuccess.also { if (it) changed() }

    /** The library changed: the next load must come from Spotify, not from the caches. */
    fun changed() {
        Api.HomeCache.libraryRoot = null
        Api.HomeCache.folders.clear()
    }
}
