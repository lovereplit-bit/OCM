package app.opencodemob

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object ModelDownloader {
    data class DownloadTask(
        val id: String,
        val url: String,
        val fileName: String,
        @Volatile var cancelled: Boolean = false,
        var thread: Thread? = null
    )

    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    fun getModelsDir(c: Context, uid: String): File {
        val f = File(Runtime.home(c, uid), "models")
        if (!f.exists()) f.mkdirs()
        return f
    }

    fun listModels(c: Context, uid: String): JSONArray {
        val dir = getModelsDir(c, uid)
        val arr = JSONArray()
        val files = dir.listFiles() ?: return arr
        files.filter { it.isFile && (it.name.endsWith(".gguf", ignoreCase = true) || it.name.endsWith(".bin", ignoreCase = true)) }
            .sortedByDescending { it.lastModified() }
            .forEach { f ->
                val o = JSONObject()
                o.put("fileName", f.name)
                o.put("size", f.length())
                o.put("modified", f.lastModified())
                o.put("path", "/root/models/" + f.name)
                val safeName = f.nameWithoutExtension.lowercase()
                val mf = File(dir, "Modelfile." + safeName)
                o.put("hasModelfile", mf.exists())
                arr.put(o)
            }
        return arr
    }

    fun deleteModel(c: Context, uid: String, fileName: String): Boolean {
        val dir = getModelsDir(c, uid)
        val f = File(dir, fileName)
        var ok = false
        if (f.exists()) {
            ok = f.delete()
        }
        File(dir, "$fileName.download").delete()
        val base = fileName.substringBeforeLast(".")
        File(dir, "Modelfile." + base.lowercase()).delete()
        File(dir, "Modelfile." + fileName.lowercase()).delete()
        return ok
    }

    fun cancel(id: String): Boolean {
        val task = tasks[id] ?: return false
        task.cancelled = true
        task.thread?.interrupt()
        tasks.remove(id)
        return true
    }

    fun isDownloading(id: String): Boolean = tasks.containsKey(id)

    fun startDownload(
        c: Context,
        uid: String,
        id: String,
        url: String,
        fileName: String,
        onProgress: (pct: Int, done: Long, total: Long, speedBps: Long) -> Unit,
        onComplete: (file: File) -> Unit,
        onError: (msg: String) -> Unit
    ): Boolean {
        if (tasks.containsKey(id)) {
            return false
        }
        val task = DownloadTask(id, url, fileName)
        tasks[id] = task

        val th = thread(name = "ModelDownload-$id") {
            val dir = getModelsDir(c, uid)
            val dst = File(dir, fileName)
            val tmp = File(dir, "$fileName.download")
            var inp: InputStream? = null
            var out: FileOutputStream? = null
            var conn: HttpURLConnection? = null

            try {
                var currentUrl = url
                var hops = 0
                while (hops++ < 10) {
                    if (task.cancelled) break
                    val u = URL(currentUrl)
                    val cConn = u.openConnection() as HttpURLConnection
                    cConn.instanceFollowRedirects = false
                    cConn.setRequestProperty("User-Agent", "OpenCode-Mobile")
                    cConn.connectTimeout = 15000
                    cConn.readTimeout = 30000
                    val code = cConn.responseCode
                    if (code in 301..308) {
                        val loc = cConn.getHeaderField("Location") ?: break
                        cConn.disconnect()
                        currentUrl = if (loc.startsWith("http")) loc else URL(u, loc).toString()
                        continue
                    }
                    conn = cConn
                    break
                }

                if (task.cancelled) {
                    tmp.delete()
                    tasks.remove(id)
                    onError("Загрузка отменена")
                    return@thread
                }

                val activeConn = conn ?: throw IllegalStateException("Не удалось установить соединение")
                val total = activeConn.contentLengthLong.coerceAtLeast(1L)
                inp = activeConn.inputStream
                out = FileOutputStream(tmp)

                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastPct = -1
                var lastReportTime = System.currentTimeMillis()
                var bytesSinceReport = 0L
                var speedBps = 0L

                while (true) {
                    if (task.cancelled || Thread.currentThread().isInterrupted) {
                        break
                    }
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    bytesSinceReport += n

                    val now = System.currentTimeMillis()
                    val dt = now - lastReportTime
                    if (dt >= 500) {
                        speedBps = (bytesSinceReport * 1000) / dt.coerceAtLeast(1L)
                        bytesSinceReport = 0L
                        lastReportTime = now
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct, done, total, speedBps)
                        }
                    }
                }

                out.flush()
                out.close(); out = null
                inp.close(); inp = null
                activeConn.disconnect()

                if (task.cancelled) {
                    tmp.delete()
                    tasks.remove(id)
                    onError("Загрузка отменена")
                    return@thread
                }

                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) {
                    throw IllegalStateException("Не удалось сохранить итоговый файл модели")
                }

                ensureModelfile(dir, id, fileName)
                ensureRunScript(dir)

                tasks.remove(id)
                onProgress(100, total, total, 0L)
                onComplete(dst)

            } catch (e: Exception) {
                try { out?.close() } catch (_: Exception) {}
                try { inp?.close() } catch (_: Exception) {}
                try { conn?.disconnect() } catch (_: Exception) {}
                try { tmp.delete() } catch (_: Exception) {}
                tasks.remove(id)
                val m = e.message ?: e.toString()
                val userMsg = when {
                    m.contains("ENOSPC", true) || m.contains("No space left", true) ->
                        "Недостаточно места на накопителе. Освободите память и повторите попытку."
                    m.contains("timeout", true) ->
                        "Превышено время ожидания сети при загрузке модели."
                    task.cancelled -> "Загрузка отменена"
                    else -> "Ошибка загрузки: $m"
                }
                onError(userMsg)
            }
        }

        task.thread = th
        return true
    }

    fun ensureModelfile(modelsDir: File, id: String, fileName: String) {
        try {
            val safeId = id.replace(":", "-").lowercase()
            val mf = File(modelsDir, "Modelfile.$safeId")
            val content = "FROM /root/models/$fileName\nPARAMETER temperature 0.7\nPARAMETER num_ctx 4096\nPARAMETER stop \"<|im_start|>\"\nPARAMETER stop \"<|im_end|>\"\nPARAMETER stop \"<|endoftext|>\"\n"
            mf.writeText(content)
        } catch (_: Exception) {}
    }

    fun ensureRunScript(modelsDir: File) {
        try {
            val sh = File(modelsDir, "import-to-ollama.sh")
            if (!sh.exists()) {
                val script = "#!/bin/bash\n" +
                    "echo \"=== Импорт локальных моделей в Ollama ===\"\n" +
                    "if ! command -v ollama >/dev/null 2>&1; then\n" +
                    "  echo \"Ollama не найдена в системе. Для установки выполните:\"\n" +
                    "  echo \"curl -fsSL https://ollama.com/install.sh | sh\"\n" +
                    "  exit 1\n" +
                    "fi\n" +
                    "cd /root/models || exit 1\n" +
                    "for mf in Modelfile.*; do\n" +
                    "  [ -f \"$mf\" ] || continue\n" +
                    "  mname=\"${mf#Modelfile.}\"\n" +
                    "  echo \"Создание модели $mname...\"\n" +
                    "  ollama create \"$mname\" -f \"$mf\"\n" +
                    "done\n" +
                    "echo \"Готово! Запустите: ollama serve\"\n"
                sh.writeText(script)
                sh.setExecutable(true, false)
            }
        } catch (_: Exception) {}
    }
}
