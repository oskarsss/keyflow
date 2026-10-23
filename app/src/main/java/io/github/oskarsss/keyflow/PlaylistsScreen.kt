package io.github.oskarsss.keyflow

import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import io.github.oskarsss.keyflow.library.LibraryStore
import io.github.oskarsss.keyflow.library.OnDeviceLibrary
import io.github.oskarsss.keyflow.library.Preparation
import io.github.oskarsss.keyflow.ui.Glyph
import io.github.oskarsss.keyflow.ui.Palette
import org.json.JSONObject

internal fun MainActivity.showPlaylists() {
    val y = if (screen == Screen.PLAYLISTS) scroll?.scrollY ?: 0 else 0
    val body = page(Screen.PLAYLISTS, bar("Playlists") { showHome() })
    val currentId = store.playlist?.optString("id")
    val playlists = library.savedPlaylists() + store.demo()
    val statuses = HashMap<String, Pair<LinearLayout, TextView>>()
    for ((i, playlist) in playlists.withIndex()) {
        val songs = playlist.getJSONArray("songs")
        val total = songs.length()
        val done = store.completedCount(playlist)
        val title = playlist.optString("title")
        val imported = playlist.optString("id") != LibraryStore.ORIGINALS
        val list = (0 until total).map { songs.getJSONObject(it) }
        val current = playlist.optString("id") == currentId
        val item = ui.row().apply {
            setPadding(dp(12), dp(12), dp(4), dp(12))
            ui.pressable(this, ui.rounded(Palette.SURFACE, 18, if (current) Palette.ACCENT else 0), 18)
            contentDescription = "Open playlist $title"
            isSelected = current
            setOnClickListener { selectPlaylist(playlist) }
        }
        val art = artwork(i + 1, 12)
        val first = list.firstOrNull()
        if (first != null && !first.optBoolean("demo"))
            art.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    covers.into(this, first, false, dp(56))
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        item.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
        ui.gap(item, 14)
        val info = ui.column()
        ui.fill(info, ui.oneLine(ui.text(title, 16f, Palette.TEXT, ui.medium)))
        ui.gap(info, 8)
        val meter = ui.row()
        ui.weight(meter, ui.progress(if (total == 0) 0f else done / total.toFloat(), height = 4))
        ui.gap(meter, 10)
        meter.addView(ui.text("$done/$total", 12f, Palette.MUTED))
        ui.fill(info, meter)
        if (imported) {
            val status = ui.oneLine(ui.text("", 12f, Palette.MUTED)).apply { visibility = android.view.View.GONE }
            info.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            statuses[playlist.getString("id")] = item to status
        }
        ui.weight(item, info)
        ui.gap(item, 4)
        if (imported) {
            item.addView(ui.iconButton(Glyph.Kind.GEAR, "Settings for $title", Palette.MUTED) {
                showPlaylistSettings(playlist.getString("id"), Screen.PLAYLISTS)
            })
        } else item.addView(ui.icon(Glyph.Kind.CHEVRON_RIGHT, Palette.FAINT, 20), LinearLayout.LayoutParams(dp(48), dp(48)))
        ui.fill(body, item)
        ui.gap(body, 10)
    }
    val add = ui.row().apply {
        setPadding(dp(12), dp(12), dp(16), dp(12))
        ui.pressable(this, ui.rounded(Palette.BG, 18, Palette.STROKE), 18)
        setOnClickListener { showAddPlaylist() }
    }
    add.addView(
        ui.icon(Glyph.Kind.PLUS, Palette.ACCENT, 22).apply {
            background = ui.rounded(Palette.SURFACE_HI, 12)
        },
        LinearLayout.LayoutParams(dp(52), dp(52)),
    )
    ui.gap(add, 14)
    add.addView(ui.text("Add playlist", 16f, Palette.ACCENT, ui.medium))
    ui.fill(body, add)
    if (y > 0) scroll?.post { scroll?.scrollTo(0, y) }
    showReadiness(scroll, statuses)
}

