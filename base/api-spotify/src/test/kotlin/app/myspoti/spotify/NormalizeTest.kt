package app.myspoti.spotify

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Volume normalization: Spotify's loudness values read, and the gains made of them. */
class NormalizeTest {
    @Test fun `loudness by id, entries without a usable value left out`() {
        val answer = Json.parseToJsonElement(
            """{"audio_features":[{"id":"a","loudness":-5.9,"tempo":120},null,{"id":"b"},{"id":"c","loudness":"x"},{"loudness":-7}]}""",
        )
        assertEquals(mapOf("a" to -5.9), SpotifyPlayer.loudnessById(answer))
        assertTrue(SpotifyPlayer.loudnessById(Json.parseToJsonElement("""{"error":{"status":403}}""")).isEmpty())
        assertTrue(SpotifyPlayer.loudnessById(null).isEmpty())
    }

    @Test fun `gains bring songs to the target, within their bounds`() {
        assertEquals(-4.0, SpotifyPlayer.normalizeGain(-6.0), 1e-9)
        assertEquals(2.0, SpotifyPlayer.normalizeGain(-12.0), 1e-9)
        assertEquals(SpotifyPlayer.NORMALIZE_MAX_DB, SpotifyPlayer.normalizeGain(-30.0), 1e-9)
        assertEquals(SpotifyPlayer.NORMALIZE_MIN_DB, SpotifyPlayer.normalizeGain(5.0), 1e-9)
    }
}
