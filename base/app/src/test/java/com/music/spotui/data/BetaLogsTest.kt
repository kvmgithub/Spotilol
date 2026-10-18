package com.music.spotui.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BetaLogsTest {
    @Test fun dropsRepeatsAndNoise() {
        val text = listOf(
            "10-16 23:28:16.561 1 1 D Myspoti : status ready=true track=A paused=false",
            "10-16 23:28:18.625 1 1 D Myspoti : console WARNING: The resource https://x/a.woff2 was preloaded using link preload but not used",
            "10-16 23:28:26.563 1 1 D Myspoti : status ready=true track=A paused=false",
            "10-16 23:28:27.000 1 1 D Myspoti : page net: {\"type\":\"net\",\"status\":200,\"ms\":3}",
            "10-16 23:28:36.564 1 1 D Myspoti : status ready=true track=B paused=false",
            "10-16 23:28:37.000 1 1 W MspPlayer: command failed: Timed out",
        ).joinToString("\n")
        val kept = BetaLogs.useful(text).lines()
        assertEquals(3, kept.size)
        assertEquals(true, kept[1].endsWith("track=B paused=false"))
        assertEquals(true, kept[2].contains("MspPlayer"))
    }
}
