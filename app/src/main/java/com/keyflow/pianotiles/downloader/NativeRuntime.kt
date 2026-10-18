package com.keyflow.pianotiles.downloader

import android.content.Context
import android.system.Os
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject

/**
 * Adapted from YTDLnis RuntimeManager, PackageBase and ZipUtils (GPL-3.0). Copyright Denis Çerri
 * and contributors. Revision afec4a6eb562c3e7e4b98b2a48f371712f101380. Keyflow changes: app-private
 * storage, bundled-only packages, bounded UTF-8 streams, timeouts, checksum-verified extractor
 * updates, and no dependency on YTDLnis UI.
 */
class NativeRuntime(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "downloader")
    private val native = File(context.applicationInfo.nativeLibraryDir)
    private val python = File(native, "libpython.so")
    private val ffmpeg = File(native, "libffmpeg.so")
    private val quickJs = File(native, "libqjs.so")
    private val extractor = File(root, "yt-dlp")
    // yt-dlp unpacked from its zip, so Python keeps its compiled code between runs instead of
    // compiling the whole extractor again for every song.
    private val unpacked = File(root, "yt-dlp-app")
    private val cache = File(context.cacheDir, "yt-dlp")
    private val packages = listOf("python", "ffmpeg")
    private val processes = ConcurrentHashMap<String, Process>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val processLock = Any()
    private var updating = false
    @Volatile private var initialized = false

    @Synchronized
    fun initialize(progress: (String) -> Unit = {}) {
        if (initialized) return
        root.mkdirs()
        for (name in packages) {
            val archive = File(native, "lib$name.zip.so")
            check(archive.isFile) { "The downloader runtime is unavailable for this device's CPU." }
            val destination = File(root, name)
            val stamp = File(destination, ".bundle")
            val signature = "${archive.length()}:${archive.lastModified()}"
            if (!stamp.isFile || stamp.readText() != signature) {
                progress("Preparing built-in $name…")
                destination.deleteRecursively()
                destination.mkdirs()
                unzip(archive, destination)
                stamp.writeText(signature)
            }
        }
        check(python.isFile && ffmpeg.isFile && quickJs.isFile) {
            "The bundled download engine is incomplete."
        }
        if (!extractor.isFile)
            context.assets.open("downloader/yt-dlp").use { input ->
                extractor.outputStream().use { input.copyTo(it) }
            }
        unpackExtractor()
        initialized = true
    }

    /** Unpacks yt-dlp once, and again after it is updated. */
    private fun unpackExtractor() {
        val stamp = File(unpacked, ".bundle")
        val signature = "${extractor.length()}:${extractor.lastModified()}"
        if (stamp.isFile && stamp.readText() == signature && File(unpacked, "__main__.py").isFile) return
        val temporary = File(root, "yt-dlp-app.part").apply { deleteRecursively(); mkdirs() }
        unzip(extractor, temporary)
        check(File(temporary, "__main__.py").isFile) { "The bundled download engine is incomplete." }
        File(temporary, ".bundle").writeText(signature)
        unpacked.deleteRecursively()
        check(temporary.renameTo(unpacked)) { "The download engine could not be prepared." }
    }

    private fun unzip(archive: File, directory: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val output = File(directory, entry.name)
                check(output.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
                    "Invalid runtime archive entry"
                }
                when {
                    entry.isDirectory -> output.mkdirs()
                    entry.isUnixSymlink -> {
                        output.parentFile?.mkdirs()
                        val target =
                            zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use {
                                it.readText()
                            }
                        check(
                            File(output.parentFile, target)
                                .canonicalPath
                                .startsWith(directory.canonicalPath + File.separator),
                        ) {
                            "Invalid runtime symlink"
                        }
                        Os.symlink(target, output.absolutePath)
                    }
                    else -> {
                        output.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            output.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
    }

    private fun environment(scratch: File) =
        mapOf(
            "LD_LIBRARY_PATH" to
                (packages.map { File(root, "$it/usr/lib").path } + native.path).joinToString(":"),
            "PYTHONHOME" to File(root, "python/usr").path,
            "SSL_CERT_FILE" to File(root, "python/usr/etc/tls/cert.pem").path,
            "TMPDIR" to scratch.path,
            // Progress lines arrive as they happen, not in one burst when yt-dlp finishes.
            "PYTHONUNBUFFERED" to "1",
            "PATH" to "${native.path}:${System.getenv("PATH") ?: "/system/bin"}",
        )

    data class Output(val out: String, val error: String)

    fun execute(
        request: YTDLRequest,
        id: String,
        timeoutSeconds: Long = 600,
        outputFile: File? = null,
        progress: (String) -> Unit = {},
    ): Output {
        initialize(progress)
        request
            .addOption("--ignore-config")
            // Kept between songs: YouTube's player code and solved signatures.
            .addOption("--cache-dir", cache.path)
            .addOption("--ffmpeg-location", ffmpeg.path)
            .addOption("--js-runtimes", "quickjs:${quickJs.path}")
            .addOption("--remote-components", "ejs:github")
        val args = listOf(python.path, unpacked.path) + request.buildCommand()
        return executeImpl(args, id, timeoutSeconds, outputFile, progress)
    }

    private fun executeImpl(
        args: List<String>,
        id: String,
        timeout: Long,
        outputFile: File?,
        progress: (String) -> Unit,
    ): Output {
        val process = start(args, id)
        val out = StringBuffer()
        val errors = StringBuffer()
        val outputFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader = if (outputFile == null) drain(process.inputStream, out, 8 * 1024 * 1024, progress)
        else thread(name = "playlist-output") {
            try {
                process.inputStream.use { input -> outputFile.outputStream().use { input.copyTo(it) } }
            } catch (error: Throwable) {
                outputFailure.set(error)
                destroyTree(process)
            }
        }
        val errorReader = drain(process.errorStream, errors, 512 * 1024, progress)
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) {
                "The download timed out. Please retry."
            }
            reader.join()
            errorReader.join()
            outputFailure.get()?.let { throw it }
            check(process.exitValue() == 0) {
                errors.toString().ifBlank { "The download failed. Please retry." }.takeLast(6000)
            }
            return Output(out.toString(), errors.toString())
        } finally {
            processes.remove(id)
            destroyTree(process)
            scratch(id).deleteRecursively()
        }
    }

    /** Consume decoded PCM before waiting, so FFmpeg cannot deadlock on a full stdout pipe. */
    fun <T> audioSamples(audio: File, id: String, consume: (InputStream) -> T): T =
        audioSamples(audio, id, 11025, consume)

    fun <T> audioSamples(audio: File, id: String, rate: Int, consume: (InputStream) -> T): T {
        initialize()
        val command =
            listOf(
                ffmpeg.path,
                "-v",
                "error",
                "-threads",
                "1",
                "-i",
                audio.path,
                "-t",
                "1200",
                "-f",
                "s16le",
                "-ac",
                "1",
                "-ar",
                rate.toString(),
                "pipe:1",
            )
        val process = start(command, id)
        val errors = StringBuffer()
        val errorReader = drain(process.errorStream, errors, 128 * 1024) {}
        val watchdog =
            thread(isDaemon = true, name = "audio-timeout") {
                try {
                    if (!process.waitFor(180, TimeUnit.SECONDS)) destroyTree(process)
                } catch (_: InterruptedException) {}
            }
        try {
            val result = process.inputStream.use(consume)
            process.waitFor()
            errorReader.join()
            check(process.exitValue() == 0) {
                "Unable to analyze this audio: ${errors.toString().takeLast(1000)}"
            }
            return result
        } finally {
            processes.remove(id)
            destroyTree(process)
            watchdog.interrupt()
            scratch(id).deleteRecursively()
        }
    }

    /** Each job's own scratch folder, removed as soon as the job ends, whatever the outcome. */
    private fun scratch(id: String) = File(work(context), id.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun start(command: List<String>, id: String): Process = synchronized(processLock) {
        check(!cancelled.remove(id)) { "Import cancelled." }
        check(!processes.containsKey(id)) { "A download is already running." }
        check(!updating) { "Wait for the downloader update to finish, then retry." }
        // Java worker priority is not reliably inherited by subprocess creation. Give native
        // decoding/extraction an explicit lower CPU priority so playback and rendering win.
        val nice = File("/system/bin/nice")
        val background = if (nice.canExecute()) listOf(nice.path, "-n", "10") + command else command
        ProcessBuilder(background)
            .apply { environment().putAll(this@NativeRuntime.environment(scratch(id).apply { deleteRecursively(); mkdirs() })) }
            .start()
            .also { processes[id] = it }
    }

    private fun drain(
        stream: InputStream,
        buffer: StringBuffer,
        limit: Int,
        progress: (String) -> Unit,
    ): Thread =
        thread(name = "download-output") {
            runCatching {
                stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        if (buffer.length < limit)
                            buffer.append(line.take(limit - buffer.length)).append('\n')
                        runCatching { progress(line) }
                    }
                }
            }
        }

    fun cancel(id: String) {
        val process = synchronized(processLock) {
            cancelled.add(id)
            processes.remove(id)
        }
        process?.let(::destroyTree)
    }

    private fun destroyTree(process: Process) {
        // YTDLnis cancellation includes ffmpeg/JS child processes, not just Python.
        val pid = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
        if (pid != null) {
            val children = mutableMapOf<Int, MutableList<Int>>()
            File("/proc").listFiles()?.forEach { file ->
                val child = file.name.toIntOrNull() ?: return@forEach
                val parent =
                    runCatching {
                        File(file, "stat")
                            .readText()
                            .substringAfterLast(')')
                            .trim()
                            .split(' ')[1]
                            .toInt()
                    }
                        .getOrNull() ?: return@forEach
                children.getOrPut(parent) { mutableListOf() }.add(child)
            }
            val queue = java.util.ArrayDeque<Int>().apply { add(pid) }
            while (!queue.isEmpty()) children[queue.removeFirst()]?.forEach { child ->
                queue.add(child)
                runCatching { android.os.Process.killProcess(child) }
            }
        }
        process.destroy()
    }

    @Synchronized
    /** Installs the latest yt-dlp; false when the one on the phone already is the latest. */
    fun updateExtractor(progress: (String) -> Unit): Boolean {
        synchronized(processLock) {
            check(processes.isEmpty()) { "Wait for current music downloads to finish before updating." }
            updating = true
        }
        try {
            return downloadExtractorUpdate(progress)
        } finally {
            synchronized(processLock) { updating = false }
        }
    }

    private fun downloadExtractorUpdate(progress: (String) -> Unit): Boolean {
        initialize(progress)
        progress("Checking for a downloader update…")
        val release =
            JSONObject(
                String(
                    fetch("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"),
                    StandardCharsets.UTF_8,
                ),
            )
        val assets = release.getJSONArray("assets")
        val asset =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .first { it.getString("name") == "yt-dlp" }
        val url = asset.getString("browser_download_url")
        check(url.startsWith("https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/"))
        val expected = asset.optString("digest").removePrefix("sha256:")
        check(expected.matches(Regex("[a-f0-9]{64}"))) {
            "The update has no checksum. The bundled downloader remains available."
        }
        if (hex(MessageDigest.getInstance("SHA-256").digest(extractor.readBytes())) == expected) return false
        progress("Downloading the update…")
        val bytes = fetch(url)
        check(hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == expected) {
            "Downloader update checksum mismatch"
        }
        val temporary = File(root, "yt-dlp.update")
        temporary.writeBytes(bytes)
        java.nio.file.Files.move(
            temporary.toPath(),
            extractor.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        unpackExtractor()
        cache.deleteRecursively()
        return true
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "Keyflow")
        try {
            check(connection.responseCode == 200) { "Unable to check downloader updates." }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        /** Scratch space for the downloader and analysis, emptied when the app starts. */
        fun work(context: Context) = File(context.cacheDir, "work").apply { mkdirs() }
    }
}