/**
 * Greys out playlists whose next level isn't on the phone yet and says when it will be, from
 * that level's place in the preparation queue. Refreshed while the list is open.
 */
private fun MainActivity.showReadiness(page: ScrollView?, statuses: Map<String, Pair<LinearLayout, TextView>>) {
    if (statuses.isEmpty()) return
    io.execute {
        val readiness = runCatching { Preparation.readiness(this) }.getOrDefault(emptyMap())
        handler.post {
            // A rebuilt or different screen has its own page.
            if (scroll !== page || isDestroyed) return@post
            for ((id, views) in statuses) {
                val (item, status) = views
                val state = readiness[id] ?: Preparation.Readiness.Ready
                item.alpha = if (state == Preparation.Readiness.Ready) 1f else .5f
                status.visibility = if (state == Preparation.Readiness.Ready) android.view.View.GONE else android.view.View.VISIBLE
                status.text = when (state) {
                    is Preparation.Readiness.Waiting -> "Getting ready · playable in ${Preparation.describe(state.ms)}"
                    Preparation.Readiness.Offline -> "Next level needs internet to get ready"
                    Preparation.Readiness.Failed -> "Next level couldn't be prepared · open to retry"
                    else -> ""
                }
            }
            if (readiness.values.any { it != Preparation.Readiness.Ready })
                handler.postDelayed({ showReadiness(page, statuses) }, 3000)
        }
    }
}

private fun MainActivity.downloadAll(playlist: JSONObject) {
    val songs = playlist.getJSONArray("songs")
    val offline = store.offlineCheck()
    val missing = (0 until songs.length()).count { !offline(songs.getJSONObject(it)) }
    if (missing == 0) return Toast.makeText(this, "Every song is already downloaded", Toast.LENGTH_SHORT).show()
    val id = playlist.getString("id")
    val waitFor = Preparation.bigDownloadsWaitFor(this)
    Toast.makeText(this, if (waitFor == null) "Downloading $missing songs" else "$missing songs will download when you have $waitFor",
        Toast.LENGTH_SHORT).show()
    io.execute {
        library.setDownloadAll(id, true)
        Preparation.wake(this)
    }
}

/** One playlist's own settings: how much of it stays on the phone, and its name. */
internal fun MainActivity.showPlaylistSettings(id: String, from: Screen? = null) {
    if (from != null) playlistSettingsFrom = from
    val playlist = library.snapshot(id) ?: return leavePlaylistSettings()
    val title = playlist.optString("title")
    val body = page(Screen.PLAYLIST, bar(title) { leavePlaylistSettings() })
    val songs = playlist.getJSONArray("songs")
    val list = (0 until songs.length()).map { songs.getJSONObject(it) }

    val downloads = section(body, "Downloads")
    val stats = ui.text("Checking storage…", 13f, Palette.MUTED).apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
    ui.fill(downloads, stats)
    io.execute {
        val (ready, bytes) = library.usage(list)
        val size = android.text.format.Formatter.formatShortFileSize(this, bytes)
        val waiting = Preparation.bigDownloadsWaitFor(this)?.takeIf { ready < list.size }?.let { "\nMore songs download when you have $it" }
        handler.post { if (!isDestroyed) stats.text = "$ready of ${list.size} songs downloaded · $size${waiting.orEmpty()}" }
    }
    settingRow(downloads, "Download ahead", "Levels kept ready, from your next one") {
        ui.stepper(store.preloadCount(playlist), LibraryStore.MIN_PRELOAD..LibraryStore.MAX_PRELOAD, 1, { "$it" }) { count ->
            io.execute {
                runCatching { library.setPreload(id, count) }.onSuccess { updated ->
                    Preparation.wake(this)
                    handler.post { if (store.playlist?.optString("id") == id) store.save(updated) }
                }
            }
        }
    }
    divider(downloads)
    switchRow(downloads, "Keep downloaded songs", "Smart storage won't remove them", store.keepsDownloads(playlist)) { keep ->
        io.execute { library.setKeepDownloads(id, keep) }
    }
    divider(downloads)
    actionRow(downloads, Glyph.Kind.DOWNLOAD, "Download all songs", "Keep every song available without internet", Palette.TEXT) {
        downloadAll(playlist)
    }
    divider(downloads)
    actionRow(downloads, Glyph.Kind.TRASH, "Remove downloaded songs", "Your next two levels, scores and unlocks stay", Palette.RED) {
        AlertDialog.Builder(this)
            .setTitle("Remove downloaded songs?")
            .setMessage("Songs from “$title” download again when you play them. Songs other playlists also have are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                io.execute {
                    val removed = library.removeDownloads(id)
                    handler.post {
                        if (isDestroyed) return@post
                        Toast.makeText(this, if (removed == 1) "Removed 1 song" else "Removed $removed songs", Toast.LENGTH_SHORT).show()
                        if (screen == Screen.PLAYLIST) showPlaylistSettings(id)
                    }
                }
            }
            .show()
    }

    val details = section(body, "Playlist")
    actionRow(details, Glyph.Kind.EDIT, "Rename", title, Palette.TEXT) { renamePlaylist(playlist) }
    divider(details)
    actionRow(details, Glyph.Kind.TRASH, "Delete playlist", "Scores for its songs stay saved", Palette.RED) {
        confirmDelete(playlist)
    }
}


