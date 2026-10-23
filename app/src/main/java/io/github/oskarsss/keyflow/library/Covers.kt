package io.github.oskarsss.keyflow.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import org.json.JSONObject

/** Downloads, caches and decodes album covers off the main thread. */
class Covers(private val store: LibraryStore) {
    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val cache =
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

    /** Locked covers are blurred and dimmed, so the level stays a surprise. */
    fun into(view: ImageView, song: JSONObject, locked: Boolean, maxPx: Int) {
        val tiny = locked && Build.VERSION.SDK_INT < 31
        val key = "${song.optString("id")}:${if (tiny) 24 else maxPx}"
        view.tag = key
        conceal(view, locked)
        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        if (song.optBoolean("demo")) return
        executor.execute {
            val bitmap = runCatching { decode(song, if (tiny) 24 else maxPx) }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            handler.post { if (view.tag == key && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun conceal(view: ImageView, locked: Boolean) {
        view.alpha = if (locked) .45f else 1f
        if (Build.VERSION.SDK_INT >= 31)
            view.setRenderEffect(
                if (locked) RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP) else null,
            )
    }

    private fun decode(song: JSONObject, maximum: Int): Bitmap? {
        val file = store.songFile(song, "jpg")
        if (!file.exists()) download(song.optString("thumbnail"), file)
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (minOf(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= maximum)
            options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        // Video thumbnails are often letterboxed album art, so keep the centred square.
        val side = minOf(bitmap.width, bitmap.height)
        if (bitmap.width == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    }

    private fun download(url: String, file: File) {
        if (!url.startsWith("https://")) return
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        val temporary = File(file.path + ".cover-part")
        try {
            connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    fun shutdown() = executor.shutdownNow()
}
