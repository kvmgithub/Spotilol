package app.myspoti.spotify

import app.myspoti.api.MediaKind
import app.myspoti.api.Page
import app.myspoti.api.RecentEntry
import app.myspoti.api.RecentItem
import app.myspoti.api.Recents
import app.myspoti.spotify.gql.Gql
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * The account's "Recently played", read from the Web Player's own list page.
 *
 * The app keeps a play counter of its own, but it only knows what this phone played. Spotify
 * keeps the account's list, so this is the same one the Web Player shows: `recents` with
 * `uris: ["spotify:list:recents:page"]` (offset, limit) returns a list page like a playlist's.
 *
 * Each row also carries a `formatListAttributes` array, and that array holds the grouping the
 * official page renders: a context row announces `children_group_id: N`, the plays it collected
 * carry `group_id_N`, `group_metadata` gives the context's play count and content type (music or
 * not), and `recent_type_saved` marks rows that were added rather than played (Liked Songs).
 */
class SpotifyRecents(private val gql: Gql) : Recents {
    override suspend fun recentEntries(offset: Int, limit: Int): Page<RecentEntry> {
        val response = gql.query("recents", vars(
            "uris" to listOf(RECENTS_PAGE),
            "offset" to offset,
            "limit" to limit,
        ))
        // data.lists[0] is the page itself: {name, uri, items{items[], pagingInfo, totalCount}}.
        val list = response.list("data.lists").firstOrNull() ?: return Page(emptyList(), offset, 0, null)
        val rows = list.obj("items") ?: return Page(emptyList(), offset, 0, null)
        return page(rows, offset, ::recentEntry)
    }

    private fun recentEntry(row: JsonElement): RecentEntry? {
        val item = recentItem(row) ?: return null
        val attributes = row.list("formatListAttributes")
        val keys = attributes.mapNotNull { it.str("key") }
        fun value(key: String): String? = attributes.firstOrNull { it.str("key") == key }?.str("value")
        // The id sits in the key, not in the value (`{"key":"group_id_1","value":""}`), and
        // group_id_0 is the root of the list: a context row carries it, no play belongs to it.
        val group = keys.firstNotNullOfOrNull { key ->
            key.takeIf { it.startsWith("group_id_") }
                ?.removePrefix("group_id_")
                ?.takeIf { it != "0" }
        }
        val metadata = groupMetadata(value("group_metadata"))
        return RecentEntry(
            item = item,
            childrenGroup = value("children_group_id"),
            group = group,
            groupCount = metadata?.count,
            // Spotify words a saved row "titres ajoutés": the flag is the attribute, not the count.
            saved = keys.contains("recent_type_saved"),
            music = when {
                // The server names the content a row holds; music rows say "music".
                metadata?.contentType != null -> metadata.contentType == "music"
                // Any other content_type_* attribute means the row is not music.
                keys.any { it.startsWith("content_type_") && it != "content_type_music" } -> false
                else -> item.kind !in PODCAST_KINDS
            },
        )
    }

    private fun recentItem(row: JsonElement): RecentItem? {
        // A row wraps its entity: `entity.data` is the entity, `entity._uri` repeats its uri.
        val entity = row.obj("entity") ?: return null
        val d = entity.obj("data") ?: entity
        val uri = d.str("uri") ?: entity.str("_uri") ?: return null
        val identity = d.obj("identityTrait")
        val title = identity.str("name") ?: d.str("name") ?: return null
        // identityTrait.type is localised by the server ("Titre", "Playlist", "EP"): it is the
        // type label the page prints, so it is kept rather than rebuilt from the entity type.
        val people = identity.list("contributors.items").mapNotNull { it.str("name") }
        // Artists carry a plain image, not a square cover: without the fallback their rows would
        // show an empty circle.
        val square = d.list("visualIdentityTrait.squareCoverImage.image.data.sources")
        return RecentItem(
            uri = uri,
            kind = kindOf(d.str("entityTypeTrait.type"), uri),
            title = title,
            subtitle = people.joinToString(", ").ifBlank { null },
            images = images(square).ifEmpty { images(d.list("visualIdentityTrait.image.data.sources")) },
            // Durations come in seconds here, unlike the tracks of the other operations.
            durationMs = (d.long("consumptionExperienceTrait.duration.seconds") ?: 0) * 1000,
            explicit = d.list("consumptionExperienceTrait.contentRatings")
                .any { (it as? JsonPrimitive)?.content == "CONTENT_RATING_EXPLICIT" },
            playedAtMs = playedOn(row.obj("addedAt")),
            typeLabel = identity.str("type"),
        )
    }

