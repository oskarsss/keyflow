package com.keyflow.pianotiles

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {
    enum class Screen { WELCOME, IMPORT, HOME, PLAYLISTS, PLAYLIST, SETTINGS, ARCADE, LOADING, GAME, RESULTS }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: LibraryStore
    private lateinit var ui: Ui
    private lateinit var covers: Covers
    private var screen = Screen.HOME
    private var scroll: ScrollView? = null
    private var pendingUrl = ""
    private var operation = 0
    private var activeImport: String? = null
    private var importReview: AlertDialog? = null
    private var foreground = true
    private var polling = false
    private val attemptedPrefetch = mutableSetOf<String>()
    private var player: MediaPlayer? = null
    private var game: GameView? = null
    private var pauseOverlay: View? = null
    private var currentSong: JSONObject? = null
    private var currentIndex = 0
    private var run: Arcade.Run? = null
    private val random = kotlin.random.Random(System.nanoTime())
    private val arcadePreparation by lazy { ArcadePreparation(this) }
    private val maintenance = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "song-cleanup")
    }
    private var protectedSongId: String? = null
    /** Shown on the next results screen when that run moved the timing offset. */
    private var timingNote: String? = null

    /** A slice of a song played inside an arcade run. */
    private class Stage(val startMs: Int, val endMs: Int)
    private val rows = HashMap<String, SongRow>()
    private var hero: Pair<String, TextView>? = null

    private class SongRow(val index: Int, val subtitle: TextView, val trailing: FrameLayout)

    private val library: OnDeviceLibrary
        get() = OnDeviceLibrary.get(this)

    private val isOriginals
        get() = store.playlist?.optString("id") == ORIGINALS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        ui = Ui(this)
        covers = Covers(store)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        if (intent.action == Intent.ACTION_SEND) {
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showAddPlaylist()
        } else if (store.playlist == null) showAddPlaylist() else showHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            stopGame()
            cancelImport()
            operation++
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showAddPlaylist()
        }
    }

    private fun extractPlaylist(value: String?): String {
        val text = value?.trim() ?: return ""
        return Regex("https://(?:music\\.|www\\.)?youtube\\.com/[^\\s]+").find(text)?.value ?: text
    }

    private fun dp(value: Int) = ui.dp(value)

    // ---------------------------------------------------------------- scaffolding

    private fun applyInsets(view: View, keyboard: Boolean) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types =
                    WindowInsets.Type.systemBars() or if (keyboard) WindowInsets.Type.ime() else 0
                val safe = insets.getInsets(types)
                target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            insets
        }
    }

    /** Builds a screen: optional fixed top bar, scrolling body, optional fixed footer. */
    private fun page(
        screen: Screen,
        bar: View?,
        footer: View? = null,
        centered: Boolean = false,
    ): LinearLayout {
        this.screen = screen
        rows.clear()
        hero = null
        val root = ui.column().apply { background = ui.backdrop() }
        applyInsets(root, true)
        if (bar != null) root.addView(bar, LinearLayout.LayoutParams(-1, dp(64)))
        val scroller =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        scroll = scroller
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        // Centred pages default children to wrap_content so they actually sit in the middle.
        val body =
            (if (centered)
                    object : LinearLayout(this) {
                        override fun generateDefaultLayoutParams() = LayoutParams(-2, -2)
                    }
                else LinearLayout(this))
                .apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(if (bar == null) 32 else 4), dp(20), dp(24))
                    if (centered) gravity = Gravity.CENTER_HORIZONTAL
                }
        scroller.addView(body)
        if (footer != null)
            root.addView(
                footer.apply { setPadding(dp(20), dp(8), dp(20), dp(16)) },
                LinearLayout.LayoutParams(-1, -2),
            )
        setContentView(root)
        return body
    }

    private fun bar(title: String?, back: Glyph.Kind = Glyph.Kind.BACK, onBack: (() -> Unit)?) =
        ui.row().apply {
            setPadding(dp(8), 0, dp(8), 0)
            if (onBack != null)
                addView(
                    ui.iconButton(back, if (back == Glyph.Kind.CLOSE) "Close" else "Back") {
                        onBack()
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)),
                )
            ui.weight(
                this,
                ui.oneLine(ui.text(title ?: "", 19f, Palette.TEXT, ui.medium)).apply {
                    setPadding(dp(if (onBack == null) 12 else 4), 0, 0, 0)
                },
            )
        }

    private fun section(parent: LinearLayout, label: String): LinearLayout {
        ui.gap(parent, 20)
        ui.fill(parent, ui.caption(label).apply { setPadding(dp(4), 0, 0, dp(10)) })
        val card = ui.column().apply { background = ui.rounded(Palette.SURFACE, 20) }
        ui.fill(parent, card)
        return card
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(this).apply { setBackgroundColor(Palette.STROKE) },
            LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(18), 0, dp(18), 0) },
        )
    }

    private fun labels(title: String, subtitle: String?) =
        ui.column().apply {
            addView(ui.text(title, 15f, Palette.TEXT, ui.medium))
            if (subtitle != null) {
                ui.gap(this, 4)
                addView(ui.text(subtitle, 12.5f, Palette.MUTED))
            }
        }

    private fun artwork(variant: Int, radius: Int) =
        FrameLayout(this).apply {
            background = ui.rounded(Palette.SURFACE_HI, radius)
            clipToOutline = true
            addView(TileArtwork(this@MainActivity, variant), FrameLayout.LayoutParams(-1, -1))
        }

    private fun spinner(indeterminate: Boolean = true) =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = indeterminate
            max = 100
            val tint = ColorStateList.valueOf(Palette.ACCENT)
            progressTintList = tint
            indeterminateTintList = tint
            progressBackgroundTintList = ColorStateList.valueOf(Palette.STROKE)
        }

    // ---------------------------------------------------------------- add playlist

    private fun showAddPlaylist() {
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
        ui.fill(body, ui.title(if (first) "Keyflow" else "Add a playlist", 30f))
        ui.gap(body, 8)
        ui.fill(
            body,
            ui.text(
                "Copy songs from a YouTube Music playlist into your own shuffled level list.",
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
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
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
            ui.gap(body, 8)
            ui.fill(
                body,
                ui.button("Play the built-in songs", Ui.Style.GHOST, Glyph.Kind.MUSIC) {
                    runCatching { selectPlaylist(store.demo()) }.onFailure(::showError)
                },
            )
        }
    }

    private fun cancelImport() {
        importReview?.dismiss()
        importReview = null
        activeImport?.let { library.runtime.cancel(it) }
        activeImport = null
    }

    private fun importPlaylist() {
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

    private fun saveImportedPlaylist(raw: JSONObject, run: Int) {
        io.execute {
            val result = runCatching { library.createPlaylist(raw) }
            handler.post {
                if (operation == run && !isDestroyed) {
                    activeImport = null
                    result.onSuccess { playlist ->
                        pendingUrl = ""
                        selectPlaylist(playlist)
                    }.onFailure { error ->
                        showAddPlaylist()
                        showError(error)
                    }
                }
            }
        }
    }

    internal fun reviewPlaylistImport(count: Int, accept: () -> Unit, cancel: () -> Unit) {
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

    private fun selectPlaylist(playlist: JSONObject) {
        cancelImport()
        operation++
        stopGame()
        attemptedPrefetch.clear()
        store.save(playlist)
        showHome()
    }

    // ---------------------------------------------------------------- playlists

    private fun showPlaylists() {
        val y = if (screen == Screen.PLAYLISTS) scroll?.scrollY ?: 0 else 0
        val body = page(Screen.PLAYLISTS, bar("Playlists") { showHome() })
        val currentId = store.playlist?.optString("id")
        val playlists = library.savedPlaylists() + store.demo()
        for ((i, playlist) in playlists.withIndex()) {
            val songs = playlist.getJSONArray("songs")
            val total = songs.length()
            val done = store.completedCount(playlist)
            val title = playlist.optString("title")
            val imported = playlist.optString("id") != ORIGINALS
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
    }

    private fun downloadAll(playlist: JSONObject) {
        val songs = playlist.getJSONArray("songs")
        val missing = (0 until songs.length()).count { !store.offline(songs.getJSONObject(it)) }
        if (missing == 0) return Toast.makeText(this, "Every song is already downloaded", Toast.LENGTH_SHORT).show()
        val id = playlist.getString("id")
        Toast.makeText(this, "Downloading $missing songs", Toast.LENGTH_SHORT).show()
        io.execute {
            library.markQueued(id)
            handler.post {
                DownloadService.start(this, id, all = true)
                if (screen == Screen.PLAYLISTS) showPlaylists()
            }
        }
    }

    /** One playlist's own settings: how much of it stays on the phone, and its name. */
    private fun showPlaylistSettings(id: String, from: Screen? = null) {
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
            handler.post { if (!isDestroyed) stats.text = "$ready of ${list.size} songs downloaded · $size" }
        }
        settingRow(downloads, "Download ahead", "Levels kept ready, from your next one") {
            ui.stepper(store.preloadCount(playlist), LibraryStore.MIN_PRELOAD..20, 1, { "$it" }) { count ->
                store.prefs.edit().putInt("preload.$id", count).apply()
                attemptedPrefetch.clear()
                io.execute {
                    runCatching { library.setPreload(id, count) }.onSuccess { updated ->
                        handler.post {
                            if (store.playlist?.optString("id") == id) store.save(updated)
                            prefetch()
                        }
                    }
                }
            }
        }
        divider(downloads)
        settingRow(downloads, "Keep downloaded songs", "Smart storage won't remove them") {
            toggle(store.keepsDownloads(playlist), "Keep downloaded songs") { library.setKeepDownloads(id, it) }
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
                            attemptedPrefetch.clear()
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

    private var playlistSettingsFrom = Screen.PLAYLISTS

    private fun leavePlaylistSettings() = if (playlistSettingsFrom == Screen.SETTINGS) showSettings() else showPlaylists()

    private fun renamePlaylist(playlist: JSONObject) {
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

    private fun confirmDelete(playlist: JSONObject) {
        val title = playlist.optString("title")
        AlertDialog.Builder(this)
            .setTitle("Delete “$title”?")
            .setMessage("This playlist is removed from Keyflow. Scores for its songs stay saved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deletePlaylist(playlist) }
            .show()
    }

    private fun deletePlaylist(playlist: JSONObject) {
        val id = playlist.getString("id")
        io.execute {
            library.deletePlaylist(id)
            handler.post {
                if (isDestroyed) return@post
                if (store.playlist?.optString("id") == id) {
                    operation++
                    stopGame()
                    attemptedPrefetch.clear()
                    store.save(library.savedPlaylists().firstOrNull() ?: store.demo())
                }
                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
                if (screen == Screen.PLAYLISTS) showPlaylists()
                else if (screen == Screen.PLAYLIST) leavePlaylistSettings()
            }
        }
    }

    // ---------------------------------------------------------------- home

    private fun nextIndex(): Int {
        val songs = store.songs()
        return (0 until songs.length()).firstOrNull { !store.completed(songs.getJSONObject(it)) }
            ?: -1
    }

    private fun showHome() {
        if (isDestroyed || store.playlist == null) return
        arcadePreparation.pause()
        scheduleCleanup()
        library.snapshot(store.playlist!!.getString("id"))?.let(store::save)
        val playlist = store.playlist!!
        val top = ui.row().apply { setPadding(dp(8), 0, dp(8), 0) }
        val switcher = ui.column().apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            ui.pressable(this, null, 14)
            contentDescription = "Switch playlist"
            setOnClickListener { showPlaylists() }
        }
        switcher.addView(ui.caption("Playlist").apply { textSize = 10f }, LinearLayout.LayoutParams(-2, -2))
        ui.gap(switcher, 4)
        switcher.addView(
            ui.oneLine(ui.text(playlist.optString("title"), 18f, Palette.TEXT, ui.medium)).apply {
                setCompoundDrawablesRelative(null, null, ui.glyph(Glyph.Kind.CHEVRON_DOWN, Palette.MUTED, 18), null)
                compoundDrawablePadding = dp(4)
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        top.addView(switcher, LinearLayout.LayoutParams(0, -2, 1f))
        val streak = store.streak()
        top.addView(
            ui.text("$streak", 14f, if (streak > 0) Palette.GOLD else Palette.MUTED, ui.heavy).apply {
                setCompoundDrawablesRelative(
                    ui.glyph(Glyph.Kind.FLAME, if (streak > 0) 0xFFFB923C.toInt() else Palette.FAINT, 18),
                    null,
                    null,
                    null,
                )
                compoundDrawablePadding = dp(3)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(12), dp(8))
                background = ui.rounded(Palette.SURFACE_HI, 18)
                contentDescription = "$streak day streak"
            },
        )
        ui.gap(top, 8)
        val xp = store.xp
        val level = LibraryStore.level(xp)
        top.addView(
            ui.levelBadge(level, LibraryStore.levelFraction(xp)).apply {
                setOnClickListener {
                    val missing = LibraryStore.levelStart(level + 1) - xp
                    Toast.makeText(context, "Level $level · $missing XP to level ${level + 1}", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        top.addView(
            ui.iconButton(Glyph.Kind.GEAR, "Settings", Palette.MUTED) { showSettings() },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        val body = page(Screen.HOME, top)
        val songs = store.songs()
        val total = songs.length()
        val done = store.completedCount()
        var earned = 0
        for (i in 0 until total) earned += store.stars(songs.getJSONObject(i))

        val summary = ui.row()
        ui.weight(summary, ui.text("$done of $total cleared", 13f, Palette.MUTED))
        summary.addView(ui.icon(Glyph.Kind.STAR, Palette.GOLD, 14))
        ui.gap(summary, 4)
        summary.addView(ui.text("$earned / ${total * 3}", 13f, Palette.MUTED))
        ui.fill(body, summary)
        ui.gap(body, 10)
        ui.fill(body, ui.meter(if (total == 0) 0f else done / total.toFloat()))
        ui.gap(body, 20)

        val next = nextIndex()
        if (next >= 0) addHero(body, next, songs.getJSONObject(next))
        else {
            val card = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            card.addView(ui.stars(3, 26, 4))
            ui.gap(card, 10)
            card.addView(ui.text("Every level cleared", 17f, Palette.TEXT, ui.medium))
            ui.gap(card, 4)
            card.addView(ui.text("Replay any song to chase three stars.", 13f, Palette.MUTED))
            ui.fill(body, card)
        }
        ui.gap(body, 24)
        addArcadeCards(body)
        ui.gap(body, 28)
        ui.fill(body, ui.caption("Journey").apply { setPadding(dp(4), 0, 0, dp(4)) })
        addJourney(body, songs, next)
        startPolling()
        prefetch()
        arcadePreparation.prepareSoon()
    }

    private fun addHero(body: LinearLayout, index: Int, song: JSONObject) {
        // A gradient frame makes the next level read as the main call to action.
        val frame = FrameLayout(this).apply {
            background = ui.gradient(26)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val card = ui.column().apply {
            background = ui.rounded(0xFF15131F.toInt(), 24)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        frame.addView(card)
        val top = ui.row()
        val cover = artwork(index, 16)
        cover.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                covers.into(this, song, false, dp(240))
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        top.addView(cover, LinearLayout.LayoutParams(dp(104), dp(104)))
        ui.gap(top, 16)
        val label = ui.column()
        label.addView(ui.caption("Next up · Level ${index + 1}", Palette.ACCENT), LinearLayout.LayoutParams(-2, -2))
        ui.gap(label, 8)
        label.addView(
            ui.text(song.optString("title"), 20f, Palette.TEXT, ui.medium).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        ui.gap(label, 4)
        label.addView(ui.oneLine(ui.text(song.optString("artist"), 13.5f, Palette.MUTED)))
        if (store.score(song) > 0) {
            ui.gap(label, 8)
            label.addView(ui.text(String.format(Locale.US, "Best %,d", store.score(song)), 12.5f, Palette.MUTED))
        }
        ui.weight(top, label)
        ui.fill(card, top)
        ui.gap(card, 14)
        val play = ui.button(heroLabel(song), Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
            prepareSong(song, index)
        }
        ui.fill(card, play)
        hero = song.optString("id") to play.label
        ui.fill(body, frame)
    }

    private fun heroLabel(@Suppress("UNUSED_PARAMETER") song: JSONObject) = "Play"

    /** Whole-song preparation: the download, then the on-device chart, as one progress value. */
    private fun downloadFraction(song: JSONObject): Float? =
        song.optDouble("prepared").takeIf { song.optString("status") == "caching" && !it.isNaN() }?.toFloat()

    private fun downloadStage(song: JSONObject): String {
        val fraction = downloadFraction(song) ?: return "Getting music ready…"
        val stage = song.optString("progress").ifBlank { "Preparing song" }
        return "$stage · ${(fraction * 100).toInt()}%"
    }

    /**
     * Levels as a winding path of nodes: cleared ones glow, the next one pulses, and locked ones
     * stay blurred until reached.
     */
    private fun addJourney(body: LinearLayout, songs: org.json.JSONArray, next: Int) {
        val width = resources.displayMetrics.widthPixels - dp(40)
        val rowHeight = dp(118)
        val centers = ArrayList<Pair<Float, Float>>()
        fun offset(i: Int) = (kotlin.math.sin(i * 1.15) * .26).toFloat()
        for (i in 0 until songs.length()) centers.add(width / 2f + offset(i) * width to i * rowHeight + rowHeight / 2f)
        val map = FrameLayout(this)
        map.addView(
            object : View(this) {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                private val dashes = android.graphics.DashPathEffect(floatArrayOf(dp(2).toFloat(), dp(10).toFloat()), 0f)
                private val path = android.graphics.Path()

                override fun onDraw(canvas: android.graphics.Canvas) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    for (i in 0 until centers.size - 1) {
                        val (x1, y1) = centers[i]
                        val (x2, y2) = centers[i + 1]
                        path.reset()
                        path.moveTo(x1, y1)
                        path.cubicTo(x1, (y1 + y2) / 2, x2, (y1 + y2) / 2, x2, y2)
                        val reached = next < 0 || i < next
                        paint.pathEffect = if (reached) null else dashes
                        paint.strokeWidth = dp(if (reached) 6 else 4).toFloat()
                        paint.color = if (reached) 0x99A78BFA.toInt() else Palette.STROKE
                        canvas.drawPath(path, paint)
                    }
                }
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        for (i in 0 until songs.length()) addNode(map, i, songs.getJSONObject(i), i == next, centers[i], width, rowHeight)
        body.addView(map, LinearLayout.LayoutParams(-1, rowHeight * songs.length()))
    }

    private fun addNode(
        map: FrameLayout,
        index: Int,
        song: JSONObject,
        current: Boolean,
        center: Pair<Float, Float>,
        width: Int,
        rowHeight: Int,
    ) {
        val unlocked = store.unlocked(index)
        val done = store.completed(song)
        val stars = store.stars(song)
        val title = SongTitle.display(song.optString("title"), unlocked)
        val size = dp(if (current) 84 else 72)
        val (cx, cy) = center
        if (current) {
            val halo = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x55A78BFA)
                }
            }
            map.addView(halo, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (cx - size / 2).toInt()
                topMargin = (cy - size / 2).toInt()
            })
            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    halo,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                    android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, .9f, 0f),
                )
                .apply {
                    duration = 1400
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
        }
        val ring =
            when {
                current -> Palette.ACCENT
                stars == 3 -> Palette.GOLD
                done -> 0xFF7C5CF2.toInt()
                else -> Palette.STROKE
            }
        val node = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.SURFACE)
                setStroke(dp(if (current) 4 else 3), ring)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "Level ${index + 1}, $title" + if (unlocked) "" else ", locked"
            isClickable = true
            isFocusable = true
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) },
            )
            setOnClickListener {
                if (unlocked) prepareSong(song, index)
                else {
                    animate().translationX(dp(6).toFloat()).setDuration(50).withEndAction {
                        animate().translationX(-dp(6).toFloat()).setDuration(80).withEndAction {
                            animate().translationX(0f).setDuration(50)
                        }
                    }
                    Toast.makeText(context, "Clear level $index to unlock", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val disc = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SURFACE_HI) }
            clipToOutline = true
            contentDescription =
                if (unlocked) "${song.optString("title")} album cover"
                else "Blurred locked album cover for level ${index + 1}"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val art = TileArtwork(this, index)
        if (!unlocked) {
            art.alpha = .45f
            if (Build.VERSION.SDK_INT >= 31)
                art.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(12f, 12f, android.graphics.Shader.TileMode.CLAMP))
        }
        disc.addView(art, FrameLayout.LayoutParams(-1, -1))
        disc.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                covers.into(this, song, !unlocked, dp(96))
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        if (!unlocked) disc.addView(ui.icon(Glyph.Kind.LOCK, 0xDDF5F3FA.toInt(), 22), FrameLayout.LayoutParams(-1, -1))
        node.addView(disc, FrameLayout.LayoutParams(-1, -1))
        map.addView(node, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (cx - size / 2).toInt()
            topMargin = (cy - size / 2).toInt()
        })
        // Level number chip on the node's top edge.
        map.addView(
            ui.text("${index + 1}", 11f, if (unlocked) Palette.ON_ACCENT else Palette.MUTED, ui.heavy).apply {
                gravity = Gravity.CENTER
                background = if (unlocked) ui.rounded(ring.takeIf { it != Palette.STROKE } ?: Palette.ACCENT, 10) else ui.rounded(Palette.SURFACE_HI, 10)
                minWidth = dp(24)
                setPadding(dp(6), dp(3), dp(6), dp(3))
            },
            FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = (cx - dp(12)).toInt()
                topMargin = (cy - size / 2 - dp(9)).toInt()
            },
        )
        val badge = FrameLayout(this)
        map.addView(badge, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
            leftMargin = (cx + size / 2 - dp(24)).toInt()
            topMargin = (cy + size / 2 - dp(24)).toInt()
        })
        if (done)
            map.addView(
                ui.stars(stars, 15, 1, Palette.FAINT),
                FrameLayout.LayoutParams(-2, -2).apply {
                    leftMargin = (cx - dp(25)).toInt()
                    topMargin = (cy + size / 2 + dp(2)).toInt()
                },
            )
        // Labels sit on whichever side of the node has more room.
        val gap = dp(16)
        val onRight = cx <= width / 2f
        val labels = ui.column().apply { gravity = if (onRight) Gravity.START else Gravity.END }
        labels.addView(
            ui.oneLine(ui.text(title, 15f, if (unlocked) Palette.TEXT else Palette.MUTED, ui.medium)).apply {
                gravity = if (onRight) Gravity.START else Gravity.END
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        ui.gap(labels, 4)
        val subtitle = ui.oneLine(ui.text("", 12.5f, Palette.MUTED)).apply {
            gravity = if (onRight) Gravity.START else Gravity.END
        }
        labels.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        val labelWidth = if (onRight) width - (cx + size / 2 + gap).toInt() else (cx - size / 2 - gap).toInt()
        map.addView(labels, FrameLayout.LayoutParams(labelWidth, -2).apply {
            leftMargin = if (onRight) (cx + size / 2 + gap).toInt() else 0
            topMargin = (cy - dp(20)).toInt()
        })
        val row = SongRow(index, subtitle, badge)
        rows[song.optString("id")] = row
        bindRow(row, song)
    }

    /** Refreshes the parts of a node that change while songs download. */
    private fun bindRow(row: SongRow, song: JSONObject) {
        val unlocked = store.unlocked(row.index)
        val secs = song.optDouble("duration").toInt()
        val length = if (secs > 150 && store.prefs.getBoolean("shortLevels", true)) "Highlights · ≤2:30"
            else if (secs > 0) String.format(Locale.US, "%d:%02d", secs / 60, secs % 60) else ""
        row.subtitle.text = listOf(song.optString("artist"), length).filter { it.isNotBlank() }.joinToString(" · ")
        row.trailing.removeAllViews()
        if (unlocked && !store.completed(song)) {
            row.trailing.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.ACCENT)
                setStroke(dp(2), Palette.BG)
            }
            row.trailing.addView(ui.icon(Glyph.Kind.PLAY, Palette.ON_ACCENT, 14), FrameLayout.LayoutParams(-1, -1))
        } else row.trailing.background = null
    }

    private fun startPolling() {
        if (polling || !foreground || store.playlist == null || isOriginals) return
        polling = true
        handler.postDelayed(::pollLibrary, 1000)
    }

    private fun pollLibrary() {
        if (!foreground || screen != Screen.HOME || store.playlist == null) {
            polling = false
            return
        }
        val id = store.playlist!!.optString("id")
        io.execute {
            val playlist = library.snapshot(id)
            handler.post {
                polling = false
                if (screen != Screen.HOME || isDestroyed) return@post
                if (store.playlist?.optString("id") != id) {
                    startPolling()
                    return@post
                }
                if (playlist != null && playlist.toString() != store.playlist.toString()) {
                    store.save(playlist)
                    val songs = playlist.getJSONArray("songs")
                    val ids = (0 until songs.length()).map { songs.getJSONObject(it).optString("id") }
                    if (ids.toSet() == rows.keys && ids.size == rows.size) {
                        for (i in ids.indices) rows[ids[i]]?.let { bindRow(it, songs.getJSONObject(i)) }
                        hero?.let { (heroId, button) ->
                            val index = ids.indexOf(heroId)
                            if (index >= 0) button.text = heroLabel(songs.getJSONObject(index))
                        }
                    } else {
                        val y = scroll?.scrollY ?: 0
                        showHome()
                        scroll?.post { scroll?.scrollTo(0, y) }
                    }
                }
                startPolling()
            }
        }
    }

    /**
     * Keeps every playlist's next level and the locked one after it prepared, so play is never
     * held up by a download. Those come first, then the rest of the current playlist's window
     * and downloaded songs whose chart came from an older generator.
     */
    private fun prefetch() {
        val currentId = store.playlist?.optString("id")
        io.execute {
            val online = ArcadePreparation.connected(this)
            val playlists = library.savedPlaylists().filter { it.optString("id") != ORIGINALS }
                .sortedByDescending { it.optString("id") == currentId }
            fun missing(songs: List<JSONObject>) = songs.filter { !store.offline(it) }.map { it.optString("id") }
            val requests = mutableListOf<Triple<String, Boolean, List<String>>>()
            for (playlist in playlists)
                requests += Triple(playlist.optString("id"), true, if (online) missing(store.essential(playlist)) else emptyList())
            playlists.firstOrNull { it.optString("id") == currentId }?.let { playlist ->
                val songs = playlist.getJSONArray("songs")
                val stale = (0 until songs.length()).map { songs.getJSONObject(it) }
                    .filter { store.songFile(it, "mp3").isFile }
                requests += Triple(playlist.optString("id"), false,
                    missing((if (online) store.upcoming(playlist) else emptyList()) + stale))
            }
            handler.post {
                if (isDestroyed) return@post
                for ((id, essential, songs) in requests) {
                    val wanted = songs.filter { it !in attemptedPrefetch }
                    if (wanted.isEmpty()) continue
                    attemptedPrefetch.addAll(wanted)
                    DownloadService.start(this, id, essential = essential)
                }
            }
        }
    }

    // ---------------------------------------------------------------- arcade

    private fun modeColors(mode: Arcade.Mode) =
        if (mode == Arcade.Mode.TIME) intArrayOf(0xFF38BDF8.toInt(), 0xFF6366F1.toInt())
        else intArrayOf(0xFFFB923C.toInt(), 0xFFE11D48.toInt())

    private fun modeGlyph(mode: Arcade.Mode) =
        if (mode == Arcade.Mode.TIME) Glyph.Kind.BOLT else Glyph.Kind.FLAME

    private fun bestLabel(mode: Arcade.Mode): String {
        val best =
            if (mode == Arcade.Mode.TIME)
                Arcade.MINUTES.maxOf { store.prefs.getInt("best.time.$it", 0) }
            else store.prefs.getInt("best.endless", 0)
        return if (best > 0) String.format(Locale.US, "Best %,d", best) else "No runs yet"
    }

    private fun addArcadeCards(body: LinearLayout) {
        ui.fill(body, ui.caption("Arcade").apply { setPadding(dp(4), 0, 0, dp(10)) })
        val cards = ui.row()
        for ((i, mode) in Arcade.Mode.values().withIndex()) {
            if (i > 0) ui.gap(cards, 12)
            val card = ui.column().apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                ui.pressable(
                    this,
                    GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(mode)).apply {
                        cornerRadius = dp(20).toFloat()
                    },
                    20,
                )
                contentDescription = mode.title
                setOnClickListener { showArcade(mode) }
            }
            card.addView(ui.icon(modeGlyph(mode), Palette.TEXT, 28), LinearLayout.LayoutParams(-2, -2))
            ui.gap(card, 18)
            card.addView(ui.text(mode.title, 17f, Palette.TEXT, ui.heavy))
            ui.gap(card, 4)
            card.addView(ui.oneLine(ui.text(bestLabel(mode), 12.5f, 0xDDFFFFFF.toInt(), ui.medium)))
            ui.weight(cards, card)
        }
        ui.fill(body, cards)
    }

    /** Every song from every playlist, plus the built-in songs. */
    private fun arcadePool(): List<Arcade.Entry> {
        val entries = ArrayList<Arcade.Entry>()
        for (playlist in library.savedPlaylists()) {
            val songs = playlist.getJSONArray("songs")
            for (i in 0 until songs.length()) entries.add(Arcade.Entry(playlist.getString("id"), songs.getJSONObject(i)))
        }
        val demo = store.demo()
        val songs = demo.getJSONArray("songs")
        for (i in 0 until songs.length()) entries.add(Arcade.Entry(ORIGINALS, songs.getJSONObject(i)))
        return entries.distinctBy { it.id }
    }

    private fun showArcade(mode: Arcade.Mode) {
        arcadePreparation.start()
        var minutes = store.prefs.getInt("arcade.minutes", 2).takeIf { it in Arcade.MINUTES } ?: 2
        val start = ui.button("Start", Ui.Style.PRIMARY, modeGlyph(mode)) { startArcade(mode, minutes) }
        val body = page(Screen.ARCADE, bar(null) { showHome() }, ui.column().apply { ui.fill(this, start) }, centered = true)
        val medal = ui.icon(modeGlyph(mode), Palette.TEXT, 46).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(mode)).apply {
                shape = GradientDrawable.OVAL
            }
        }
        body.addView(medal, LinearLayout.LayoutParams(dp(104), dp(104)))
        ui.gap(body, 20)
        body.addView(ui.title(mode.title, 30f))
        ui.gap(body, 8)
        body.addView(
            ui.text(
                if (mode == Arcade.Mode.TIME) "Score as much as you can before the clock runs out."
                else "Keep your lives as long as you can. Every song is faster.",
                15f,
                Palette.MUTED,
            ).apply { gravity = Gravity.CENTER },
        )
        ui.gap(body, 28)
        val best = ui.text("", 26f, Palette.GOLD, ui.heavy).apply { gravity = Gravity.CENTER }
        val bestDetail = ui.text("", 13f, Palette.MUTED).apply { gravity = Gravity.CENTER }
        fun refreshBest() {
            val key = if (mode == Arcade.Mode.TIME) "best.time.$minutes" else "best.endless"
            val value = store.prefs.getInt(key, 0)
            best.text = if (value > 0) String.format(Locale.US, "%,d", value) else "—"
            bestDetail.text =
                if (mode == Arcade.Mode.ENDLESS && value > 0) "${store.prefs.getInt("best.endless.songs", 0)} songs survived"
                else if (mode == Arcade.Mode.TIME) "Best in $minutes min" else "Best run"
        }
        if (mode == Arcade.Mode.TIME) {
            body.addView(ui.caption("Duration"))
            ui.gap(body, 10)
            body.addView(
                ui.segmented(Arcade.MINUTES.map { "$it min" }, Arcade.MINUTES.indexOf(minutes), Palette.SURFACE) {
                    minutes = Arcade.MINUTES[it]
                    store.prefs.edit().putInt("arcade.minutes", minutes).apply()
                    refreshBest()
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            ui.gap(body, 20)
        }
        val card = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(ui.icon(Glyph.Kind.STAR, Palette.GOLD, 22))
        ui.gap(card, 6)
        card.addView(best)
        ui.gap(card, 2)
        card.addView(bestDetail)
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        refreshBest()
        ui.gap(body, 20)
        body.addView(ui.text("Random songs from the levels you've reached", 13f, Palette.MUTED))
    }

    private fun startArcade(mode: Arcade.Mode, minutes: Int) {
        if (library.savedPlaylists().isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Import a playlist first?")
                .setMessage(
                    "Arcade modes play random songs from your playlists. Without one, only the 3 built-in songs repeat.",
                )
                .setPositiveButton("Import playlist") { _, _ -> showAddPlaylist() }
                .setNegativeButton("Play anyway") { _, _ -> preloadArcade(mode, minutes) }
                .show()
        } else preloadArcade(mode, minutes)
    }

    private fun preloadArcade(mode: Arcade.Mode, minutes: Int) {
        arcadePreparation.start()
        beginRun(mode, minutes)
    }

    private fun beginRun(mode: Arcade.Mode, minutes: Int) {
        run = Arcade.Run(mode, minutes)
        nextStage()
    }

    private fun nextStage() {
        val r = run ?: return
        val op = ++operation
        // Catalog reads and readiness checks are disk work, including when advancing mid-run.
        io.execute {
            val ready = arcadePool().filter { it.demo || store.offline(it.song) }
            val entry = arcadePreparation.pick(ready, r.recent, kotlin.random.Random.Default)
                ?.also { arcadePreparation.played(it.id) }
            if (entry == null) {
                handler.post { if (operation == op && run === r && !isDestroyed) finishRun() }
                return@execute
            }
            val song = entry.song
            runCatching { if (entry.demo) store.prepareDemo(song) }.onFailure { error ->
                handler.post { if (operation == op && !isDestroyed) { finishRun(); showError(error) } }
                return@execute
            }
            val durationMs = (song.optDouble("duration", 0.0) * 1000).toInt()
            val (start, end) = Arcade.segment(durationMs, kotlin.random.Random.Default)
            handler.post {
                if (operation != op || isDestroyed || run !== r) return@post
                r.remember(entry.id)
                arcadePreparation.start()
                launchGame(song, -1, Stage(start, end))
            }
        }
    }

    private fun stageFinished(session: GameSession, timeLeft: Long) {
        val r = run ?: return
        stopGame()
        r.absorb(session, timeLeft)
        if (session.failed() || (r.mode == Arcade.Mode.TIME && r.timeLeftMs <= 0)) finishRun()
        else nextStage()
    }

    private fun finishRun() {
        val r = run ?: return showHome()
        run = null
        stopGame()
        val previous = store.prefs.getInt(r.bestKey, 0)
        val edit = store.prefs.edit()
        if (r.score > previous) {
            edit.putInt(r.bestKey, r.score)
            if (r.mode == Arcade.Mode.ENDLESS) edit.putInt("best.endless.songs", r.songs)
        }
        edit.apply()
        val xpBefore = store.xp
        val gained = r.score / 60 + r.songs * 15
        store.prefs.edit().putInt("xp", xpBefore + gained).apply()
        store.markPlayedToday()
        val footer = ui.column()
        ui.fill(footer, ui.button("Play again", Ui.Style.PRIMARY, modeGlyph(r.mode)) { startArcade(r.mode, r.minutes) })
        ui.gap(footer, 4)
        ui.fill(footer, ui.button("Home", Ui.Style.GHOST) { showHome() })
        val body = page(Screen.RESULTS, bar(null, Glyph.Kind.CLOSE) { showHome() }, footer, centered = true)
        body.addView(
            ui.icon(modeGlyph(r.mode), Palette.TEXT, 46).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, modeColors(r.mode)).apply {
                    shape = GradientDrawable.OVAL
                }
            },
            LinearLayout.LayoutParams(dp(96), dp(96)),
        )
        ui.gap(body, 18)
        body.addView(ui.title(if (r.mode == Arcade.Mode.TIME) "Time's up!" else "Run over", 30f))
        ui.gap(body, 6)
        body.addView(
            ui.text(
                if (r.mode == Arcade.Mode.TIME) "${r.mode.title} · ${r.minutes} min" else r.mode.title,
                15f,
                Palette.MUTED,
            ),
        )
        resultsScore(body, r.score, previous)
        resultsXp(body, xpBefore, gained)
        ui.gap(body, 14)
        resultsStats(
            body,
            listOf(
                "${r.songs}" to "Songs",
                "${(r.accuracy() * 100).toInt()}%" to "Accuracy",
                "${r.maxCombo}" to "Best combo",
                "${r.misses}" to "Misses",
            ),
        )
        resultsTiming(body)
        if (r.score > previous && r.score > 0) celebrate()
    }

    // ---------------------------------------------------------------- settings

    private val cleanupScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleCleanup(force: Boolean = false) {
        if (!store.prefs.getBoolean("smartStorage", true) || !cleanupScheduled.compareAndSet(false, true)) return
        maintenance.execute {
            try {
                val now = System.currentTimeMillis()
                if (force || now - store.prefs.getLong("lastCleanup", 0) > 21_600_000) {
                    library.pruneUnused(store.prefs.getInt("retentionDays", 30))
                    store.prefs.edit().putLong("lastCleanup", now).apply()
                }
            } finally { cleanupScheduled.set(false) }
        }
    }

    private fun showSettings() {
        val body = page(Screen.SETTINGS, bar("Settings") { showHome() })
        val prefs = store.prefs
        val gameplay = section(body, "Gameplay")
        val speeds = floatArrayOf(.8f, 1f, 1.15f)
        val speed = speeds.indexOfFirst { kotlin.math.abs(it - prefs.getFloat("speed", 1f)) < .01 }
        settingStack(gameplay, "Song speed", "Slower songs are easier") {
            ui.segmented(listOf("0.8×", "1×", "1.15×"), speed.takeIf { it >= 0 } ?: 1) {
                prefs.edit().putFloat("speed", speeds[it]).apply()
            }
        }
        divider(gameplay)
        settingStack(gameplay, "Note speed", "Follows the song's tempo") {
            ui.segmented(listOf("Slow", "Normal", "Fast"), prefs.getInt("noteSpeed", 1)) {
                prefs.edit().putInt("noteSpeed", it).apply()
            }
        }
        divider(gameplay)
        settingStack(gameplay, "Journey level length", "Highlights keep levels under 2½ minutes") {
            ui.segmented(listOf("Highlights", "Full song"), if (prefs.getBoolean("shortLevels", true)) 0 else 1) {
                prefs.edit().putBoolean("shortLevels", it == 0).apply()
            }
        }
        divider(gameplay)
        settingRow(gameplay, "Timing offset", "Adjust if hits feel early or late") {
            ui.stepper(prefs.getInt("timingOffset", 0), -TimingCalibration.RANGE_MS..TimingCalibration.RANGE_MS, 5, ::signedMs) {
                prefs.edit().putInt("timingOffset", it).apply()
            }
        }
        divider(gameplay)
        settingRow(gameplay, "Learn timing from my hits", "Tunes the offset after every song") {
            toggle(prefs.getBoolean("autoTiming", true), "Learn timing from my hits") {
                prefs.edit().putBoolean("autoTiming", it).apply()
            }
        }
        divider(gameplay)
        settingRow(gameplay, "Vibration", null) {
            toggle(prefs.getBoolean("haptics", true), "Vibration") { prefs.edit().putBoolean("haptics", it).apply() }
        }

        val downloads = section(body, "Storage and downloads")
        val stats = ui.text("Checking storage…", 13f, Palette.MUTED).apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
        ui.fill(downloads, stats)
        // Each playlist's share; its own download settings are a tap away.
        val perPlaylist = ui.column()
        ui.fill(downloads, perPlaylist)
        divider(downloads)
        io.execute {
            val songs = library.arcadeSongs()
            val ready = songs.count { store.offline(it.song) }
            val size = android.text.format.Formatter.formatShortFileSize(this, library.downloadedBytes())
            val playlists = library.savedPlaylists().map { playlist ->
                val list = playlist.getJSONArray("songs").let { a -> (0 until a.length()).map(a::getJSONObject) }
                val (count, bytes) = library.usage(list)
                Triple(playlist, "$count of ${list.size} songs", android.text.format.Formatter.formatShortFileSize(this, bytes))
            }
            handler.post {
                if (isDestroyed || screen != Screen.SETTINGS) return@post
                stats.text = "$ready of ${songs.size} songs downloaded · $size used"
                for ((playlist, count, bytes) in playlists) {
                    divider(perPlaylist)
                    val row = ui.row().apply {
                        setPadding(dp(18), dp(14), dp(14), dp(14))
                        ui.pressable(this, null, 20)
                        contentDescription = "Download settings for ${playlist.optString("title")}"
                        setOnClickListener { showPlaylistSettings(playlist.getString("id"), Screen.SETTINGS) }
                    }
                    ui.weight(row, labels(playlist.optString("title"), "$count · $bytes"))
                    row.addView(ui.icon(Glyph.Kind.CHEVRON_RIGHT, Palette.FAINT, 20))
                    ui.fill(perPlaylist, row)
                }
            }
        }
        settingRow(downloads, "Smart storage management", "Remove songs you haven't played recently") {
            toggle(prefs.getBoolean("smartStorage", true), "Smart storage management") {
                prefs.edit().putBoolean("smartStorage", it).apply()
                if (it) scheduleCleanup(force = true)
            }
        }
        divider(downloads)
        settingRow(downloads, "Remove unused songs after", "Playing a song resets its timer") {
            ui.stepper(prefs.getInt("retentionDays", 30), 1..365, 1, { "$it days" }) {
                prefs.edit().putInt("retentionDays", it).apply()
                scheduleCleanup(force = true)
            }
        }
        divider(downloads)
        actionRow(downloads, Glyph.Kind.TRASH, "Clear downloaded songs", "Scores and unlocks are kept", Palette.RED) {
            AlertDialog.Builder(this)
                .setTitle("Clear downloaded songs?")
                .setMessage("Songs will download again when you play them.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    io.execute {
                        library.clearDownloads()
                        handler.post {
                            attemptedPrefetch.clear()
                            Toast.makeText(this, "Downloads cleared", Toast.LENGTH_SHORT).show()
                            if (screen == Screen.SETTINGS) showSettings()
                        }
                    }
                }
                .show()
        }
        divider(downloads)
        actionRow(downloads, Glyph.Kind.REFRESH, "Update downloader", "Try this if downloads stop working", Palette.TEXT) {
            updateDownloader()
        }
        ui.gap(body, 28)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        ui.fill(
            body,
            ui.text("Keyflow ${version ?: ""}".trim(), 12f, Palette.FAINT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    private fun toggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            isChecked = checked
            contentDescription = label
            thumbTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Palette.ACCENT, Palette.MUTED),
                )
            trackTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0x88A78BFA.toInt(), Palette.STROKE),
                )
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun settingRow(card: LinearLayout, title: String, subtitle: String?, control: () -> View) {
        val row = ui.row().apply { setPadding(dp(18), dp(14), dp(14), dp(14)) }
        ui.weight(row, labels(title, subtitle))
        ui.gap(row, 12)
        row.addView(control())
        ui.fill(card, row)
    }

    private fun settingStack(card: LinearLayout, title: String, subtitle: String?, control: () -> View) {
        val stack = ui.column().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
        ui.fill(stack, labels(title, subtitle))
        ui.gap(stack, 12)
        ui.fill(stack, control())
        ui.fill(card, stack)
    }

    private fun actionRow(
        card: LinearLayout,
        glyph: Glyph.Kind,
        title: String,
        subtitle: String,
        color: Int,
        action: () -> Unit,
    ) {
        val row = ui.row().apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            ui.pressable(this, null, 20)
            setOnClickListener { action() }
        }
        ui.weight(row, labels(title, subtitle).apply { (getChildAt(0) as TextView).setTextColor(color) })
        row.addView(ui.icon(glyph, color, 22))
        ui.fill(card, row)
    }

    private fun updateDownloader() {
        val content = ui.column().apply { setPadding(dp(24), dp(12), dp(24), dp(8)) }
        val label = ui.text("Checking for updates…", 14f, Palette.TEXT)
        ui.fill(content, label)
        ui.gap(content, 14)
        ui.fill(content, spinner())
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Update downloader")
                .setView(content)
                .setCancelable(false)
                .create()
        dialog.show()
        io.execute {
            runCatching {
                    library.runtime.updateExtractor { message ->
                        handler.post { if (!isDestroyed) label.text = message }
                    }
                }
                .onSuccess { version ->
                    handler.post {
                        dialog.dismiss()
                        if (!isDestroyed)
                            Toast.makeText(this, "Downloader updated · $version", Toast.LENGTH_LONG)
                                .show()
                    }
                }
                .onFailure { error ->
                    handler.post {
                        dialog.dismiss()
                        if (!isDestroyed) showError(error)
                    }
                }
        }
    }

    // ---------------------------------------------------------------- loading and play

    private fun prepareSong(song: JSONObject, index: Int) {
        arcadePreparation.pause()
        stopGame()
        currentSong = song
        currentIndex = index
        val run = ++operation
        val playlistId = store.playlist!!.optString("id")
        if (song.optBoolean("demo") || store.offline(song)) {
            io.execute {
                runCatching { if (song.optBoolean("demo")) store.prepareDemo(song) }
                    .onSuccess {
                        handler.post { if (operation == run && !isDestroyed) launchGame(song, index) }
                    }
                    .onFailure { error ->
                        handler.post {
                            if (operation == run && !isDestroyed) {
                                showHome()
                                showError(error)
                            }
                        }
                    }
            }
            return
        }
        val body =
            page(
                Screen.LOADING,
                bar(null, Glyph.Kind.CLOSE) {
                    operation++
                    showHome()
                },
                centered = true,
            )
        ui.gap(body, 12)
        val cover = artwork(index, 24)
        cover.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                covers.into(this, song, false, dp(360))
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        body.addView(cover, LinearLayout.LayoutParams(dp(240), dp(240)))
        ui.gap(body, 28)
        body.addView(ui.caption("Level ${index + 1}", Palette.ACCENT))
        ui.gap(body, 8)
        body.addView(
            ui.text(song.optString("title"), 22f, Palette.TEXT, ui.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            },
        )
        ui.gap(body, 4)
        body.addView(ui.oneLine(ui.text(song.optString("artist"), 14f, Palette.MUTED)))
        ui.gap(body, 32)
        val bar = ui.meter(.02f, height = 10)
        body.addView(bar, LinearLayout.LayoutParams(dp(260), -2))
        ui.gap(body, 12)
        val status = ui.text("Preparing song…", 14f, Palette.TEXT, ui.medium)
        body.addView(status)
        DownloadService.start(this, playlistId, song.optString("id"))
        waitForSong(playlistId, song, index, run, status, bar)
    }

    private fun waitForSong(
        playlistId: String,
        song: JSONObject,
        index: Int,
        run: Int,
        status: TextView,
        bar: Ui.Meter,
    ) {
        if (operation != run || isDestroyed) return
        handler.postDelayed(
            {
                if (operation != run || isDestroyed) return@postDelayed
                io.execute {
                    val playlist = library.snapshot(playlistId)
                    val songs = playlist?.optJSONArray("songs")
                    val updated = songs?.let { values ->
                        (0 until values.length())
                            .map { values.getJSONObject(it) }
                            .firstOrNull { it.optString("id") == song.optString("id") }
                    }
                    handler.post {
                        if (operation != run || isDestroyed) return@post
                        if (updated == null) {
                            showHome()
                            showError(IllegalStateException("Song not found. Import the playlist again."))
                            return@post
                        }
                        when {
                            store.offline(updated) -> {
                                store.save(playlist!!)
                                launchGame(updated, index)
                            }
                            updated.optString("status") == "error" -> {
                                store.save(playlist!!)
                                showHome()
                                showError(IllegalStateException("Couldn't prepare this song. Try again when you have an internet connection."))
                            }
                            else -> {
                                val target = downloadFraction(updated)
                                if (target != null && target > bar.fraction)
                                    android.animation.ValueAnimator.ofFloat(bar.fraction, target).apply {
                                        duration = 500
                                        addUpdateListener { bar.fraction = it.animatedValue as Float }
                                        start()
                                    }
                                status.text = downloadStage(updated)
                                waitForSong(playlistId, song, index, run, status, bar)
                            }
                        }
                    }
                }
            },
            600,
        )
    }

    private fun launchGame(song: JSONObject, index: Int, stage: Stage? = null) {
        stopGame()
        currentSong = song
        currentIndex = index
        val op = ++operation
        val r = run
        val seed = random.nextInt()
        val id = song.optString("id")
        library.protectPlayback(id)
        protectedSongId = id
        val previousLayout = store.prefs.getInt("layout.$id", -1)
        io.execute {
            runCatching {
                val chart = store.chart(song)
                val excerpt = if (r == null && stage == null && store.prefs.getBoolean("shortLevels", true)) {
                    PreparedChart.highlight(chart)
                } else null
                val playStage = stage ?: excerpt?.let { Stage((it.start * 1000).toInt(), (it.end * 1000).toInt()) }
                val raw = chart.getJSONArray("notes")
                val list = ArrayList<GameSession.Note>(raw.length())
                var previous = -1.0
                for (i in 0 until raw.length()) {
                    val note = raw.getJSONObject(i)
                    val time = note.getDouble("time")
                    val lane = note.getInt("lane")
                    val kind = note.optString("kind", "tap")
                    val duration = note.optDouble("duration", 0.0)
                    val endLane = note.optInt("endLane", lane)
                    check(time.isFinite() && time >= 0 && time >= previous && lane in 0..3 &&
                        kind in listOf("tap", "hold", "slide") && duration.isFinite() && duration >= 0 &&
                        endLane in 0..3 && (kind == "tap" || duration > 0)) { "This song's chart could not be read. Try preparing it again in Settings." }
                    list.add(GameSession.Note(time, lane, kind, duration, endLane))
                    previous = time
                }
                val playable = if (playStage != null) Arcade.clip(list, playStage.startMs, playStage.endMs, if (r == null) 1.3 else .4) else list
                check(playable.isNotEmpty()) { "This song has no playable notes." }
                val decorated = TileVariety.decorate(playable, r?.variety ?: index, id.hashCode())
                val layout = TileVariety.shuffleLanes(decorated, kotlin.random.Random(seed), previousLayout)
                val session = if (r == null) GameSession(layout.notes)
                    else GameSession(layout.notes, r.lives, r.mode != Arcade.Mode.TIME, r.combo)
                val beats = chart.optJSONArray("beats")?.let { values -> DoubleArray(values.length()) { values.getDouble(it) } }
                    ?: song.optDouble("bpm", 0.0).takeIf { it > 0 }?.let { bpm ->
                        val first = list.firstOrNull()?.time ?: 0.0
                        DoubleArray(((chart.optDouble("duration", 0.0) - first) * bpm / 60).toInt().coerceAtLeast(0)) {
                            first + it * 60 / bpm
                        }
                    } ?: DoubleArray(0)
                val rhythm = Rhythm(beats, chart.optInt("barPhase", 0), chart.optDouble("bpm", 0.0).takeIf { it > 0 } ?: song.optDouble("bpm", 0.0))
                val path = store.songFile(song, if (song.optBoolean("demo")) "wav" else "mp3").path
                handler.post {
                    if (operation != op || isDestroyed || run !== r) return@post
                    store.prefs.edit().putInt("layout.$id", layout.key).apply()
                    val prepared = MediaPlayer()
                    player = prepared
                    runCatching {
                        prepared.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        prepared.setDataSource(path)
                        prepared.setOnPreparedListener {
                            if (player !== prepared) return@setOnPreparedListener
                            if (playStage != null && playStage.startMs > 0) {
                                prepared.setOnSeekCompleteListener {
                                    if (player === prepared) {
                                        prepared.setOnSeekCompleteListener(null)
                                        showGame(song, session, MediaClock(prepared), playStage, rhythm)
                                    }
                                }
                                prepared.seekTo(playStage.startMs)
                            } else showGame(song, session, MediaClock(prepared), playStage, rhythm)
                        }
                        prepared.setOnErrorListener { _, _, _ ->
                            if (player === prepared) {
                                stopGame(); run = null; showHome()
                                showError(IllegalStateException("This song couldn't play. Try another song."))
                            }
                            true
                        }
                        prepared.prepareAsync()
                    }.onFailure { error -> stopGame(); run = null; showHome(); showError(error) }
                }
            }.onFailure { error -> handler.post {
                if (operation == op && !isDestroyed) { stopGame(); run = null; showHome(); showError(error) }
            } }
        }
    }

    private class Rhythm(val beats: DoubleArray, val barPhase: Int, val bpm: Double)

    /** Long silent or tile-free outros fade out a few seconds after the last tile instead. */
    private fun outroEnd(session: GameSession, clock: GameClock): Int {
        val last = session.notes.maxOfOrNull { it.time + it.duration } ?: return 0
        val end = (last * 1000).toInt() + 3500
        return if (clock.durationMs > 0 && end < clock.durationMs - 1500) end else 0
    }

    private fun showGame(song: JSONObject, session: GameSession, clock: GameClock, stage: Stage?, rhythm: Rhythm) {
        screen = Screen.GAME
        rows.clear()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        applyInsets(root, false)
        val prefs = store.prefs
        val r = run
        val caption =
            when {
                r == null -> if (stage != null) "Highlights · up to 2½ minutes" else ""
                r.mode == Arcade.Mode.ENDLESS ->
                    "Song ${r.songs + 1}  ·  Speed ×" + String.format(Locale.US, "%.2f", r.speed ?: 1f)
                else -> "Song ${r.songs + 1}  ·  ${song.optString("title")}"
            }
        val speed = r?.speed ?: prefs.getFloat("speed", 1f)
        lateinit var view: GameView
        view =
            GameView(
                this,
                session,
                clock,
                GameView.Options(
                    speed = speed,
                    offsetMs = prefs.getInt("timingOffset", 0),
                    travel = r?.travel ?: GameView.travelForTempo(GameView.TRAVEL[prefs.getInt("noteSpeed", 1).coerceIn(0, 2)], rhythm.bpm),
                    haptics = prefs.getBoolean("haptics", true),
                    hint = r == null && !prefs.getBoolean("tutorialSeen", false),
                    introduce = newKinds(session),
                    startMs = stage?.startMs ?: 0,
                    endMs = stage?.endMs?.takeIf { it > 0 } ?: outroEnd(session, clock),
                    timeLimitMs = if (r?.mode == Arcade.Mode.TIME) r.timeLeftMs else 0,
                    baseScore = r?.score ?: 0,
                    caption = caption,
                    segmentDone = if (r != null) "Next song!" else "Song complete!",
                    beats = rhythm.beats,
                    barPhase = rhythm.barPhase,
                ),
                object : GameView.Listener {
                    override fun finished(session: GameSession) {
                        handler.post {
                            learnTiming(session, speed)
                            if (run != null) stageFinished(session, view.timeLeftMs)
                            else finishGame(song, session)
                        }
                    }

                    override fun pauseRequested() {
                        showPause()
                    }
                },
            )
        game = view
        store.markPlayed(song)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (!foreground) showPause()
    }

    /** Moves the timing offset toward where this run's hits landed; see [TimingCalibration]. */
    private fun learnTiming(session: GameSession, speed: Float) {
        timingNote = null
        val prefs = store.prefs
        if (!prefs.getBoolean("autoTiming", true)) return
        val errors = session.timingErrorsMs()
        val lean = TimingCalibration.lean(errors, speed) ?: return
        val before = prefs.getInt("timingOffset", 0)
        val after = TimingCalibration.adjust(before, lean)
        android.util.Log.i("Keyflow", "Timing: ${errors.size} hits leaned ${lean.roundToInt()} ms; offset $before -> $after ms")
        if (after == before) return
        prefs.edit().putInt("timingOffset", after).apply()
        val side = if (lean > 0) "late" else "early"
        timingNote = "Hits leaned ${abs(lean).roundToInt()} ms $side · timing offset now ${signedMs(after)}"
    }

    private fun signedMs(ms: Int) = if (ms > 0) "+$ms ms" else "$ms ms"

    /** Tile kinds in this level the player has never seen; each is introduced once. */
    private fun newKinds(session: GameSession): List<String> {
        val notes = session.notes
        val present = ArrayList<String>()
        if (notes.any { it.kind == GameSession.GOLD }) present.add(GameSession.GOLD)
        if ((1 until notes.size).any { abs(notes[it].time - notes[it - 1].time) < .001 && notes[it].kind != GameSession.AVOID && notes[it - 1].kind != GameSession.AVOID })
            present.add(CHORD)
        if (notes.any { it.kind == GameSession.AVOID }) present.add(GameSession.AVOID)
        val fresh = present.filter { !store.prefs.getBoolean("seen.$it", false) }
        if (fresh.isNotEmpty())
            store.prefs.edit().apply { fresh.forEach { putBoolean("seen.$it", true) } }.apply()
        return fresh
    }

    private fun showPause() {
        val view = game ?: return
        if (pauseOverlay != null) return
        view.pause()
        val root = view.parent as? ViewGroup ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xD90D0C12.toInt())
            isClickable = true
        }
        val panel = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        panel.addView(ui.title("Paused", 30f), LinearLayout.LayoutParams(-2, -2))
        ui.gap(panel, 8)
        panel.addView(
            ui.oneLine(ui.text(currentSong?.optString("title") ?: "", 15f, Palette.MUTED)).apply {
                gravity = Gravity.CENTER
            },
        )
        ui.gap(panel, 32)
        fun add(button: View) {
            panel.addView(button, LinearLayout.LayoutParams(dp(240), -2))
            ui.gap(panel, 10)
        }
        add(ui.button("Resume", Ui.Style.PRIMARY, Glyph.Kind.PLAY) { hidePause() })
        if (run == null) {
            add(ui.button("Restart", Ui.Style.SECONDARY, Glyph.Kind.RETRY) {
                currentSong?.let { prepareSong(it, currentIndex) }
            })
            add(ui.button("Quit", Ui.Style.GHOST) {
                stopGame()
                showHome()
            })
        } else
            add(ui.button("End run", Ui.Style.SECONDARY) {
                val session = view.session
                val left = view.timeLeftMs
                stopGame()
                run?.absorb(session, left)
                finishRun()
            })
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        pauseOverlay = overlay
    }

    private fun hidePause() {
        val overlay = pauseOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        pauseOverlay = null
        game?.resume()
    }

    private fun finishGame(song: JSONObject, session: GameSession) {
        stopGame()
        val previousBest = store.score(song)
        val xpBefore = store.xp
        store.record(song, session)
        // The window of prepared levels moves on as soon as a level is cleared.
        prefetch()
        val gained = store.award(session)
        store.markPlayedToday()
        store.prefs.edit().putBoolean("tutorialSeen", true).apply()
        val passed = session.passed()
        val hasNext = currentIndex + 1 < store.songs().length()
        val footer = ui.column()
        if (passed && hasNext) {
            ui.fill(
                footer,
                ui.button("Next level", Ui.Style.PRIMARY, Glyph.Kind.PLAY) {
                    prepareSong(store.songs().getJSONObject(currentIndex + 1), currentIndex + 1)
                },
            )
            ui.gap(footer, 8)
            ui.fill(footer, ui.button("Retry", Ui.Style.SECONDARY, Glyph.Kind.RETRY) {
                prepareSong(song, currentIndex)
            })
        } else
            ui.fill(footer, ui.button("Retry", Ui.Style.PRIMARY, Glyph.Kind.RETRY) {
                prepareSong(song, currentIndex)
            })
        ui.gap(footer, 4)
        ui.fill(footer, ui.button("Levels", Ui.Style.GHOST) { showHome() })
        val body = page(Screen.RESULTS, bar(null, Glyph.Kind.CLOSE) { showHome() }, footer, centered = true)
        resultsHeader(
            body,
            session.grade(),
            session.stars(),
            when {
                passed -> "Level cleared"
                session.failed() -> "Out of lives"
                else -> "Almost there"
            },
            song.optString("title"),
        )
        if (!passed && !session.failed()) {
            ui.gap(body, 14)
            val hit = if (session.scored == 0) 0 else session.hits * 100 / session.scored
            body.addView(
                ui.text("Hit 75% of notes to clear · you hit $hit%", 13f, Palette.TEXT).apply {
                    background = ui.rounded(Palette.SURFACE_HI, 14)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                },
            )
        }
        resultsScore(body, session.score, previousBest)
        resultsXp(body, xpBefore, gained)
        ui.gap(body, 14)
        resultsStats(
            body,
            listOf(
                "${(session.accuracy() * 100).toInt()}%" to "Accuracy",
                "${session.maxCombo}" to "Best combo",
                "${session.perfect}" to "Perfect",
                "${session.misses}" to "Misses",
            ),
        )
        resultsTiming(body)
        if (passed) celebrate()
    }

    /** Grade medallion, stars popping in one by one, and the headline. */
    private fun resultsHeader(body: LinearLayout, grade: String, stars: Int, title: String, subtitle: String) {
        val colors =
            when (grade) {
                "S" -> intArrayOf(0xFFFDE68A.toInt(), 0xFFF59E0B.toInt())
                "A" -> intArrayOf(0xFF99F6E4.toInt(), 0xFF14B8A6.toInt())
                "B" -> intArrayOf(0xFFC4B5FD.toInt(), 0xFF7C5CF2.toInt())
                "C", "D" -> intArrayOf(0xFFBAE6FD.toInt(), 0xFF3B82F6.toInt())
                else -> intArrayOf(0xFF4B4560.toInt(), 0xFF2C2939.toInt())
            }
        val medal =
            ui.text(grade, 46f, if (grade == "F") Palette.MUTED else 0xFF1A1030.toInt(), ui.heavy).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(4), 0x33FFFFFF)
                }
                contentDescription = "Grade $grade"
                scaleX = 0f
                scaleY = 0f
            }
        body.addView(medal, LinearLayout.LayoutParams(dp(104), dp(104)))
        medal.animate().scaleX(1f).scaleY(1f).setStartDelay(80).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        ui.gap(body, 16)
        val row = ui.row().apply { contentDescription = "$stars of 3 stars" }
        for (i in 1..3) {
            val star = ui.icon(Glyph.Kind.STAR, if (i <= stars) Palette.GOLD else Palette.STROKE, 40)
            if (i > 1) ui.gap(row, 8)
            row.addView(star)
            if (i <= stars) {
                star.scaleX = 0f
                star.scaleY = 0f
                star.animate().scaleX(1f).scaleY(1f).setStartDelay(380L + i * 200).setDuration(360)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
        }
        body.addView(row)
        ui.gap(body, 16)
        body.addView(ui.title(title, 30f))
        ui.gap(body, 6)
        body.addView(ui.oneLine(ui.text(subtitle, 15f, Palette.MUTED)))
    }

    /** Score counts up; a gold ribbon marks a new best. */
    private fun resultsScore(body: LinearLayout, score: Int, previousBest: Int) {
        ui.gap(body, 26)
        val label = ui.text("0", 50f, Palette.TEXT, ui.heavy)
        body.addView(label)
        android.animation.ValueAnimator.ofInt(0, score).apply {
            duration = 900
            startDelay = 300
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { label.text = String.format(Locale.US, "%,d", it.animatedValue as Int) }
            start()
        }
        ui.gap(body, 8)
        body.addView(
            if (score > previousBest && previousBest > 0)
                ui.text("NEW BEST", 12f, 0xFF1A1030.toInt(), ui.heavy).apply {
                    letterSpacing = .12f
                    background = ui.rounded(Palette.GOLD, 12)
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                }
            else if (previousBest > 0)
                ui.text(String.format(Locale.US, "Best %,d", previousBest), 13f, Palette.MUTED)
            else ui.caption("Score"),
        )
    }

    /** Experience bar that fills with what this run earned, flagging level-ups. */
    private fun resultsXp(body: LinearLayout, before: Int, gained: Int) {
        ui.gap(body, 24)
        val card = ui.card()
        val head = ui.row()
        val levelBefore = LibraryStore.level(before)
        val levelAfter = LibraryStore.level(before + gained)
        val levelLabel = ui.text("Level $levelBefore", 14f, Palette.TEXT, ui.medium)
        ui.weight(head, levelLabel)
        head.addView(ui.text("+$gained XP", 14f, Palette.GOLD, ui.heavy))
        ui.fill(card, head)
        ui.gap(card, 10)
        val meter = ui.meter(LibraryStore.levelFraction(before), Palette.GOLD, 10)
        ui.fill(card, meter)
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        val target = if (levelAfter > levelBefore) 1f else LibraryStore.levelFraction(before + gained)
        android.animation.ValueAnimator.ofFloat(meter.fraction, target).apply {
            duration = 800
            startDelay = 700
            addUpdateListener { meter.fraction = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (levelAfter <= levelBefore) return
                    meter.fraction = LibraryStore.levelFraction(before + gained)
                    levelLabel.text = "Level $levelAfter  ·  LEVEL UP!"
                    levelLabel.setTextColor(Palette.GOLD)
                    levelLabel.animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).withEndAction {
                        levelLabel.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    }
                }
            })
            start()
        }
    }

    private fun resultsStats(body: LinearLayout, values: List<Pair<String, String>>) {
        val stats = ui.row().apply {
            background = ui.rounded(Palette.SURFACE, 20)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        for ((value, label) in values)
            ui.weight(
                stats,
                ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(ui.text(value, 20f, Palette.TEXT, ui.medium), LinearLayout.LayoutParams(-2, -2))
                    ui.gap(this, 4)
                    addView(ui.text(label, 11.5f, Palette.MUTED), LinearLayout.LayoutParams(-2, -2))
                },
            )
        body.addView(stats, LinearLayout.LayoutParams(-1, -2))
    }

    private fun resultsTiming(body: LinearLayout) {
        val note = timingNote ?: return
        timingNote = null
        ui.gap(body, 10)
        body.addView(ui.text(note, 12.5f, Palette.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun celebrate() {
        findViewById<FrameLayout>(android.R.id.content)
            .addView(Confetti(this), FrameLayout.LayoutParams(-1, -1))
    }

    private fun stopGame() {
        protectedSongId?.let(library::releasePlayback)
        protectedSongId = null
        game?.stop()
        game = null
        player?.release()
        player = null
        pauseOverlay = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.GAME -> if (pauseOverlay != null) hidePause() else showPause()
            Screen.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
            Screen.PLAYLIST -> leavePlaylistSettings()
            Screen.IMPORT -> {
                operation++
                cancelImport()
                showAddPlaylist()
            }
            else ->
                if (store.playlist == null) @Suppress("DEPRECATION") super.onBackPressed()
                else {
                    operation++
                    stopGame()
                    showHome()
                }
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        arcadePreparation.pause()
        if (game != null) showPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (run != null || screen == Screen.ARCADE) arcadePreparation.start()
        else arcadePreparation.pause()
        scheduleCleanup()
        if (game != null) showPause() else if (screen == Screen.HOME) startPolling()
    }

    override fun onDestroy() {
        operation++
        cancelImport()
        stopGame()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        maintenance.shutdownNow()
        arcadePreparation.close()
        covers.shutdown()
        super.onDestroy()
    }

    private fun showError(error: Throwable) {
        if (isDestroyed) return
        val message =
            if (
                error is java.net.ConnectException ||
                    error is java.net.SocketTimeoutException ||
                    error is java.net.UnknownHostException
            )
                "Couldn't connect. Check your internet connection and try again."
            else error.message ?: "Something went wrong. Please try again."
        AlertDialog.Builder(this)
            .setTitle("Something went wrong")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val ORIGINALS = "keyflow-originals"

        /** Song states that mean a download is underway or waiting its turn. */
        private val BUSY = setOf("caching", "queued")
        private const val CHORD = "chord"
    }
}