internal fun MainActivity.leavePlaylistSettings() = if (playlistSettingsFrom == Screen.SETTINGS) showSettings() else showPlaylists()

private fun MainActivity.renamePlaylist(playlist: JSONObject) {
    val id = playlist.getString("id")
    val input =
        EditText(this).apply {
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.FAINT)
            textSize = 16f
            isSingleLine = true
            hint = "Playlist name"
            setText(playlist.optString("title"))
            selectAll()
            filters = arrayOf(android.text.InputFilter.LengthFilter(OnDeviceLibrary.MAX_TITLE))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = "Playlist name"
        }
    val frame = FrameLayout(this).apply {
        setPadding(dp(24), dp(8), dp(24), 0)
        addView(input)
    }
    val dialog =
        AlertDialog.Builder(this)
            .setTitle("Rename playlist")
            .setView(frame)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
    val save = {
        val title = input.text.toString().trim()
        if (title.isEmpty()) input.error = "Enter a name"
        else {
            dialog.dismiss()
            val updated = library.rename(id, title)
            if (store.playlist?.optString("id") == id) store.save(updated)
            if (screen == Screen.PLAYLISTS) showPlaylists()
            else if (screen == Screen.PLAYLIST) showPlaylistSettings(id)
        }
    }
    input.setOnEditorActionListener { _, _, _ ->
        save()
        true
    }
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        input.requestFocus()
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
}

private fun MainActivity.confirmDelete(playlist: JSONObject) {
    val title = playlist.optString("title")
    AlertDialog.Builder(this)
        .setTitle("Delete “$title”?")
        .setMessage("This playlist is removed from Keyflow. Scores for its songs stay saved.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ -> deletePlaylist(playlist) }
        .show()
}

private fun MainActivity.deletePlaylist(playlist: JSONObject) {
    val id = playlist.getString("id")
    io.execute {
        library.deletePlaylist(id)
        handler.post {
            if (isDestroyed) return@post
            if (store.playlist?.optString("id") == id) {
                operation++
                stopGame()
                store.save(library.savedPlaylists().firstOrNull() ?: store.demo())
            }
            Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
            if (screen == Screen.PLAYLISTS) showPlaylists()
            else if (screen == Screen.PLAYLIST) leavePlaylistSettings()
        }
    }
}
