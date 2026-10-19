package app.myspoti.spotify

import app.myspoti.api.PlaylistSort
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Answers each `Myspoti.call(id, method, args)` with [reply] and keeps every request it was sent. */
private class RecordingHost(val reply: (JsonObject) -> JsonElement) : BridgeHost {
    val calls = mutableListOf<JsonObject>()
    override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override suspend fun evaluate(script: String) {
        val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""").find(script) ?: return
        val args = Bridge.json.parseToJsonElement(m.groupValues[3]).jsonObject
        calls += args
        messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":${reply(args)}}""")
    }
}

/** Records each GraphQL request's variables and answers with [respond]. */
private class FakeGql(val respond: (String, JsonObject) -> JsonElement) : Gql {
    val variables = mutableListOf<JsonObject>()
    override suspend fun query(name: String, variables: JsonObject): JsonElement {
        this.variables += variables
        return respond(name, variables)
    }
}

private fun JsonObject.method() = this["method"]!!.jsonPrimitive.content
private fun JsonObject.args() = this["args"]!!.jsonArray.toString()

/** A `fetchPlaylistContents` answer holding rows `offset until min(total, offset + limit)`. */
private fun playlistPage(total: Int, offset: Int, limit: Int): JsonElement {
    val rows = (offset until minOf(total, offset + limit)).joinToString(",") { i ->
        """{"uid":"u$i","addedAt":{"isoString":"2026-01-01T00:00:00Z"},"itemV2":{"__typename":"TrackResponseWrapper","data":
          {"__typename":"Track","uri":"spotify:track:t$i","name":"Track ${"%03d".format(i)}","duration":{"totalMilliseconds":1000},
          "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
          "albumOfTrack":{"uri":"spotify:album:b","name":"Album"}}}}"""
    }
    return Bridge.json.parseToJsonElement("""{"data":{"playlistV2":{"__typename":"Playlist","content":{"totalCount":$total,"items":[$rows]}}}}""")
}

class SpotifyWritesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    private fun runBridge(reply: (JsonObject) -> JsonElement, block: suspend (RecordingHost, Bridge) -> Unit) = runBlocking {
        val host = RecordingHost(reply)
        val bridge = Bridge(host, scope)
        host.messages.subscriptionCount.first { it > 0 }
        block(host, bridge)
    }

    @Test fun `don't play this artist writes the artistban set and nothing else`() = runBridge({ JsonNull }) { host, bridge ->
        val extras = SpotifyTrackExtras(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        extras.setArtistBanned("spotify:artist:a", true)
        extras.setArtistBanned("spotify:artist:a", false)
        assertEquals(listOf("add", "remove"), host.calls.map { it.method() })
        assertEquals("""["artistban",["spotify:artist:a"]]""", host.calls[0].args())
        assertEquals("CollectionPlatformAPI", host.calls[0]["service"]!!.jsonPrimitive.content)
    }

    @Test fun `saving an episode uses the listenlater set that savedEpisodes reads`() = runBridge({ JsonNull }) { host, bridge ->
        val podcasts = SpotifyPodcasts(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        podcasts.setEpisodeSaved("spotify:episode:e", true)
        podcasts.setEpisodeSaved("spotify:episode:e", false)
        assertEquals(listOf("add", "remove"), host.calls.map { it.method() })
        assertEquals("""["listenlater",["spotify:episode:e"]]""", host.calls[0].args())
    }

    @Test fun `public, private and collaborative set the base permission`() = runBridge({ call ->
        if (call.method() == "getBasePermission") kotlinx.serialization.json.JsonPrimitive("BLOCKED") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.setPublic("spotify:playlist:p", false)
        playlists.setCollaborative("spotify:playlist:p", true)
        playlists.setPublic("spotify:playlist:p", true)
        val sets = host.calls.filter { it.method() == "setBasePermission" }.map { it.args() }
        assertEquals(
            listOf("""["spotify:playlist:p","BLOCKED"]""", """["spotify:playlist:p","CONTRIBUTOR"]""", """["spotify:playlist:p","VIEWER"]"""),
            sets,
        )
        assertEquals(app.myspoti.api.PlaylistAccess.PRIVATE, playlists.access("spotify:playlist:p"))
    }

    @Test fun `rename still sends the name as a string`() = runBridge({ call ->
        if (call.method() == "getMetadata") Bridge.json.parseToJsonElement("""{"name":"New"}""") else JsonNull
    }) { host, bridge ->
        val playlists = SpotifyPlaylists(FakeGql { _, _ -> JsonNull }, bridge, SpotifyHttp(OkHttpClient(), bridge))
        playlists.rename("spotify:playlist:p", "New")
        assertEquals("""["spotify:playlist:p",{"name":"New"}]""", host.calls.first { it.method() == "updateDetails" }.args())
    }

    @Test fun `sorted tracks read every page of 100 and sort on the client`() = runBridge({ JsonNull }) { _, bridge ->
        val gql = FakeGql { _, v ->
            playlistPage(250, v["offset"]!!.jsonPrimitive.content.toInt(), v["limit"]!!.jsonPrimitive.content.toInt())
        }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        val sorted = playlists.sortedTracks("spotify:playlist:p", PlaylistSort.TITLE, descending = true)
        assertEquals(listOf("0", "100", "200"), gql.variables.map { it["offset"]!!.jsonPrimitive.content })
        assertEquals(250, sorted.size)
        assertEquals("Track 249", sorted.first().name)
        assertEquals("Track 000", sorted.last().name)
    }

    @Test fun `custom sort keeps the playlist's own order`() = runBridge({ JsonNull }) { _, bridge ->
        val gql = FakeGql { _, v -> playlistPage(3, 0, v["limit"]!!.jsonPrimitive.content.toInt()) }
        val playlists = SpotifyPlaylists(gql, bridge, SpotifyHttp(OkHttpClient(), bridge))
        assertEquals(listOf("u0", "u1", "u2"), playlists.sortedTracks("spotify:playlist:p").map { it.uid })
        assertEquals(listOf("u2", "u1", "u0"), playlists.sortedTracks("spotify:playlist:p", PlaylistSort.CUSTOM, true).map { it.uid })
    }
}
