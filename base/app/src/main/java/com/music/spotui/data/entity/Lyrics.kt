package com.music.spotui.data.entity

/** One word (or syllable) of word-synced lyrics; [endMs] 0 = until the next word. */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * One line of lyrics. [timeMs] is the start time in ms (0 for unsynced lyrics);
 * [words] is set only when the source is word-synced (karaoke-style highlight).
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
)

/** Spotify's per-track lyrics colours (color-lyrics `colors`), ARGB ints. */
data class LyricsColors(
    val background: Int,
    val text: Int,
    val highlight: Int,
)

/**
 * Lyrics for a track. When [synced] is true [lines] carry real timestamps and the
 * UI highlights/scrolls in time with playback; otherwise [lines] are just the
 * plain lyric lines (timeMs = 0) shown statically.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val language: String? = null,
    /** Who provides the text, as Spotify credits it ("Musixmatch"), or our source name. */
    val provider: String? = null,
    val colors: LyricsColors? = null,
    /** False when Spotify only gives a preview (Free cap): the card says "Aperçu des paroles". */
    val uncapped: Boolean = true,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val wordSynced: Boolean get() = lines.any { it.words != null }
}
