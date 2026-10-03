package com.project.lol.service

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaSearchTest {
    @Test fun callerTextRemainsAQuotedArgument() {
        val query = "'); actStop(); //\n\"\\"
        val script = MediaSearch.playSearchScript(query)
        assertTrue(script.contains("searchMediaItems(${JSONObject.quote(query)})"))
        assertFalse(script.contains("searchMediaItems('$query')"))
    }

    @Test fun oversizedVoiceQueryIsBounded() {
        val query = "x".repeat(2000)
        val script = MediaSearch.playSearchScript(query)
        assertTrue(script.contains(JSONObject.quote(query.take(1024))))
        assertFalse(script.contains(query))
    }

    @Test fun emptyQueryResumesPlaybackWithoutSearch() {
        assertEquals("actPlayPause(true);", MediaSearch.playSearchScript(null))
        assertEquals("actPlayPause(true);", MediaSearch.playSearchScript("  "))
    }
}
