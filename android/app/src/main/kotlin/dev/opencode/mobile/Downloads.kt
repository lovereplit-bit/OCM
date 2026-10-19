package dev.opencode.mobile

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Saves files into the public Download folder, `/storage/emulated/0/Download`. On Android 10 and later this goes through
 * MediaStore, which needs no storage permission; earlier versions write the file directly.
 */
object Downloads {
    private val counter = AtomicInteger()
    private val sessions = ConcurrentHashMap<String, Writer>()

    class Writer(private val context: Context, name: String, mime: String) {
        val displayName = clean(name)
        private var uri: android.net.Uri? = null
        private var file: File? = null
        private val out: OutputStream

        init {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val created = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore refused the file")

                uri = created
                out = context.contentResolver.openOutputStream(created) ?: throw IOException("Cannot open the file for writing")
            } else {
                val dir = if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION")
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                } else {
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                }

                dir.mkdirs()

                val target = unique(dir, displayName)

                file = target
                out = FileOutputStream(target)
            }
        }

        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) = out.write(bytes, offset, length)

        /** Finishes the file and returns where it ended up, for the confirmation message. */
        fun finish(): String {
            out.close()

            uri?.let {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }

                context.contentResolver.update(it, done, null, null)

                return "Download/" + queryName(it)
            }

            return file?.absolutePath ?: displayName
        }

        fun abort() {
            runCatching { out.close() }
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            file?.delete()
        }

        private fun queryName(target: android.net.Uri): String =
            context.contentResolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: displayName
    }

    fun clean(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(120)

        return cleaned.ifEmpty { "download" }
    }

    private fun unique(dir: File, name: String): File {
        var target = File(dir, name)
        var n = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        while (target.exists()) target = File(dir, "$stem ($n)$ext").also { n++ }

        return target
    }

    // ---- chunked saving from the page (blob and data URLs) -----------------------------------------------------------

    fun begin(context: Context, name: String, mime: String): String? = try {
        val id = "s" + counter.incrementAndGet()

        sessions[id] = Writer(context, name, mime)
        id
    } catch (e: Exception) {
        Log.w("downloads", "begin failed: ${e.message}")
        null
    }

    fun chunk(id: String, base64: String): Boolean {
        val writer = sessions[id] ?: return false

        return try {
            writer.write(Base64.getDecoder().decode(base64))
            true
        } catch (e: Exception) {
            sessions.remove(id)?.abort()
            false
        }
    }

    fun end(id: String): String? {
        val writer = sessions.remove(id) ?: return null

        return try {
            writer.finish()
        } catch (e: Exception) {
            writer.abort()
            null
        }
    }

    fun abort(id: String) {
        sessions.remove(id)?.abort()
    }

    // ---- links the web view hands over -------------------------------------------------------------------------------

    /** Downloads [url] with the web view's cookies. Runs on its own thread and reports on the main thread via [done]. */
    fun fromUrl(context: Context, url: String, userAgent: String, contentDisposition: String?, mime: String?, done: (Result<String>) -> Unit) {
        Thread({
            val result = runCatching {
                val uri = Urls.parse(url)

                require(uri != null && (uri.scheme == "http" || uri.scheme == "https")) { "Unsupported link" }

                val http = URL(url).openConnection() as HttpURLConnection

                try {
                    http.connectTimeout = 15_000
                    http.readTimeout = 60_000
                    http.setRequestProperty("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { http.setRequestProperty("Cookie", it) }

                    if (http.responseCode !in 200..299) throw IOException("HTTP ${http.responseCode}")

                    val type = mime?.takeIf { it.isNotBlank() } ?: http.contentType ?: "application/octet-stream"
                    val disposition = contentDisposition ?: http.getHeaderField("Content-Disposition")
                    val writer = Writer(context, URLUtil.guessFileName(url, disposition, type), type.substringBefore(';'))

                    try {
                        http.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)

                            while (true) {
                                val n = input.read(buffer)

                                if (n < 0) break

                                writer.write(buffer, 0, n)
                            }
                        }

                        writer.finish()
                    } catch (e: Exception) {
                        writer.abort()

                        throw e
                    }
                } finally {
                    http.disconnect()
                }
            }

            done(result)
        }, "download").apply { isDaemon = true; start() }
    }
}
