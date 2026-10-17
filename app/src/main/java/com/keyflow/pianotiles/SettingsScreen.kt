package com.keyflow.pianotiles

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.*
import com.keyflow.pianotiles.arcade.Arcade
import com.keyflow.pianotiles.game.TimingCalibration
import com.keyflow.pianotiles.library.Preparation
import com.keyflow.pianotiles.ui.Glyph
import com.keyflow.pianotiles.ui.Palette
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
        val saved = library.readPlaylists()
        val songs = Arcade.pool(saved)
        val offline = store.offlineCheck()
        val ready = songs.count { offline(it.song) }
        val size = android.text.format.Formatter.formatShortFileSize(this, library.downloadedBytes())
        val playlists = saved.map { playlist ->
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
    // The next two levels and Arcade always download; these hold back only the extra songs.
    settingRow(downloads, "Download ahead on Wi-Fi only", "And Download all. Your next two levels use any connection") {
        toggle(prefs.getBoolean(Preparation.WIFI_ONLY, true), "Download ahead on Wi-Fi only") {
            prefs.edit().putBoolean(Preparation.WIFI_ONLY, it).apply()
            Preparation.wake(this)
        }
    }
    divider(downloads)
    settingRow(downloads, "Download ahead only while charging", "And Download all") {
        toggle(prefs.getBoolean(Preparation.CHARGING_ONLY, false), "Download ahead only while charging") {
            prefs.edit().putBoolean(Preparation.CHARGING_ONLY, it).apply()
            Preparation.wake(this)
        }
    }
    divider(downloads)
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
