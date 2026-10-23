package io.github.oskarsss.keyflow

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.oskarsss.keyflow.arcade.Arcade
import io.github.oskarsss.keyflow.game.TimingCalibration
import io.github.oskarsss.keyflow.library.Preparation
import io.github.oskarsss.keyflow.ui.Glyph
import io.github.oskarsss.keyflow.ui.Palette
import kotlin.math.abs

internal fun MainActivity.scheduleCleanup(force: Boolean = false) {
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

internal fun MainActivity.showSettings() {
    val body = page(Screen.SETTINGS, bar("Settings") { showHome() })
    val prefs = store.prefs

    val gameplay = section(body, "Gameplay")
    val speeds = floatArrayOf(.8f, 1f, 1.15f)
    val speed = speeds.indexOfFirst { abs(it - prefs.getFloat("speed", 1f)) < .01 }
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

    val feel = section(body, "Timing and feel")
    settingRow(feel, "Timing offset", "If hits feel early or late") {
        ui.stepper(prefs.getInt("timingOffset", 0), -TimingCalibration.RANGE_MS..TimingCalibration.RANGE_MS, 5, ::signedMs) {
            prefs.edit().putInt("timingOffset", it).apply()
        }
    }
    divider(feel)
    switchRow(feel, "Learn timing from my hits", "Tunes the offset after every song", prefs.getBoolean("autoTiming", true)) {
        prefs.edit().putBoolean("autoTiming", it).apply()
    }
    divider(feel)
    switchRow(feel, "Vibration", null, prefs.getBoolean("haptics", true)) {
        prefs.edit().putBoolean("haptics", it).apply()
    }

    // What's on the phone, what's still coming, and each playlist's share.
    val storage = section(body, "Storage")
    val overview = ui.column().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
    val used = ui.row().apply { gravity = Gravity.BOTTOM }
    val size = ui.text("–", 26f, Palette.TEXT, ui.medium).apply { letterSpacing = -.02f }
    used.addView(size)
    ui.gap(used, 8)
    ui.weight(used, ui.text("used", 14f, Palette.MUTED).apply { setPadding(0, 0, 0, dp(3)) })
    val free = ui.text("", 13f, Palette.MUTED).apply { setPadding(0, 0, 0, dp(3)) }
    used.addView(free)
    ui.fill(overview, used)
    ui.gap(overview, 6)
    val count = ui.text("Checking storage…", 13f, Palette.MUTED)
    ui.fill(overview, count)
    ui.fill(storage, overview)
    divider(storage)
    val queues = navigationRow(storage, "Song queues", "Checking…") { showQueue() }
    val perPlaylist = ui.column()
    ui.fill(body, perPlaylist)
    io.execute {
        val saved = library.readPlaylists()
        val songs = Arcade.pool(saved)
        val offline = store.offlineCheck()
        val ready = songs.count { offline(it.song) }
        val bytes = library.downloadedBytes()
        val left = filesDir.usableSpace
        val waiting = runCatching { Preparation.queues(this) }.getOrNull()
        val playlists = saved.map { playlist ->
            val list = playlist.getJSONArray("songs").let { a -> (0 until a.length()).map(a::getJSONObject) }
            val (downloaded, share) = library.usage(list)
            Triple(playlist, downloaded to list.size, share)
        }
        handler.post {
            if (isDestroyed || screen != Screen.SETTINGS) return@post
            size.text = android.text.format.Formatter.formatShortFileSize(this, bytes)
            free.text = "${android.text.format.Formatter.formatShortFileSize(this, left)} free"
            count.text = "$ready of ${songs.size} songs downloaded"
            queues.text = when {
                waiting == null -> "Songs waiting to download, and levels waiting to be made"
                waiting.downloads.isEmpty() && waiting.levels.isEmpty() -> "Nothing waiting"
                else -> listOfNotNull(
                    waiting.downloads.size.takeIf { it > 0 }?.let { "$it to download" },
                    waiting.levels.size.takeIf { it > 0 }?.let { "${if (it == 1) "1 level" else "$it levels"} to make" },
                ).joinToString(" · ")
            }
            if (playlists.isEmpty()) return@post
            val card = section(perPlaylist, "Playlist downloads")
            for ((i, entry) in playlists.withIndex()) {
                val (playlist, progress, share) = entry
                if (i > 0) divider(card)
                val row = ui.row().apply {
                    setPadding(dp(18), dp(14), dp(14), dp(14))
                    ui.pressable(this, null, 20)
                    contentDescription = "Download settings for ${playlist.optString("title")}"
                    setOnClickListener { showPlaylistSettings(playlist.getString("id"), Screen.SETTINGS) }
                }
                val text = ui.column()
                val top = ui.row()
                ui.weight(top, ui.oneLine(ui.text(playlist.optString("title"), 15f, Palette.TEXT, ui.medium)))
                top.addView(ui.text(android.text.format.Formatter.formatShortFileSize(this, share), 13f, Palette.MUTED))
                ui.fill(text, top)
                ui.gap(text, 8)
                ui.fill(text, ui.progress(progress.first / maxOf(1, progress.second).toFloat(),
                    if (progress.first == progress.second) Palette.MINT else Palette.ACCENT, 4))
                ui.gap(text, 6)
                ui.fill(text, ui.text("${progress.first} of ${progress.second} songs downloaded", 12.5f, Palette.MUTED))
                ui.weight(row, text)
                ui.gap(row, 10)
                row.addView(ui.icon(Glyph.Kind.CHEVRON_RIGHT, Palette.FAINT, 20))
                ui.fill(card, row)
            }
        }
    }

    // The next two levels and Arcade always download; these hold back only the extra songs.
    val downloading = section(body, "Downloading")
    // Every downloaded song gets a quick level in seconds; full levels take minutes each on top.
    switchRow(downloading, "Make full levels", "Higher quality tiling that follows the music closely, made in minutes per song. Off keeps only quick levels being made",
        Preparation.fullLevels(this)) {
        prefs.edit().putBoolean(Preparation.FULL_LEVELS, it).apply()
        Preparation.wake(this)
    }
    divider(downloading)
    switchRow(downloading, "Download ahead on Wi-Fi only", "Also for Download all. Your next two levels download on any connection",
        prefs.getBoolean(Preparation.WIFI_ONLY, true)) {
        prefs.edit().putBoolean(Preparation.WIFI_ONLY, it).apply()
        Preparation.wake(this)
    }
    divider(downloading)
    switchRow(downloading, "Download ahead only while charging", "Also for Download all and making their levels",
        prefs.getBoolean(Preparation.CHARGING_ONLY, false)) {
        prefs.edit().putBoolean(Preparation.CHARGING_ONLY, it).apply()
        Preparation.wake(this)
    }

    val space = section(body, "Free up space")
    lateinit var after: View
    switchRow(space, "Smart storage management", "Remove songs you haven't played recently", prefs.getBoolean("smartStorage", true)) {
        prefs.edit().putBoolean("smartStorage", it).apply()
        after.visibility = if (it) View.VISIBLE else View.GONE
        if (it) scheduleCleanup(force = true)
    }
    after = ui.column().also { group ->
        divider(group)
        settingRow(group, "Remove after", "Days unplayed. Playing a song resets it") {
            ui.stepper(prefs.getInt("retentionDays", 30), 1..365, 1, { "$it days" }) {
                prefs.edit().putInt("retentionDays", it).apply()
                scheduleCleanup(force = true)
            }
        }
        group.visibility = if (prefs.getBoolean("smartStorage", true)) View.VISIBLE else View.GONE
    }
    ui.fill(space, after)
    divider(space)
    actionRow(space, Glyph.Kind.TRASH, "Clear ALL downloaded songs", "Scores and unlocks are kept", Palette.RED) {
        AlertDialog.Builder(this)
            .setTitle("Clear ALL downloaded songs?")
            .setMessage("Songs will download again when you play them.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                io.execute {
                    library.clearDownloads()
                    handler.post {
                        Toast.makeText(this, "Downloads cleared", Toast.LENGTH_SHORT).show()
                        if (screen == Screen.SETTINGS) showSettings()
                    }
                }
            }
            .show()
    }

    val help = section(body, "Troubleshooting")
    actionRow(help, Glyph.Kind.REFRESH, "Update downloader", "Try this if downloads stop working", Palette.TEXT) {
        updateDownloader()
    }

    ui.gap(body, 28)
    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
    ui.fill(body, ui.text("Keyflow ${version ?: ""}".trim(), 12f, Palette.FAINT).apply { gravity = Gravity.CENTER })
}

