package com.music.spotui.ui.components

/**
 * B25: the `user-profile-view` endpoints hand out `spotify:image:<id>` URIs (the form their
 * protobuf payload carries) instead of an https URL. Nothing in the app turned those into a
 * URL, so Glide was given a string it cannot fetch and kept its grey placeholder: the profile
 * picture and the covers of a user's public playlists never loaded, while the same covers
 * appeared as soon as the playlist page itself was opened (it builds https URLs).
 * A real URL passes through untouched.
 */
fun spotifyImageUrl(url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + url.removePrefix("spotify:image:")
    else -> url
}
