package com.music.spotui.data.api

/**
 * Lyric translation is removed in Spotilol UI: it sent the lyrics to Google's public
 * translate endpoint. Kept as a stub so the lyrics screen's contract compiles; it never
 * makes a request and the screen hides the translate actions.
 */
object LyricTranslate {
    data class Result(val lines: List<String>, val sourceLanguage: String?)

    @Suppress("UNUSED_PARAMETER")
    fun detectLanguage(lines: List<String>, targetLang: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun translateLines(lines: List<String>, targetLang: String): Result? = null
}
