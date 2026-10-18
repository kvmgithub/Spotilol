package com.music.spotui.data.entity

@kotlinx.serialization.Serializable
data class AlbumsModel(
    val id : Int,
    val artists : String,
    val coverUri : String,
    val name : String,
    val time : String,
    // Release kind from Spotify: "album", "single", "compilation"... Empty when unknown.
    val type : String = "",
    // Real Spotify album id (empty when unknown) — the page is loaded by id.
    val spotifyId : String = "",
){
    constructor() : this( -1,"" ,"" ,"", "")
}
