package com.keyflow.pianotiles

import android.app.AlertDialog
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.pianotiles.downloader.YTDLRequest
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun largePlaylistMetadataIsCompleteAndImportWaitsForConfirmation() {
        val source = File(context.cacheDir, "large-playlist-source.json")
        val output = File(context.cacheDir, "large-playlist-output.json")
        val library = OnDeviceLibrary.get(context)
        var importedId: String? = null
        try {
            val entries = JSONArray((0 until 350).map {
                JSONObject().put("_type", "url").put("ie_key", "Youtube")
                    .put("id", "songid%05d".format(it)).put("title", "Song $it")
                    .put("url", "https://www.youtube.com/watch?v=songid%05d".format(it))
            })
            source.writeText(JSONObject().put("_type", "playlist").put("id", "large-fixture")
                .put("title", "Large import test").put("extractor", "youtube:tab").put("extractor_key", "YoutubeTab")
                .put("entries", entries)
                .put("description", "x".repeat(9 * 1024 * 1024)).toString())
            library.runtime.execute(
                YTDLRequest(emptyList()).addOption("--load-info-json", source.path)
                    .addOption("--flat-playlist").addOption("--skip-download").addOption("--no-clean-info-json")
                    .addOption("--dump-single-json"),
                "large-playlist-fixture", 60, outputFile = output,
            )
            assertTrue("Metadata was truncated", output.length() > 8 * 1024 * 1024)
            val raw = JSONObject(output.readText())
            assertEquals(350, library.importSongCount(raw))
            val before = library.savedPlaylists().map { it.getString("id") }.toSet()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var accepted = false
                var cancelled = false
                scenario.onActivity { activity ->
                    activity.reviewPlaylistImport(350, { accepted = true }, { cancelled = true })
                }
                instrumentation.waitForIdleSync()
                assertFalse(accepted)
                assertFalse(cancelled)
                assertEquals(before, library.savedPlaylists().map { it.getString("id") }.toSet())
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("importReview").apply { isAccessible = true }
                    val dialog = field.get(activity) as AlertDialog
                    assertTrue(dialog.isShowing)
                    assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("storage"))
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                instrumentation.waitForIdleSync()
                assertTrue(cancelled)
                assertFalse(accepted)
                assertEquals(before, library.savedPlaylists().map { it.getString("id") }.toSet())
                scenario.onActivity { activity ->
                    activity.reviewPlaylistImport(350, { accepted = true }, { fail("Cancelled confirmation") })
                    val field = MainActivity::class.java.getDeclaredField("importReview").apply { isAccessible = true }
                    (field.get(activity) as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                instrumentation.waitForIdleSync()
                assertTrue(accepted)
                // Match the real flow: persist on the worker only after positive confirmation.
                val imported = library.createPlaylist(raw)
                importedId = imported.getString("id")
                assertEquals(350, imported.getJSONArray("songs").length())
            }
        } finally {
            importedId?.let(library::deletePlaylist)
            source.delete()
            output.delete()
        }
    }
}
