package com.keyflow.pianotiles

import java.net.URI
import java.net.URLDecoder

object PlaylistUrl {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        require(
            uri.scheme == "https" &&
                uri.host in setOf("music.youtube.com", "www.youtube.com", "youtube.com") &&
                uri.userInfo == null &&
                uri.path in setOf("/playlist", "/watch"),
        ) {
            "Paste an HTTPS YouTube Music playlist link."
        }
        val id =
            uri.rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == "list" }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        require(id.matches(Regex("[A-Za-z0-9_-]{10,150}"))) {
            "The link is missing a valid playlist ID."
        }
        return "https://music.youtube.com/playlist?list=$id"
    }
}