internal fun MainActivity.toggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) =
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

internal fun MainActivity.settingRow(card: LinearLayout, title: String, subtitle: String?, control: () -> View) {
    val row = ui.row().apply { setPadding(dp(18), dp(14), dp(14), dp(14)) }
    ui.weight(row, labels(title, subtitle))
    ui.gap(row, 12)
    row.addView(control())
    ui.fill(card, row)
}

/** A setting that is on or off; the whole row flips it. */
internal fun MainActivity.switchRow(card: LinearLayout, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val switch = toggle(checked, title, onChange).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val row = ui.row().apply {
        setPadding(dp(18), dp(14), dp(14), dp(14))
        ui.pressable(this, null, 20)
        contentDescription = title
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switch.isChecked
            }
        }
        setOnClickListener { switch.toggle() }
    }
    ui.weight(row, labels(title, subtitle))
    ui.gap(row, 12)
    row.addView(switch)
    ui.fill(card, row)
}

/** A row that opens another page; returns its subtitle so it can be filled in later. */
private fun MainActivity.navigationRow(card: LinearLayout, title: String, subtitle: String, open: () -> Unit): TextView {
    val row = ui.row().apply {
        setPadding(dp(18), dp(14), dp(14), dp(14))
        ui.pressable(this, null, 20)
        setOnClickListener { open() }
    }
    val labels = labels(title, subtitle)
    ui.weight(row, labels)
    ui.gap(row, 10)
    row.addView(ui.icon(Glyph.Kind.CHEVRON_RIGHT, Palette.FAINT, 20))
    ui.fill(card, row)
    return labels.getChildAt(2) as TextView
}

private fun MainActivity.settingStack(card: LinearLayout, title: String, subtitle: String?, control: () -> View) {
    val stack = ui.column().apply { setPadding(dp(18), dp(16), dp(18), dp(16)) }
    ui.fill(stack, labels(title, subtitle))
    ui.gap(stack, 12)
    ui.fill(stack, control())
    ui.fill(card, stack)
}

internal fun MainActivity.actionRow(
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

private fun MainActivity.updateDownloader() {
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
            .onSuccess { updated ->
                handler.post {
                    dialog.dismiss()
                    if (!isDestroyed)
                        Toast.makeText(this, if (updated) "Downloader updated" else "Downloader is already up to date",
                            Toast.LENGTH_LONG).show()
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
