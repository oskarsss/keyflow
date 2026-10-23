package io.github.oskarsss.keyflow.library

import io.github.oskarsss.keyflow.downloader.YTDLRequest
import org.junit.Assert.*
import org.junit.Test

class OnDeviceImportTest {
    @Test
    fun playlistLinksAreCanonicalized() {
        assertEquals(
            "https://music.youtube.com/playlist?list=PLA2wtd_DK6uw",
            PlaylistUrl.normalize("https://www.youtube.com/watch?v=foo&list=PLA2wtd_DK6uw"),
        )
        for (link in
            listOf(
                "file:///etc/passwd",
                "https://youtube.com.evil.test/playlist?list=PLA2wtd_DK6uw",
                "http://youtube.com/playlist?list=PLA2wtd_DK6uw",
                "https://youtube.com/playlist?list=x",
            )) {
            assertThrows(IllegalArgumentException::class.java) { PlaylistUrl.normalize(link) }
        }
    }

    @Test
    fun reusedYtdlnisRequestsPreserveLiteralArguments() {
        val request =
            YTDLRequest("https://youtube.com/watch?v=literal")
                .addOption("--output", "/private/path with spaces/%(id)s.%(ext)s")
                .addOption("--format", "bestaudio")
                .addOption("--extract-audio")
        assertEquals(
            listOf(
                "--output",
                "/private/path with spaces/%(id)s.%(ext)s",
                "--format",
                "bestaudio",
                "--extract-audio",
                "https://youtube.com/watch?v=literal",
            ),
            request.buildCommand(),
        )
    }
}
