package com.keyflow.pianotiles

import android.app.AlertDialog
import android.content.ClipboardManager
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.keyflow.pianotiles.library.PlaylistUrl
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
import com.keyflow.pianotiles.ui.Ui
import org.json.JSONObject

/**
 * Adding a playlist. On the first start it is the welcome page and can't be skipped: a playlist
 * comes first, so its songs prepare in the background while the player warms up.
 */
internal fun MainActivity.showAddPlaylist() {
    val first = store.playlist == null
    val body =
        page(
            Screen.WELCOME,
            if (first) null
            else
                bar(null) {
                    operation++
                    showHome()
                },
        )
    body.addView(artwork(0, 28), LinearLayout.LayoutParams(-1, dp(if (first) 210 else 160)))
    ui.gap(body, 28)
    ui.fill(body, ui.title(if (first) "Welcome to Keyflow" else "Add a playlist", 30f))
    ui.gap(body, 8)
    ui.fill(
        body,
        ui.text(
            if (first) "Turn a YouTube Music playlist into a path of piano levels. Add one to begin: " +
                "its songs become levels on this phone while you warm up with three built-in songs."
            else "Copy songs from a YouTube Music playlist into your own shuffled level list.",
            15f,
            Palette.MUTED,
        ).apply { setLineSpacing(dp(3).toFloat(), 1f) },
    )
    ui.gap(body, 24)
    val field = ui.row().apply {
        background = ui.rounded(Palette.SURFACE, 16, Palette.STROKE)
        setPadding(dp(16), 0, dp(4), 0)
    }
    val input =
        EditText(this).apply {
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.FAINT)
            textSize = 15f
            isSingleLine = true
            hint = "music.youtube.com/playlist?list=…"
            setText(pendingUrl)
            background = null
            setPadding(0, dp(16), 0, dp(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            contentDescription = "YouTube Music playlist link"
        }
    ui.weight(field, input)
    field.addView(
        ui.iconButton(Glyph.Kind.PASTE, "Paste link", Palette.ACCENT) {
            val clip = (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (!text.isNullOrBlank()) {
                input.setText(extractPlaylist(text.toString()))
                input.setSelection(input.text.length)
            }
        },
        LinearLayout.LayoutParams(dp(48), dp(48)),
    )
    ui.fill(body, field)
    val error = ui.text("", 13f, Palette.RED).apply {
        visibility = View.GONE
        setPadding(dp(4), dp(8), 0, 0)
    }
    ui.fill(body, error)
    val submit = {
        pendingUrl = input.text.toString().trim()
        val valid = runCatching { PlaylistUrl.normalize(pendingUrl) }
        if (valid.isSuccess) importPlaylist()
        else {
            error.text = "That doesn't look like a playlist link."
            error.visibility = View.VISIBLE
        }
    }
    input.setOnEditorActionListener { _, _, _ ->
        submit()
        true
    }
    ui.gap(body, 12)
    ui.fill(body, ui.button("Import playlist", Ui.Style.PRIMARY) { submit() })
    if (first) {
        ui.gap(body, 14)
        ui.fill(body, ui.text("In YouTube Music, open a playlist and tap Share › Copy link.", 13f, Palette.FAINT)
            .apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
    }
}

internal fun MainActivity.cancelImport() {
    importReview?.dismiss()
    importReview = null
    activeImport?.let { library.runtime.cancel(it) }
    activeImport = null
}

private fun MainActivity.importPlaylist() {
    cancelImport()
    val job = "import-${java.util.UUID.randomUUID()}"
    activeImport = job
    val run = ++operation
    val body =
        page(
            Screen.IMPORT,
            bar(null, Glyph.Kind.CLOSE) {
                operation++
                cancelImport()
                showAddPlaylist()
            },
        )
    body.addView(artwork(1, 28), LinearLayout.LayoutParams(-1, dp(210)))
    ui.gap(body, 28)
    ui.fill(body, ui.title("Importing…", 28f))
    ui.gap(body, 10)
    val status = ui.text("Reading your playlist", 15f, Palette.MUTED)
    ui.fill(body, status)
    ui.gap(body, 18)
    ui.fill(body, spinner())
    if (
        Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
    )
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20)
    val url = pendingUrl
    io.execute {
        runCatching {
                val raw = library.readPlaylist(url, job) { message ->
                    handler.post {
                        if (operation == run && !isDestroyed)
                            status.text = message.removeSuffix("…").removeSuffix(" on this phone")
                    }
                }
                raw to library.importSongCount(raw)
            }
            .onSuccess { (raw, count) ->
                handler.post {
                    if (operation == run && !isDestroyed) {
                        reviewPlaylistImport(count, accept = {
                            if (operation == run && !isDestroyed) saveImportedPlaylist(raw, run)
                        }, cancel = {
                            operation++
                            cancelImport()
                            showAddPlaylist()
                        })
                    }
                }
            }
            .onFailure { error ->
                handler.post {
                    if (operation == run && !isDestroyed) {
                        activeImport = null
                        showAddPlaylist()
                        showError(error)
                    }
                }
            }
    }
}

private fun MainActivity.saveImportedPlaylist(raw: JSONObject, run: Int) {
    io.execute {
        val result = runCatching { library.createPlaylist(raw) }
        handler.post {
            if (operation == run && !isDestroyed) {
                activeImport = null
                result.onSuccess { playlist ->
                    pendingUrl = ""
                    if (store.playlist == null) showWarmUp(playlist) else selectPlaylist(playlist)
                }.onFailure { error ->
                    showAddPlaylist()
                    showError(error)
                }
            }
        }
    }
}

internal fun MainActivity.reviewPlaylistImport(count: Int, accept: () -> Unit, cancel: () -> Unit) {
    if (count <= 100) {
        accept()
        return
    }
    importReview = AlertDialog.Builder(this)
        .setTitle("Large playlist")
        .setMessage("This playlist has $count songs. Keeping this much music on your phone can use a lot of storage as you play. Only the next few songs are prepared at first. Import the playlist?")
        .setPositiveButton("Import $count songs") { _, _ -> accept() }
        .setNegativeButton("Cancel") { _, _ -> cancel() }
        .setOnCancelListener { cancel() }
        .setOnDismissListener { importReview = null }
        .show()
}

internal fun MainActivity.selectPlaylist(playlist: JSONObject) {
    cancelImport()
    operation++
    stopGame()
    store.save(playlist)
    showHome()
}

/**
 * After the first import: the playlist's songs are being prepared, so the built-in songs are
 * offered to play meanwhile, with an estimate of when its first level is ready.
 */
internal fun MainActivity.showWarmUp(playlist: JSONObject) {
    // The playlist is chosen now, so a restart skips the welcome and keeps preparing it.
    store.save(playlist)
    Preparation.wake(this)
    val id = playlist.getString("id")
    val body = page(Screen.WARMUP, null)
    body.addView(artwork(2, 28), LinearLayout.LayoutParams(-1, dp(210)))
    ui.gap(body, 28)
    ui.fill(body, ui.title("Getting “${playlist.optString("title")}” ready", 28f))
    ui.gap(body, 10)
    val status = ui.text("Checking…", 15f, Palette.ACCENT, ui.medium)
    ui.fill(body, status)
    ui.gap(body, 8)
    ui.fill(body, ui.text("Its songs download and become levels on this phone, even with the screen off. " +
        "Warm up with three built-in songs in the meantime.", 15f, Palette.MUTED)
        .apply { setLineSpacing(dp(3).toFloat(), 1f) })
    ui.gap(body, 24)
    ui.fill(body, ui.button("Warm up with The first notes", Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
        runCatching { selectPlaylist(store.demo()) }.onFailure(::showError)
    })
    ui.gap(body, 8)
    ui.fill(body, ui.button("Go to my playlist", Ui.Style.GHOST) {
        library.snapshot(id)?.let(::selectPlaylist) ?: showHome()
    })
    showWarmUpStatus(scroll, id, status)
}

private fun MainActivity.showWarmUpStatus(page: android.widget.ScrollView?, id: String, status: TextView) {
    io.execute {
        val state = runCatching { Preparation.readiness(this)[id] }.getOrNull()
        handler.post {
            if (scroll !== page || isDestroyed) return@post
            status.text = playlistStatus(state)
            handler.postDelayed({ showWarmUpStatus(page, id, status) }, 3000)
        }
    }
}

/** How soon a playlist's next level can be played, in a short line. */
internal fun playlistStatus(state: Preparation.Readiness?) = when (state) {
    is Preparation.Readiness.Waiting -> "First level playable in ${Preparation.describe(state.ms)}"
    Preparation.Readiness.Offline -> "Waiting for an internet connection"
    Preparation.Readiness.Failed -> "Its first song couldn't be prepared · retrying soon"
    else -> "Ready to play"
}
