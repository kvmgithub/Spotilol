package com.music.spotui.video

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal object YouTubeUrlKey {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    fun videoId(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val id = when (host) {
            "youtu.be" -> uri.path.removePrefix("/").takeIf { '/' !in it }
            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> when {
                uri.path == "/watch" -> {
                    val ids = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                        if (part.substringBefore('=') != "v") null
                        else URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    }
                    ids.singleOrNull()
                }
                uri.path.startsWith("/shorts/") -> uri.path.removePrefix("/shorts/").takeIf { '/' !in it }
                uri.path.startsWith("/embed/") -> uri.path.removePrefix("/embed/").takeIf { '/' !in it }
                else -> null
            }
            else -> null
        }
        id?.takeIf { idPattern.matches(it) }
    }.getOrNull()

    fun canonicalUrl(url: String): String = videoId(url)?.let { "https://www.youtube.com/watch?v=$it" } ?: url
    fun cacheKey(url: String, locale: String): String = canonicalUrl(url) + "|locale=" + locale
}
