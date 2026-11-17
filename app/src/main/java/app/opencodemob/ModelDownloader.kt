package app.opencodemob

import android.content.Context
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Model Downloader for Hugging Face and custom local models.
 * Supports streaming downloads, live progress, cancellation, deletion,
 * and automatic registration in OpenCode v1 configuration.
 */
object ModelDownloader {
    data class DownloadTask(
        val id: String,
        val url: String,
        val filename: String,
        val name: String,
        val call: Call,
        var downloadedBytes: Long = 0L,
        var totalBytes: Long = 0L,
        var pct: Int = 0,
        var speed: String = "0 MB/s",
        var status: String = "downloading", // downloading, completed, error, cancelled
        var error: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    var onProgress: ((JSONObject) -> Unit)? = null

    /** Curated catalog of Hugging Face models between 500MB and 4GB */
    val CATALOG = listOf(
        CatalogItem(
            id = "walkyrie",
            name = "Walkyrie (Text to Image)",
            type = "image",
            size = "2.79 ГБ",
            sizeBytes = 2_790_000_000L,
            filename = "walkyrie_FP8e4m3fn.safetensors",
            url = "https://huggingface.co/kpsss34/Walkyrie-1.3B-v1.0/resolve/main/walkyrie_FP8e4m3fn.safetensors?download=true",
            repo = "kpsss34/Walkyrie-1.3B-v1.0",
            description = "Высокодетализированная Text-to-Image модель генерации артов и иллюстраций (2.79 ГБ)."
        ),
        CatalogItem(
            id = "sakura-iris-3b",
            name = "Sakura-Iris-3B",
            type = "image",
            size = "3.37 ГБ",
            sizeBytes = 3_370_000_000L,
            filename = "Sakura-Iris-3B-Q4_K_M-3.14GiB.gguf",
            url = "https://huggingface.co/webmp3/Sakura-Iris-3B-GGUF/resolve/main/Sakura-Iris-3B-Q4_K_M-3.14GiB.gguf?download=true",
            repo = "webmp3/Sakura-Iris-3B-GGUF",
            description = "3B модель генерации визуальных композиций и артов без внешних API (3.37 ГБ)."
        ),
        CatalogItem(
            id = "nanosaur-2",
            name = "Nanosaur 2",
            type = "image",
            size = "1.33 ГБ",
            sizeBytes = 1_330_000_000L,
            filename = "nanosaur2_diffusion_model.safetensors",
            url = "https://huggingface.co/well9472/Nanosaur2-670M/resolve/main/nanosaur2_diffusion_model.safetensors?download=true",
            repo = "well9472/Nanosaur2-670M",
            description = "Компактная DiT модель для быстрого создания иллюстраций (1.33 ГБ)."
        ),
        CatalogItem(
            id = "valkyrie-7b",
            name = "Valkyrie-V1-7B",
            type = "llm",
            size = "3.52 ГБ",
            sizeBytes = 3_520_000_000L,
            filename = "valkyrie-v1.Q3_K_M.gguf",
            url = "https://huggingface.co/TheBloke/Valkyrie-V1-GGUF/resolve/main/valkyrie-v1.Q3_K_M.gguf?download=true",
            repo = "TheBloke/Valkyrie-V1-GGUF",
            description = "Классическая языковая модель Valkyrie v1 (7B параметров, квантование Q3_K_M, 3.52 ГБ)."
        ),
        CatalogItem(
            id = "qwen2.5-coder-0.5b",
            name = "Qwen2.5-Coder-0.5B",
            type = "llm",
            size = "550 МБ",
            sizeBytes = 550_000_000L,
            filename = "qwen2.5-coder-0.5b-instruct-q8_0.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q8_0.gguf",
            repo = "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
            description = "Сверхлёгкий ИИ-ассистент по кодингу для любых мобильных телефонов (550 МБ)."
        ),
        CatalogItem(
            id = "smollm2-1.7b",
            name = "SmolLM2-1.7B-Instruct",
            type = "llm",
            size = "1.0 ГБ",
            sizeBytes = 1_000_000_000L,
            filename = "smollm2-1.7b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            repo = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            description = "Компактная быстрая разговорная модель от команды Hugging Face (1.0 ГБ)."
        ),
        CatalogItem(
            id = "qwen2.5-coder-1.5b",
            name = "Qwen2.5-Coder-1.5B",
            type = "llm",
            size = "1.1 ГБ",
            sizeBytes = 1_100_000_000L,
            filename = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            repo = "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            description = "Идеальный баланс скорости и качества генерации кода на Android (1.1 ГБ)."
        ),
        CatalogItem(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek-R1-Distill-1.5B",
            type = "llm",
            size = "1.1 ГБ",
            sizeBytes = 1_100_000_000L,
            filename = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            repo = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            description = "Модель с глубокими цепочками рассуждений (Reasoning/Thinking) локально на устройстве."
        ),
        CatalogItem(
            id = "llama-3.2-1b",
            name = "Llama-3.2-1B-Instruct",
            type = "llm",
            size = "800 МБ",
            sizeBytes = 800_000_000L,
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            description = "Официальная компактная инструктивная модель Meta Llama 3.2 (800 МБ)."
        ),
        CatalogItem(
            id = "llama-3.2-3b",
            name = "Llama-3.2-3B-Instruct",
            type = "llm",
            size = "2.2 ГБ",
            sizeBytes = 2_200_000_000L,
            filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            description = "Продвинутая 3B модель Meta с отличным пониманием инструкций и контекста (2.2 ГБ)."
        ),
        CatalogItem(
            id = "phi-3.5-mini",
            name = "Phi-3.5-mini-instruct",
            type = "llm",
            size = "2.4 ГБ",
            sizeBytes = 2_400_000_000L,
            filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            repo = "bartowski/Phi-3.5-mini-instruct-GGUF",
            description = "Мощная 3.8B модель Microsoft с поддержкой контекста до 128k токенов (2.4 ГБ)."
        ),
        CatalogItem(
            id = "qwen2.5-coder-3b",
            name = "Qwen2.5-Coder-3B",
            type = "llm",
            size = "2.2 ГБ",
            sizeBytes = 2_200_000_000L,
            filename = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            repo = "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
            description = "Флагманская 3B модель для написания, рефакторинга и отладки кода (2.2 ГБ)."
        )
    )

    data class CatalogItem(
        val id: String,
        val name: String,
        val type: String,
        val size: String,
        val sizeBytes: Long,
        val filename: String,
        val url: String,
        val repo: String,
        val description: String
    )

    /** Starts downloading a model from Hugging Face or direct URL */
    fun startDownload(context: Context, id: String, url: String, filename: String, name: String): JSONObject {
        val cleanUrl = sanitizeHuggingFaceUrl(url)
        val cleanFilename = sanitizeFilename(filename, cleanUrl)
        val targetDir = Runtime.modelsDir(context)
        val destFile = File(targetDir, cleanFilename)
        val partFile = File(targetDir, "$cleanFilename.part")

        if (destFile.exists() && destFile.length() > 1024) {
            // Already downloaded, register immediately
            Runtime.registerLocalModel(context, id, name, cleanFilename)
            return JSONObject().put("ok", true).put("already", true).put("path", destFile.absolutePath)
        }

        if (tasks.containsKey(id)) {
            val t = tasks[id]
            if (t != null && t.status == "downloading") {
                return JSONObject().put("ok", true).put("status", "already_downloading")
            }
        }

        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 OpenCodeMobile/2.0.2")
            .build()

        val call = client.newCall(request)
        val task = DownloadTask(id, cleanUrl, cleanFilename, name, call)
        tasks[id] = task

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    task.status = "cancelled"
                    emit(task)
                    return
                }
                task.status = "error"
                task.error = e.message ?: e.toString()
                emit(task)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    task.status = "error"
                    task.error = "HTTP ${response.code}: ${response.message}"
                    emit(task)
                    return
                }

