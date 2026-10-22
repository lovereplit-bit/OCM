package dev.opencode.mobile

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small rotating log file the diagnostics screen can show and share. */
object Log {
    private const val LIMIT = 512 * 1024L
    private const val CRASH_REPORT_FILE = "opencode-crash-report.txt"
    private const val MAX_CRASH_REPORT = 30_000

    @Volatile
    private var file: File? = null

    /** Last startup/service stage, included in a crash report to narrow down early process failures. */
    @Volatile
    private var currentStage: String = "Application startup"
    private val threadStage = ThreadLocal<String?>()

    fun setStage(value: String) {
        val safe = value.take(160)
        threadStage.set(safe)
        currentStage = safe
    }

    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "app.log")
    }

    /**
     * Persist uncaught JVM exceptions before Android's default handler terminates the process, and (when [context] is
     * given) open the crash screen, which lives in its own process and shows the reason.
     */
    fun installCrashHandler(context: android.content.Context? = null) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = runCatching { error.stackTraceToString() }.getOrElse { "Could not format stack trace: $it" }
            val stage = threadStage.get() ?: currentStage
            val report = report(thread, trace, stage)

            runCatching {
                e("crash", "FATAL: uncaught exception at '$stage' on thread '${thread.name}':\n$trace")
            }

            if (context != null) {
                // Persist before trying to launch an Activity: Android may reject that launch while this process dies.
                saveCrashReport(context, report)
                runCatching { CrashActivity.show(context, report) }
            }

            previous?.uncaughtException(thread, error)
        }
    }

    private fun report(thread: Thread, trace: String, stage: String): String =
        "OpenCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n" +
            "Stage: $stage\nThread: ${thread.name}\n\n$trace"

    /** Stores the crash report directly under filesDir, even when the normal app log was not initialized yet. */
    private fun saveCrashReport(context: android.content.Context, report: String) {
        runCatching {
            val dir = context.filesDir
            dir.mkdirs()
            val target = File(dir, CRASH_REPORT_FILE)
            val temp = File(dir, "$CRASH_REPORT_FILE.tmp")
            temp.writeText(report.take(MAX_CRASH_REPORT), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(report.take(MAX_CRASH_REPORT), Charsets.UTF_8)
                temp.delete()
            }
        }.onFailure { android.util.Log.e("OpenCode/crash", "Could not persist crash report", it) }
    }

    /** Read a pending report on the next launch if Android refused to open the crash screen during process death. */
    fun pendingCrashReport(context: android.content.Context): String? = runCatching {
        File(context.filesDir, CRASH_REPORT_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.take(MAX_CRASH_REPORT)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clearPendingCrashReport(context: android.content.Context) {
        runCatching { File(context.filesDir, CRASH_REPORT_FILE).delete() }
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

    /** The last recorded crash of a previous run, so the next one can say what killed it. Null when there was none. */
    fun lastCrash(): String? {
        val text = tailOf(file, 96 * 1024)
        val at = text.lastIndexOf("FATAL: uncaught exception")

        if (at < 0) return null

        return text.substring(at).trim().take(2000)
    }

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
