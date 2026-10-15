package com.keyflow.pianotiles

import android.app.AlertDialog
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistImportUiTest {
    @Test
    fun oneHundredSongsContinueWithoutAWarning() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var accepted = false
            controller.get().reviewPlaylistImport(100, { accepted = true }, { fail("Cancelled") })
            assertTrue(accepted)
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    @Test
    fun overOneHundredSongsRequireConfirmationAndExplainStorage() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var accepted = false
            controller.get().reviewPlaylistImport(101, { accepted = true }, { fail("Cancelled") })
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertFalse(accepted)
            val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("101 songs"))
            assertTrue(message.contains("storage"))
            assertTrue(message.contains("Only the next few songs"))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(accepted)
        }
    }

    @Test
    fun cancelOrBackNeverAcceptsALargeImport() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var cancelled = 0
            val activity = controller.get()
            activity.reviewPlaylistImport(350, { fail("Accepted cancelled import") }, { cancelled++ })
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, cancelled)
            activity.reviewPlaylistImport(350, { fail("Accepted dismissed import") }, { cancelled++ })
            ShadowAlertDialog.getLatestAlertDialog().cancel()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, cancelled)
        }
    }
}