    private fun kindOf(entityType: String?, uri: String): MediaKind = when (entityType) {
        "ENTITY_TYPE_TRACK" -> MediaKind.TRACK
        "ENTITY_TYPE_ALBUM" -> MediaKind.ALBUM
        "ENTITY_TYPE_ARTIST" -> MediaKind.ARTIST
        "ENTITY_TYPE_PLAYLIST" -> MediaKind.PLAYLIST
        "ENTITY_TYPE_SHOW", "ENTITY_TYPE_PODCAST" -> MediaKind.SHOW
        "ENTITY_TYPE_EPISODE" -> MediaKind.EPISODE
        "ENTITY_TYPE_AUDIOBOOK" -> MediaKind.AUDIOBOOK
        else -> MediaKind.of(uri)
    }

    /** The row dates the play (`addedAt: {year, month, day}`): a day, with no time of day. */
    private fun playedOn(node: JsonElement?): Long? {
        val year = node.int("year") ?: return null
        val month = node.int("month") ?: return null
        val day = node.int("day") ?: return null
        return runCatching {
            LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** What `group_metadata` names: the row's content type ("music") and its play count. */
    private data class GroupMeta(val contentType: String?, val count: Int?)

    /**
     * `group_metadata` is a small protobuf blob: `1: index`, `4: {1: content type, 2: plays}`.
     * Its count is the "N titres lus" the row prints, and it stays right where counting the rows
     * would not: the last group of a page is cut by the page size.
     */
    private fun groupMetadata(base64: String?): GroupMeta? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(base64)
            var i = 0
            var type: String? = null
            var count: Int? = null
            while (i < bytes.size) {
                val tag = bytes[i++].toInt() and 0xFF
                when (tag and 7) {
                    // Varint field (the index): skipped, it repeats what children_group_id says.
                    0 -> { while (i < bytes.size && (bytes[i].toInt() and 0x80) != 0) i++; i++ }
                    2 -> {
                        val length = bytes[i++].toInt() and 0xFF
                        if (tag ushr 3 == 4 && i + length <= bytes.size) {
                            val inner = bytes.copyOfRange(i, i + length)
                            type = innerString(inner)
                            count = innerCount(inner)
                        }
                        i += length
                    }
                    else -> return@runCatching GroupMeta(type, count)
                }
            }
            GroupMeta(type, count)
        }.getOrNull()
    }

    /** Field 1 of the inner message: the content type, as plain UTF-8. */
    private fun innerString(inner: ByteArray): String? {
        var i = 0
        while (i < inner.size) {
            val tag = inner[i++].toInt() and 0xFF
            when (tag and 7) {
                0 -> { while (i < inner.size && (inner[i].toInt() and 0x80) != 0) i++; i++ }
                2 -> {
                    val length = inner[i++].toInt() and 0xFF
                    if (tag ushr 3 == 1) return String(inner, i, length)
                    i += length
                }
                else -> return null
            }
        }
        return null
    }

    /** Field 2 of the inner message: the play count. */
    private fun innerCount(inner: ByteArray): Int? {
        var i = 0
        while (i < inner.size) {
            val tag = inner[i++].toInt() and 0xFF
            when (tag and 7) {
                0 -> {
                    var value = 0
                    var shift = 0
                    while (i < inner.size) {
                        val byte = inner[i++].toInt() and 0xFF
                        value = value or ((byte and 0x7F) shl shift)
                        shift += 7
                        if (byte and 0x80 == 0) break
                    }
                    if (tag ushr 3 == 2) return value
                }
                2 -> {
                    val length = inner[i++].toInt() and 0xFF
                    i += length
                }
                else -> return null
            }
        }
        return null
    }

    private companion object {
        const val RECENTS_PAGE = "spotify:list:recents:page"

        /** Kinds that never count as music for the page's pills, whatever the attributes say. */
        val PODCAST_KINDS = setOf(MediaKind.SHOW, MediaKind.EPISODE, MediaKind.AUDIOBOOK, MediaKind.CHAPTER)
    }
}
