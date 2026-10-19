package dev.opencode.mobile

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small rotating log file the diagnostics screen can show and share. */
object Log {
    private const val LIMIT = 512 * 1024L

    @Volatile
    private var file: File? = null

    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "app.log")
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        android.util.Log.println(
            when (level) { "E" -> android.util.Log.ERROR; "W" -> android.util.Log.WARN; else -> android.util.Log.INFO },
            "OpenCode/$tag",
            message + (error?.let { " " + it } ?: ""),
        )

        val target = file ?: return

        try {
            if (target.length() > LIMIT) {
                val old = File(target.parentFile, "app.log.1")

                old.delete()
                target.renameTo(old)
            }

            target.appendText("${format.format(Date())} $level $tag: $message${error?.let { " | $it" } ?: ""}\n")
        } catch (_: Exception) {
            // Logging must never take the app down.
        }
    }

    fun tail(maxBytes: Int = 64 * 1024): String = tailOf(file, maxBytes)

    fun tailOf(target: File?, maxBytes: Int): String {
        if (target == null || !target.exists()) return ""

        return try {
            java.io.RandomAccessFile(target, "r").use { raf ->
                val size = raf.length()
                val start = maxOf(0L, size - maxBytes)

                raf.seek(start)

                val bytes = ByteArray((size - start).toInt())

                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
