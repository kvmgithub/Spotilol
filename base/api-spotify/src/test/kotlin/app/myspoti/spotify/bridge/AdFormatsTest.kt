package app.myspoti.spotify.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdFormatsTest {
    private val registration = """{"device":{"brand":"spotify","capabilities":{"change_volume":true,"manifest_formats":["file_ids_mp3","file_urls_mp3","manifest_urls_audio_ad","manifest_ids_video","file_urls_external","file_ids_mp4","file_ids_mp4_dual","manifest_urls_audio_ad"]},"device_id":"x"},"volume":65535}"""

    @Test fun `registration loses the ad formats only`() {
        val out = AdBlock.withoutAdFormats(registration)
        assertTrue(""""manifest_formats":["file_ids_mp3","manifest_ids_video","file_urls_external","file_ids_mp4","file_ids_mp4_dual"]""" in out, out)
        assertTrue(""""device_id":"x"""" in out && """"volume":65535""" in out)
    }

    @Test fun `other bodies stay as they are`() {
        assertEquals("not json", AdBlock.withoutAdFormats("not json"))
        assertEquals("""{"seq_num":2}""", AdBlock.withoutAdFormats("""{"seq_num":2}"""))
    }

    @Test fun `only the registration is rewritten`() {
        assertTrue(AdBlock.isDeviceRegistration("POST", "https://gew4-spclient.spotify.com/track-playback/v1/devices"))
        assertFalse(AdBlock.isDeviceRegistration("PUT", "https://gew4-spclient.spotify.com/track-playback/v1/devices/abc/state"))
        assertFalse(AdBlock.isDeviceRegistration("POST", "https://gew4-spclient.spotify.com/track-playback/v1/devices/abc/state"))
    }
}