                val body = response.body
                if (body == null) {
                    task.status = "error"
                    task.error = "Empty response body"
                    emit(task)
                    return
                }

                val totalLen = body.contentLength()
                task.totalBytes = if (totalLen > 0) totalLen else CATALOG.find { it.id == id }?.sizeBytes ?: 1_000_000_000L

                var downloaded = 0L
                var lastUpdate = SystemClock.elapsedRealtime()
                var bytesSinceLastUpdate = 0L

                try {
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (call.isCanceled()) {
                                    task.status = "cancelled"
                                    partFile.delete()
                                    emit(task)
                                    return
                                }
                                output.write(buffer, 0, read)
                                downloaded += read
                                bytesSinceLastUpdate += read
                                task.downloadedBytes = downloaded

                                val now = SystemClock.elapsedRealtime()
                                val elapsed = now - lastUpdate
                                if (elapsed >= 350) {
                                    val speedBytesPerSec = (bytesSinceLastUpdate * 1000) / elapsed
                                    task.speed = formatSpeed(speedBytesPerSec)
                                    task.pct = if (task.totalBytes > 0) ((downloaded * 100) / task.totalBytes).toInt().coerceIn(0, 99) else 0
                                    lastUpdate = now
                                    bytesSinceLastUpdate = 0L
                                    emit(task)
                                }
                            }
                            output.flush()
                        }
                    }

                    // Download complete: atomic rename
                    if (partFile.exists()) {
                        destFile.delete()
                        if (partFile.renameTo(destFile)) {
                            task.status = "completed"
                            task.pct = 100
                            task.speed = "0 MB/s"
                            task.downloadedBytes = destFile.length()
                            task.totalBytes = destFile.length()
                            Runtime.registerLocalModel(context, id, name, cleanFilename)
                            LocalBridgeServer.start(context)
                            emit(task)
                        } else {
                            task.status = "error"
                            task.error = "Failed to rename temporary file"
                            emit(task)
                        }
                    }
                } catch (e: Exception) {
                    if (call.isCanceled()) {
                        task.status = "cancelled"
                        partFile.delete()
                    } else {
                        task.status = "error"
                        task.error = e.message ?: e.toString()
                    }
                    emit(task)
                } finally {
                    if (task.status != "completed") {
                        tasks.remove(id)
                    }
                }
            }
        })

        emit(task)
        return JSONObject().put("ok", true).put("status", "started")
    }

    /** Cancels an active download */
    fun cancelDownload(context: Context, id: String): JSONObject {
        val task = tasks[id] ?: return JSONObject().put("ok", false).put("err", "Task not found")
        task.call.cancel()
        task.status = "cancelled"
        val targetDir = Runtime.modelsDir(context)
        File(targetDir, "${task.filename}.part").delete()
        tasks.remove(id)
        emit(task)
        return JSONObject().put("ok", true)
    }

    /** Deletes a downloaded model */
    fun deleteModel(context: Context, id: String, filename: String): JSONObject {
        cancelDownload(context, id)
        val targetDir = Runtime.modelsDir(context)
        val f = File(targetDir, filename)
        val ok = f.delete()
        File(targetDir, "$filename.part").delete()
        Runtime.unregisterLocalModel(context, id)
        tasks.remove(id)
        return JSONObject().put("ok", ok)
    }

    /** Returns full status list of all curated and custom local models */
    fun getLocalModels(context: Context): JSONArray {
        val targetDir = Runtime.modelsDir(context)
        val files = targetDir.listFiles() ?: emptyArray()
        val res = JSONArray()

        // 1. Curated catalog models
        for (item in CATALOG) {
            val f = File(targetDir, item.filename)
            val isDownloaded = f.exists() && f.length() > 1024
            val task = tasks[item.id]
            val isDownloading = task != null && task.status == "downloading"

            val o = JSONObject()
                .put("id", item.id)
                .put("name", item.name)
                .put("type", item.type)
                .put("size", item.size)
                .put("sizeBytes", if (isDownloaded) f.length() else item.sizeBytes)
                .put("filename", item.filename)
                .put("url", item.url)
                .put("repo", item.repo)
                .put("description", item.description)
                .put("downloaded", isDownloaded)
                .put("downloading", isDownloading)
                .put("path", if (isDownloaded) f.absolutePath else "")
                .put("pct", task?.pct ?: if (isDownloaded) 100 else 0)
                .put("speed", task?.speed ?: "")
                .put("status", task?.status ?: if (isDownloaded) "ready" else "idle")
                .put("error", task?.error ?: "")
            res.put(o)
        }

        // 2. Custom downloaded GGUF/model files
        val knownFilenames = CATALOG.map { it.filename }.toSet()
        for (f in files) {
            if (f.isFile && !f.name.endsWith(".part") && !knownFilenames.contains(f.name)) {
                val id = f.name.substringBeforeLast('.').lowercase().replace("[^a-z0-9_-]".toRegex(), "-")
                val isImage = f.name.contains("image", true) || f.name.contains("safetensors", true)
                res.put(JSONObject()
                    .put("id", id)
                    .put("name", "${f.name.substringBeforeLast('.')} (Локально)")
                    .put("type", if (isImage) "image" else "llm")
                    .put("size", formatBytes(f.length()))
                    .put("sizeBytes", f.length())
                    .put("filename", f.name)
                    .put("url", "")
                    .put("repo", "Custom Local")
                    .put("description", "Пользовательская локальная модель")
                    .put("downloaded", true)
                    .put("downloading", false)
                    .put("path", f.absolutePath)
                    .put("pct", 100)
                    .put("speed", "")
                    .put("status", "ready")
                )
            }
        }

        return res
    }

    private fun emit(task: DownloadTask) {
        val o = JSONObject()
            .put("id", task.id)
            .put("filename", task.filename)
            .put("name", task.name)
            .put("status", task.status)
            .put("pct", task.pct)
            .put("speed", task.speed)
            .put("downloadedBytes", task.downloadedBytes)
            .put("totalBytes", task.totalBytes)
            .put("error", task.error ?: "")
        onProgress?.invoke(o)
    }

    private fun sanitizeHuggingFaceUrl(url: String): String {
        var u = url.trim()
        if (u.contains("huggingface.co") && u.contains("/blob/")) {
            u = u.replace("/blob/", "/resolve/")
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        if (u.contains("huggingface.co") && u.contains("/resolve/") && !u.contains("download=true")) {
            u = if (u.contains("?")) "$u&download=true" else "$u?download=true"
        }
        return u
    }

    private fun sanitizeFilename(filename: String, url: String): String {
        var fn = filename.trim()
        if (fn.isEmpty()) {
            fn = url.substringAfterLast('/').substringBefore('?')
        }
        if (fn.isEmpty() || !fn.contains('.')) {
            fn = "model-${System.currentTimeMillis()}.gguf"
        }
        return fn.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> "%.1f МБ/с".format(Locale.US, bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> "%.0f КБ/с".format(Locale.US, bytesPerSec / 1_000.0)
        else -> "$bytesPerSec Б/с"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f ГБ".format(Locale.US, bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f МБ".format(Locale.US, bytes / 1_000_000.0)
        else -> "%.0f КБ".format(Locale.US, bytes / 1000.0)
    }
}
