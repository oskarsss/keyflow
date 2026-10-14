package com.keyflow.pianotiles

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val images = Executors.newSingleThreadExecutor()
    private lateinit var store: LibraryStore
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var playlistInput: EditText
    private lateinit var importStatus: TextView
    private lateinit var libraryStatus: TextView
    private var page = ""
    private var pendingUrl = ""
    private var operation = 0
    private var activeImport: String? = null
    private var foreground = true
    private var polling = false
    private val attemptedPrefetch = mutableSetOf<String>()
    private var player: MediaPlayer? = null
    private var game: GameView? = null
    private var currentSong: JSONObject? = null
    private var currentIndex = 0
    private var pauseDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (intent.action == Intent.ACTION_SEND) {
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showOnboarding()
        } else if (store.playlist == null) showOnboarding() else showLibrary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            stopGame()
            cancelImport()
            operation++
            pendingUrl = extractPlaylist(intent.getStringExtra(Intent.EXTRA_TEXT))
            showOnboarding()
        }
    }

    private fun extractPlaylist(value: String?): String {
        val text = value?.trim() ?: return ""
        return Regex("https://(?:music\\.|www\\.)?youtube\\.com/[^\\s]+").find(text)?.value ?: text
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun background(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun outlined(color: Int, border: Int, radius: Int) =
        background(color, radius).apply { setStroke(dp(1), border) }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size.toFloat()
            setTextColor(color)
            fontFeatureSettings = "kern"
            setLineSpacing(dp(3).toFloat(), 1f)
            typeface =
                Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun add(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun weight(parent: LinearLayout, view: View) {
        parent.addView(view, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun applyInsets(view: View, keyboard: Boolean) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types =
                    WindowInsets.Type.systemBars() or if (keyboard) WindowInsets.Type.ime() else 0
                val safe = insets.getInsets(types)
                target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            insets
        }
    }

    private fun page(name: String) {
        page = name
        root = column().apply { setBackgroundColor(BG) }
        applyInsets(root, true)
        setContentView(root)
        scroll =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                isVerticalScrollBarEnabled = false
            }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(24), dp(18), dp(24), dp(28)) }
        scroll.addView(body)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) =
        text(label, 15, if (primary) BG else TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(17), dp(16), dp(17))
            minHeight = dp(54)
            background =
                if (primary) background(PURPLE, 16) else outlined(CARD, 0xFF383247.toInt(), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun chip(label: String, color: Int) =
        text(label, 10, color, true).apply {
            letterSpacing = .08f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = background((color and 0xFFFFFF) or 0x16000000, 8)
        }

    private fun toolbar(right: String, action: () -> Unit) {
        val bar = row()
        weight(bar, text("▥  keyflow", 22, TEXT, true).apply { letterSpacing = -.035f })
        bar.addView(
            text(right, 14, PURPLE, true).apply {
                setPadding(dp(12), dp(12), 0, dp(12))
                minHeight = dp(48)
                contentDescription = right
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            },
        )
        add(body, bar)
        space(body, 24)
    }

    private fun input(hint: String, value: String) =
        EditText(this).apply {
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            setSingleLine(true)
            this.hint = hint
            setText(value)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            minHeight = dp(54)
            background = outlined(CARD, 0xFF3B354E.toInt(), 14)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

    private fun showOnboarding() {
        page("onboarding")
        toolbar(if (store.playlist == null) "Downloads" else "My journey") {
            if (store.playlist == null) showDownloaderSettings() else showLibrary()
        }
        add(body, text("YOUR MUSIC. YOUR MOMENT.", 10, MINT, true).apply { letterSpacing = .14f })
        space(body, 14)
        add(body, text("Turn your playlist\ninto play.", 38, TEXT, true))
        space(body, 12)
        add(
            body,
            text("A new rhythm for the songs you love.\nTap the tiles. Find your flow.", 15, MUTED),
        )
        space(body, 24)
        val hero = FrameLayout(this)
        hero.addView(TileArtwork(this, 0), FrameLayout.LayoutParams(-1, -1))
        hero.addView(
            chip("4 LANES  ·  ENDLESS FLOW", TEXT),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
                setMargins(dp(16), 0, 0, dp(16))
            },
        )
        body.addView(hero, LinearLayout.LayoutParams(-1, dp(190)))
        space(body, 24)
        add(body, text("Start with your YouTube Music playlist", 14, TEXT, true))
        space(body, 10)
        playlistInput =
            input("Paste a playlist link", pendingUrl).apply {
                contentDescription = "YouTube Music playlist link"
            }
        add(body, playlistInput)
        space(body, 12)
        add(body, button("Create my journey  →", true, ::beginImport))
        space(body, 10)
        add(
            body,
            button("Try the original piano collection", false) {
                runCatching {
                        operation++
                        store.save(store.demo())
                        showLibrary()
                    }
                    .onFailure(::showError)
            },
        )
        space(body, 18)
        add(
            body,
            text(
                    "Share a public or unlisted playlist from YouTube Music. Your first songs are saved for offline play.",
                    12,
                    MUTED,
                )
                .apply { gravity = Gravity.CENTER },
        )
    }

    private fun showDownloaderSettings() {
        AlertDialog.Builder(this)
            .setTitle("Built-in music downloads")
            .setMessage(
                "Playlist imports, audio downloads, and level creation run directly on your phone. No computer or server is needed.\n\nThe download engine can be updated independently when YouTube changes.",
            )
            .setPositiveButton("Update downloader") { _, _ -> updateDownloader() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateDownloader() {
        val label =
            text("Checking for updates…", 14, TEXT).apply {
                setPadding(dp(24), dp(16), dp(24), dp(16))
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Update downloader")
                .setView(label)
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

    private fun beginImport() {
        pendingUrl = playlistInput.text.toString().trim()
        val uri = runCatching { URI(pendingUrl) }.getOrNull()
        if (
            uri == null ||
                uri.scheme != "https" ||
                uri.host !in listOf("music.youtube.com", "www.youtube.com", "youtube.com") ||
                uri.rawQuery?.contains("list=") != true
        ) {
            playlistInput.error = "Paste a YouTube Music playlist link with list=…"
            return
        }
        importPlaylist()
    }

    private val library: OnDeviceLibrary
        get() = OnDeviceLibrary.get(this)

    private fun cancelImport() {
        activeImport?.let { library.runtime.cancel(it) }
        activeImport = null
    }

    private fun importPlaylist() {
        cancelImport()
        val job = "import-${java.util.UUID.randomUUID()}"
        activeImport = job
        val run = ++operation
        page("import")
        toolbar("Cancel") {
            operation++
            cancelImport()
            showOnboarding()
        }
        space(body, 36)
        add(body, text("A playlist.\nA new journey.", 34, TEXT, true))
        space(body, 24)
        body.addView(TileArtwork(this, 1), LinearLayout.LayoutParams(-1, dp(230)))
        space(body, 24)
        importStatus = text("Reading your playlist…", 17, PURPLE, true)
        add(body, importStatus)
        space(body, 12)
        add(
            body,
            text(
                "Your song order becomes your level path. We'll prepare the first three songs while you explore.",
                14,
                MUTED,
            ),
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20)
        val url = pendingUrl
        io.execute {
            runCatching {
                library.importPlaylist(url, job) { message ->
                    handler.post {
                        if (operation == run && !isDestroyed) importStatus.text = message
                    }
                }
            }
                .onSuccess { playlist ->
                    handler.post {
                        if (operation == run && !isDestroyed) {
                            activeImport = null
                            store.save(playlist)
                            showLibrary()
                        }
                    }
                }
                .onFailure { error ->
                    handler.post {
                        if (operation == run && !isDestroyed) {
                            activeImport = null
                            showOnboarding()
                            showError(error)
                        }
                    }
                }
        }
    }

    private fun showLibrary() {
        if (isDestroyed || store.playlist == null) return
        library.bootstrap(store.playlist!!)
        page("library")
        toolbar("＋ Playlist", ::showOnboarding)
        add(body, button("Play & download settings", false, ::showSettings))
        space(body, 18)
        val heading = row()
        weight(heading, text("Your journey", 30, TEXT, true))
        heading.addView(
            chip(
                if (store.playlist!!.optString("id") == "keyflow-originals") "ORIGINALS"
                else "YOUR MIX",
                MINT,
            ),
        )
        add(body, heading)
        space(body, 8)
        add(body, text(store.playlist!!.optString("title"), 15, MUTED))
        space(body, 24)
        val total = store.songs().length()
        val completed = store.completedCount()
        val percent = if (total == 0) 0 else completed * 100 / total
        val progress =
            column().apply {
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = outlined(0xFF242033.toInt(), 0xFF39304F.toInt(), 20)
            }
        val header = row()
        weight(header, text("PLAYLIST PROGRESS", 10, PURPLE, true))
        header.addView(text("$percent%", 24, TEXT, true))
        add(progress, header)
        space(progress, 14)
        add(progress, progressBar(percent))
        space(progress, 12)
        add(progress, text("$completed of $total songs completed", 13, MUTED))
        add(body, progress)
        space(body, 22)
        var earned = 0
        var best = 0
        for (i in 0 until total) {
            val song = store.songs().getJSONObject(i)
            earned += store.stars(song)
            best += store.score(song)
        }
        val stats = row()
        weight(stats, stat("✦  $earned", "Stars earned"))
        weight(stats, stat(String.format(Locale.US, "%,d", best), "Total best score"))
        add(body, stats)
        space(body, 28)
        val levels = row()
        weight(levels, text("The next note", 19, TEXT, true))
        levels.addView(text("$total LEVELS", 10, MUTED, true))
        add(body, levels)
        space(body, 8)
        libraryStatus = text("Complete a song to unlock the next.", 12, MUTED)
        add(body, libraryStatus)
        space(body, 16)
        for (i in 0 until total) addSong(i, store.songs().getJSONObject(i))
        space(body, 12)
        add(body, button("Play settings", false, ::showSettings))
        space(body, 10)
        add(body, button("Built-in downloader", false, ::showDownloaderSettings))
        space(body, 16)
        add(
            body,
            text("Made for your music. Played at your pace.", 11, MUTED).apply {
                gravity = Gravity.CENTER
            },
        )
        startPolling()
        prefetch()
    }

    private fun stat(value: String, label: String) =
        column().apply {
            add(this, text(value, 22, TEXT, true))
            space(this, 4)
            add(this, text(label, 11, MUTED))
        }

    private fun progressBar(percent: Int): View {
        val bar =
            FrameLayout(this).apply {
                background = background(0xFF3A324B.toInt(), 8)
                minimumHeight = dp(7)
            }
        val fill = View(this).apply { background = background(PURPLE, 8) }
        bar.addView(fill, FrameLayout.LayoutParams(0, dp(7)))
        bar.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val desired = (r - l) * percent / 100
            if (fill.layoutParams.width != desired)
                fill.layoutParams = fill.layoutParams.apply { width = desired }
        }
        return bar
    }

    private fun stars(count: Int) = (1..3).joinToString(" ") { if (it <= count) "★" else "☆" }

    private fun addSong(index: Int, song: JSONObject) {
        val unlocked = store.unlocked(index)
        val done = store.completed(song)
        val card =
            column().apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background =
                    outlined(
                        CARD,
                        if (unlocked && !done) 0xFF524264.toInt() else 0xFF2D293D.toInt(),
                        18,
                    )
            }
        val cover =
            FrameLayout(this).apply {
                background = background(0xFF302843.toInt(), 12)
                clipToOutline = true
                contentDescription =
                    if (unlocked) "${song.optString("title")} album cover"
                    else "Blurred locked album cover for ${song.optString("title")}"
            }
        cover.addView(
            TileArtwork(this, index).apply { concealPreview(this, !unlocked) },
            FrameLayout.LayoutParams(-1, -1),
        )
        card.addView(cover, LinearLayout.LayoutParams(-1, dp(172)))
        loadCover(cover, song, !unlocked)
        cover.addView(
            View(this).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            if (unlocked) 0x00101019 else 0x55101019,
                            if (unlocked) 0x55101019 else 0xAA101019.toInt(),
                        ),
                    )
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        cover.addView(
            chip(
                if (unlocked) {
                    if (done) "✓  COMPLETED" else "♪  ALBUM COVER"
                } else "○  LOCKED PREVIEW",
                if (unlocked) MINT else TEXT,
            ),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
                setMargins(dp(12), 0, dp(12), dp(12))
            },
        )
        space(card, 14)
        val top = row()
        val info = column().apply { setPadding(0, 0, dp(5), 0) }
        add(
            info,
            text(
                    String.format(Locale.US, "LEVEL %02d", index + 1),
                    9,
                    if (unlocked) PURPLE else MUTED,
                    true,
                )
                .apply { letterSpacing = .1f },
        )
        space(info, 4)
        add(
            info,
            text(song.optString("title"), 16, TEXT, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        space(info, 3)
        add(
            info,
            text(song.optString("artist"), 11, MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        weight(top, info)
        top.addView(
            text(
                if (done) "✓" else if (unlocked) "♪" else "○",
                21,
                if (done) MINT else if (unlocked) PURPLE else MUTED,
                true,
            ),
        )
        add(card, top)
        space(card, 12)
        val secs = song.optDouble("duration").toInt()
        val status =
            when {
                store.offline(song) -> "Offline ready"
                song.optString("status") == "caching" -> "Preparing audio…"
                song.optString("status") == "error" -> "Retry download"
                else -> "Download to play"
            }
        val detail = row()
        weight(
            detail,
            text(
                (if (secs > 0) String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
                else "Full song") + "  ·  " + status,
                10,
                MUTED,
            ),
        )
        detail.addView(
            text(
                stars(store.stars(song)),
                13,
                if (done) 0xFFE9C77F.toInt() else 0xFF555063.toInt(),
                true,
            ),
        )
        add(card, detail)
        space(card, 12)
        add(
            card,
            button(
                    if (done) "Play again  ↗"
                    else if (unlocked) "Play level  →" else "Complete level $index to unlock",
                    unlocked && !done,
                ) {
                    if (unlocked) prepareSong(song, index)
                }
                .apply {
                    textSize = 12f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    minHeight = dp(46)
                    isEnabled = unlocked
                    if (!unlocked) setTextColor(MUTED)
                },
        )
        add(body, card)
        space(body, 12)
    }

    private fun concealPreview(view: View, locked: Boolean) {
        if (!locked) return
        view.alpha = .48f
        if (Build.VERSION.SDK_INT >= 31)
            view.setRenderEffect(
                RenderEffect.createBlurEffect(
                    dp(12).toFloat(),
                    dp(12).toFloat(),
                    Shader.TileMode.CLAMP,
                ),
            )
    }

    private fun loadCover(frame: FrameLayout, song: JSONObject, locked: Boolean) {
        if (song.optBoolean("demo")) return
        images.execute {
            runCatching {
                val file = store.songFile(song, "jpg")
                if (!file.exists()) {
                    val url = song.optString("thumbnail")
                    if (!url.startsWith("https://")) return@execute
                    val connection = URI(url).toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    val temporary = java.io.File(file.path + ".cover-part")
                    try {
                        connection.inputStream.use { input ->
                            temporary.outputStream().use { input.copyTo(it) }
                        }
                        java.nio.file.Files.move(
                            temporary.toPath(),
                            file.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        connection.disconnect()
                        temporary.delete()
                    }
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                val maximum = if (locked && Build.VERSION.SDK_INT < 31) 24 else 720
                while (options.outWidth / options.inSampleSize > maximum) options.inSampleSize *= 2
                val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return@execute
                handler.post {
                    if (!isDestroyed)
                        frame.addView(
                            ImageView(this).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setImageBitmap(bitmap)
                                concealPreview(this, locked)
                            },
                            1,
                            FrameLayout.LayoutParams(-1, -1),
                        )
                }
            }
        }
    }

    private fun startPolling() {
        if (
            polling ||
                !foreground ||
                store.playlist == null ||
                store.playlist!!.optString("id") == "keyflow-originals"
        )
            return
        polling = true
        handler.postDelayed(::pollLibrary, 1000)
    }

    private fun pollLibrary() {
        if (!foreground || page != "library" || store.playlist == null) {
            polling = false
            return
        }
        val id = store.playlist!!.optString("id")
        io.execute {
            val playlist = library.snapshot(id)
            handler.post {
                polling = false
                if (page != "library" || isDestroyed || store.playlist?.optString("id") != id)
                    return@post
                if (playlist != null && playlist.toString() != store.playlist.toString()) {
                    val y = scroll.scrollY
                    store.save(playlist)
                    showLibrary()
                    scroll.post { scroll.scrollTo(0, y) }
                }
                startPolling()
            }
        }
    }

    private fun prefetch() {
        val playlist = store.playlist ?: return
        val id = playlist.optString("id")
        if (id == "keyflow-originals") return
        val count = store.prefs.getInt("preload.$id", playlist.optInt("preloadCount", 3))
        val songs =
            (0 until minOf(count, store.songs().length()))
                .map { store.songs().getJSONObject(it) }
                .filter { !store.offline(it) && it.optString("id") !in attemptedPrefetch }
        if (songs.isEmpty()) return
        attemptedPrefetch.addAll(songs.map { it.optString("id") })
        library.setPreload(id, count)
        DownloadService.start(this, id)
    }

    private fun prepareSong(song: JSONObject, index: Int) {
        stopGame()
        currentSong = song
        currentIndex = index
        val run = ++operation
        page("loading")
        toolbar("Cancel") {
            operation++
            showLibrary()
        }
        space(body, 44)
        add(body, text("One moment.\nYour stage is set.", 32, TEXT, true))
        space(body, 24)
        body.addView(TileArtwork(this, index), LinearLayout.LayoutParams(-1, dp(220)))
        space(body, 24)
        val status = text("Preparing ${song.optString("title")}…", 16, PURPLE, true)
        add(body, status)
        space(body, 12)
        add(
            body,
            text(
                "Tap short tiles, hold the glowing rails, and drag along diagonal paths at the line. Hit 75% of notes to unlock the next song. Eight misses ends your run.",
                14,
                MUTED,
            ),
        )
        val playlistId = store.playlist!!.optString("id")
        if (!song.optBoolean("demo") && !store.offline(song)) {
            DownloadService.start(this, playlistId, song.optString("id"))
            waitForSong(playlistId, song, index, run, status)
            return
        }
        io.execute {
            runCatching { if (song.optBoolean("demo")) store.prepareDemo(song) }
                .onSuccess {
                    handler.post { if (operation == run && !isDestroyed) launchGame(song, index) }
                }
                .onFailure { error ->
                    handler.post {
                        if (operation == run && !isDestroyed) {
                            showLibrary()
                            showError(error)
                        }
                    }
                }
        }
    }

    private fun waitForSong(
        playlistId: String,
        song: JSONObject,
        index: Int,
        run: Int,
        status: TextView,
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
                            showLibrary()
                            showError(
                                IllegalStateException("Song not found. Import the playlist again."),
                            )
                            return@post
                        }
                        when {
                            store.offline(updated) -> {
                                store.save(playlist!!)
                                launchGame(updated, index)
                            }
                            updated.optString("status") == "error" -> {
                                showLibrary()
                                showError(IllegalStateException(updated.optString("error")))
                            }
                            else -> {
                                status.text =
                                    updated.optString("progress", "Preparing audio on this phone…")
                                waitForSong(playlistId, song, index, run, status)
                            }
                        }
                    }
                }
            },
            750,
        )
    }

    private fun launchGame(song: JSONObject, index: Int) {
        stopGame()
        currentSong = song
        currentIndex = index
        runCatching {
            val notes = store.chart(song).getJSONArray("notes")
            val list = ArrayList<GameSession.Note>()
            var previous = -1.0
            for (i in 0 until notes.length()) {
                val note = notes.getJSONObject(i)
                val time = note.getDouble("time")
                val lane = note.getInt("lane")
                check(time.isFinite() && time >= 0 && time >= previous && lane in 0..3) {
                    "Invalid song chart. Reimport the playlist."
                }
                val kind = note.optString("kind", "tap")
                val duration = note.optDouble("duration", 0.0)
                val endLane = note.optInt("endLane", lane)
                check(
                    kind in listOf("tap", "hold", "slide") &&
                        duration.isFinite() &&
                        duration >= 0 &&
                        endLane in 0..3 &&
                        (kind == "tap" || duration > 0),
                ) {
                    "Invalid gesture chart. Reimport the playlist."
                }
                list.add(GameSession.Note(time, lane, kind, duration, endLane))
                previous = time
            }
            check(list.isNotEmpty()) { "This song has no playable notes." }
            val session = GameSession(list)
            val prepared = MediaPlayer()
            player = prepared
            prepared.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            prepared.setDataSource(
                store.songFile(song, if (song.optBoolean("demo")) "wav" else "mp3").path,
            )
            prepared.setOnPreparedListener {
                if (player !== prepared) return@setOnPreparedListener
                page = "game"
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                root = column().apply { setBackgroundColor(BG) }
                applyInsets(root, false)
                game =
                    GameView(
                        this,
                        song.optString("title"),
                        session,
                        prepared,
                        store.prefs.getFloat("speed", 1f),
                        store.prefs.getInt("timingOffset", 0),
                        object : GameView.Listener {
                            override fun finished(session: GameSession) {
                                handler.post { finishGame(song, session) }
                            }

                            override fun pauseRequested() {
                                showPause()
                            }
                        },
                    )
                root.addView(game, LinearLayout.LayoutParams(-1, -1))
                setContentView(root)
                if (!foreground) game?.pause()
            }
            prepared.setOnErrorListener { _, _, _ ->
                stopGame()
                showLibrary()
                showError(
                    IllegalStateException(
                        "Audio could not play. Clear the offline cache and reimport this song.",
                    ),
                )
                true
            }
            prepared.prepareAsync()
        }
            .onFailure {
                stopGame()
                showLibrary()
                showError(it)
            }
    }

    private fun finishGame(song: JSONObject, session: GameSession) {
        stopGame()
        store.record(song, session)
        page("results")
        toolbar("My journey", ::showLibrary)
        space(body, 30)
        body.addView(
            chip(
                if (session.passed()) "LEVEL COMPLETE" else "KEEP THE RHYTHM",
                if (session.passed()) MINT else PURPLE,
            ),
            LinearLayout.LayoutParams(-2, -2),
        )
        space(body, 20)
        add(
            body,
            text(if (session.passed()) "Beautifully played." else "One more time?", 32, TEXT, true),
        )
        space(body, 10)
        add(body, text(song.optString("title"), 16, MUTED))
        space(body, 28)
        add(
            body,
            text(stars(session.stars()), 48, 0xFFE9C77F.toInt(), true).apply {
                gravity = Gravity.CENTER
            },
        )
        space(body, 10)
        add(
            body,
            text(String.format(Locale.US, "%,d", session.score), 52, TEXT, true).apply {
                gravity = Gravity.CENTER
            },
        )
        add(
            body,
            text("YOUR SCORE", 10, MUTED, true).apply {
                letterSpacing = .15f
                gravity = Gravity.CENTER
            },
        )
        space(body, 28)
        val stats = row()
        weight(stats, stat("${(session.accuracy() * 100).toInt()}%", "Accuracy"))
        weight(stats, stat("${session.maxCombo}", "Best combo"))
        weight(stats, stat("${session.perfect}", "Perfect taps"))
        add(body, stats)
        space(body, 30)
        add(
            body,
            text(
                if (session.passed()) "Your progress is saved. The next song is waiting."
                else
                    "Aim for 75% of notes, with fewer than eight misses. Let the glowing line guide you.",
                14,
                MUTED,
            ),
        )
        space(body, 24)
        if (session.passed() && currentIndex + 1 < store.songs().length())
            add(
                body,
                button("Next song  →", true) {
                    prepareSong(store.songs().getJSONObject(currentIndex + 1), currentIndex + 1)
                },
            )
        else add(body, button("Play again  ↗", true) { prepareSong(song, currentIndex) })
        space(body, 12)
        add(body, button("Back to my journey", false, ::showLibrary))
    }

    private fun stopGame() {
        game?.stop()
        game = null
        player?.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pauseDialog?.dismiss()
        pauseDialog = null
    }

    private fun showPause() {
        val game = game ?: return
        if (pauseDialog?.isShowing == true) return
        game.pause()
        pauseDialog =
            AlertDialog.Builder(this)
                .setTitle("Take a breath")
                .setMessage("Your song is paused. Ready for the next note?")
                .setCancelable(false)
                .setPositiveButton("Resume") { _, _ -> this.game?.resume() }
                .setNeutralButton("Restart") { _, _ ->
                    currentSong?.let { prepareSong(it, currentIndex) }
                }
                .setNegativeButton("Leave song") { _, _ ->
                    stopGame()
                    showLibrary()
                }
                .create()
                .also { it.show() }
    }

    private fun showSettings() {
        val form = column().apply { setPadding(dp(24), dp(12), dp(24), 0) }
        add(form, text("Choose your pace", 15, TEXT, true))
        space(form, 10)
        val speeds = RadioGroup(this)
        val values = floatArrayOf(.8f, 1f, 1.15f)
        val labels = arrayOf("Relaxed · 0.8×", "Original · 1.0×", "Fast · 1.15×")
        val selected = store.prefs.getFloat("speed", 1f)
        val ids = IntArray(3)
        for (i in values.indices) {
            val radio =
                RadioButton(this).apply {
                    id = View.generateViewId()
                    text = labels[i]
                    setTextColor(TEXT)
                }
            ids[i] = radio.id
            speeds.addView(radio)
            if (kotlin.math.abs(selected - values[i]) < .01) speeds.check(ids[i])
        }
        add(form, speeds)
        space(form, 18)
        val label =
            text("Timing offset: ${store.prefs.getInt("timingOffset", 0)} ms", 14, TEXT, true)
        add(form, label)
        space(form, 6)
        add(
            form,
            text(
                "Match the tiles to your headphones. Negative values move tiles later; positive values move them earlier.",
                12,
                MUTED,
            ),
        )
        val offset =
            SeekBar(this).apply {
                max = 60
                progress = store.prefs.getInt("timingOffset", 0) / 10 + 30
            }
        add(form, offset)
        offset.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    label.text = "Timing offset: ${(value - 30) * 10} ms"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {}
            },
        )
        space(form, 16)
        val playlistId = store.playlist!!.optString("id")
        add(form, text("Songs to preload for this playlist", 14, TEXT, true))
        space(form, 6)
        add(
            form,
            text(
                "Automatically save the first 0–20 songs for offline play. Existing downloads stay until you clear the cache.",
                12,
                MUTED,
            ),
        )
        space(form, 10)
        val preload =
            input(
                    "Number of songs",
                    store.prefs
                        .getInt("preload.$playlistId", store.playlist!!.optInt("preloadCount", 3))
                        .toString(),
                )
                .apply { inputType = InputType.TYPE_CLASS_NUMBER }
        add(form, preload)
        space(form, 12)
        add(
            form,
            button("Clear offline song cache", false) {
                AlertDialog.Builder(this)
                    .setTitle("Clear downloaded audio?")
                    .setMessage(
                        "Your scores and unlocks will stay saved. Songs can be downloaded again.",
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        io.execute {
                            java.io.File(filesDir, "songs").deleteRecursively()
                            handler.post {
                                Toast.makeText(this, "Offline cache cleared", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                    .show()
            },
        )
        val settingsScroll = ScrollView(this).apply { addView(form) }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Find your flow")
                .setView(settingsScroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = preload.text.toString().toIntOrNull()
                if (count == null || count !in 0..20) {
                    preload.error = "Choose 0 to 20 songs"
                    return@setOnClickListener
                }
                val index = ids.indexOf(speeds.checkedRadioButtonId).takeIf { it >= 0 } ?: 1
                store.prefs
                    .edit()
                    .putFloat("speed", values[index])
                    .putInt("timingOffset", (offset.progress - 30) * 10)
                    .putInt("preload.$playlistId", count)
                    .apply()
                dialog.dismiss()
                if (playlistId != "keyflow-originals") {
                    store.save(library.setPreload(playlistId, count))
                    attemptedPrefetch.clear()
                    if (page == "library") showLibrary()
                }
            }
        }
        dialog.show()
    }

    override fun onBackPressed() {
        when {
            page == "game" -> showPause()
            store.playlist != null && page != "library" -> {
                operation++
                cancelImport()
                stopGame()
                showLibrary()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        game?.pause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (game != null) showPause() else if (page == "library") startPolling()
    }

    override fun onDestroy() {
        operation++
        cancelImport()
        stopGame()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        images.shutdownNow()
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
                "The download could not connect. Check the phone’s internet connection and retry."
            else error.message ?: "Something went wrong. Please retry."
        AlertDialog.Builder(this)
            .setTitle("Let's try that again")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private val BG = 0xFF101019.toInt()
        private val CARD = 0xFF1C1B29.toInt()
        private val TEXT = 0xFFF3F0FA.toInt()
        private val MUTED = 0xFF9793AA.toInt()
        private val PURPLE = 0xFFB7A0FF.toInt()
        private val MINT = 0xFF85E8D1.toInt()
    }
}
