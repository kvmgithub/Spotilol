package com.music.spotui.ui.screens

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricWordTapTest {
    private val line = LyricLine(
        timeMs = 1_000,
        text = "Hello dear world",
        words = listOf(LyricWord(1_100, 0, "Hello "), LyricWord(1_600, 0, "dear "), LyricWord(2_200, 0, "world")),
    )

    @Test fun tapOnAWordPlaysFromIt() {
        assertEquals(1_600L, wordTapTime(line, line.text, 7))
        assertEquals(2_200L, wordTapTime(line, line.text, 14))
    }

    @Test fun betweenTwoWordsPlaysTheOneBefore() {
        assertEquals(1_600L, wordTapTime(line, line.text, 10))
    }

    @Test fun firstWordPlaysFromTheLineStart() {
        assertEquals(1_000L, wordTapTime(line, line.text, 2))
    }

    @Test fun lineWithoutWordTimingsPlaysFromItsStart() {
        assertEquals(5_000L, wordTapTime(LyricLine(5_000, "No words"), "No words", 4))
    }
}
