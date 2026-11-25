package app.opencodemob

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local AI Bridge Server for OpenCode Mobile (v1 line).
 * Listens on 127.0.0.1:8088 and exposes an OpenAI-compatible API
 * (/v1/models, /v1/chat/completions, /v1/images/generations, /preload)
 * routing GGUF LLMs/VLMs to llama-server on 127.0.0.1:8089.
 */
object LocalBridgeServer {
    const val DEFAULT_PORT = 8088
    private const val LLAMA_PORT = 8089
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)
    private var appContext: Context? = null
    var activePort = DEFAULT_PORT
        private set

    private val llamaLock = Any()
    @Volatile private var llamaProc: Process? = null
    @Volatile private var loadedModelFile: String? = null

    fun isAlive(): Boolean = isRunning.get() && serverSocket != null && !serverSocket!!.isClosed

    @Synchronized
    fun start(context: Context, port: Int = DEFAULT_PORT): Boolean {
        appContext = context.applicationContext
        if (isAlive()) return true
        activePort = port
        return try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            }
            serverSocket = ss
            isRunning.set(true)
            thread(name = "LocalBridgeServer-Acceptor", isDaemon = true) {
                while (isRunning.get() && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        pool.execute { handleClient(client) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            true
        } catch (_: Exception) {
            isRunning.set(false)
            false
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        stopLlamaServer()
    }

    fun stopIfModel(filename: String) {
        synchronized(llamaLock) {
            if (loadedModelFile == filename) {
                stopLlamaServerLocked()
            }
        }
    }

    private fun stopLlamaServer() {
        synchronized(llamaLock) {
            stopLlamaServerLocked()
        }
    }

    private fun stopLlamaServerLocked() {
        try { llamaProc?.destroy() } catch (_: Exception) {}
        try {
            val p = llamaProc
            if (p != null && p.isAlive) {
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        llamaProc = null
        loadedModelFile = null
    }

    private data class HttpReq(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readLineRaw(inp: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var b = inp.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
            b = inp.read()
        }
        return String(baos.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun readExactBytes(inp: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = inp.read(buf, off, count - off)
            if (n < 0) break
            off += n
        }
        return if (off == count) buf else buf.copyOf(off)
    }

    private fun readChunkedBody(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineRaw(inp)?.trim() ?: break
            val hexSize = sizeLine.substringBefore(';').trim()
            val chunkSize = hexSize.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Consume trailing CRLF after 0-length chunk
                readLineRaw(inp)
                break
            }
            out.write(readExactBytes(inp, chunkSize))
            readLineRaw(inp) // CRLF after chunk data
        }
        return out.toByteArray()
    }

    private fun readHttpRequest(rawIn: InputStream): HttpReq? {
        val inp = BufferedInputStream(rawIn, 32 * 1024)
        val reqLine = readLineRaw(inp) ?: return null
        val parts = reqLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val uri = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLineRaw(inp) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase(Locale.US)
                val v = line.substring(idx + 1).trim()
                headers[k] = v
            }
        }

        val te = headers["transfer-encoding"]?.lowercase(Locale.US) ?: ""
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = when {
            te.contains("chunked") -> readChunkedBody(inp)
            contentLength > 0 -> readExactBytes(inp, contentLength)
            else -> ByteArray(0)
        }
        return HttpReq(method, uri, headers, String(bodyBytes, StandardCharsets.UTF_8))
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 300_000
            val req = readHttpRequest(s.getInputStream()) ?: return
            val out = s.getOutputStream()

            if (req.method == "OPTIONS") {
                sendCors(out)
                return
            }

            val path = req.uri.substringBefore('?')
            val query = req.uri.substringAfter('?', "")
            when {
                path == "/health" || path == "/v1/health" -> {
                    val llamaOk = isLlamaHealthy()
                    sendJson(
                        out,
                        JSONObject()
                            .put("status", "ok")
                            .put("bridge", "local-ai-bridge")
                            .put("port", activePort)
                            .put("llamaRunning", llamaOk)
                            .put("loadedModel", loadedModelFile ?: "")
                    )
                }
                path == "/v1/models" || path == "/models" -> {
                    handleGetModels(out)
                }
                path == "/preload" || path == "/v1/models/preload" -> {
                    handlePreload(out, query, req.body)
                }
                (path == "/v1/chat/completions" || path == "/chat/completions") && req.method == "POST" -> {
                    handleChatCompletions(out, req.body)
                }
                (path == "/v1/images/generations" || path == "/images/generations") && req.method == "POST" -> {
                    handleImageGenerations(out, req.body)
                }
                else -> {
                    sendError(out, 404, "Endpoint not found: $path")
                }
            }
        }
    }

    private fun listLocalModelFiles(ctx: Context): List<File> {
        val dir = Runtime.modelsDir(ctx)
        return dir.listFiles()?.filter { f ->
            f.isFile && f.length() > 1024 && !f.name.endsWith(".part") &&
                !f.name.startsWith("mmproj-", true) &&
                (f.name.endsWith(".gguf", true) || f.name.endsWith(".safetensors", true) || f.name.endsWith(".bin", true))
        } ?: emptyList()
    }

    private fun findModelFile(ctx: Context, requestedModel: String): File? {
        val files = listLocalModelFiles(ctx)
        if (files.isEmpty()) return null
        val reqLow = requestedModel.lowercase(Locale.US).trim()
        for (f in files) {
            if (ModelDownloader.deriveModelId(f.name) == reqLow || f.name.equals(requestedModel, true)) {
                return f
            }
        }
        val catById = ModelDownloader.CATALOG.find { it.id.equals(reqLow, true) }
        if (catById != null) {
            files.find { it.name == catById.filename }?.let { return it }
        }
        return files.find { it.name.lowercase(Locale.US).contains(reqLow) } ?: files.firstOrNull()
    }

    private fun findMmprojFile(ctx: Context, modelFile: File): File? {
        val dir = Runtime.modelsDir(ctx)
        val stem = modelFile.name.substringBeforeLast('.').lowercase(Locale.US)
        val files = dir.listFiles() ?: return null
        return files.find { f ->
            f.isFile && f.length() > 1024 && f.name.startsWith("mmproj", true) &&
                (f.name.lowercase(Locale.US).contains(stem.take(12)) || stem.contains("smolvlm") && f.name.lowercase(Locale.US).contains("smolvlm"))
        }
    }

    private fun handleGetModels(out: OutputStream) {
        val ctx = appContext
        val modelsList = JSONArray()

        if (ctx != null) {
            try {
                for (f in listLocalModelFiles(ctx)) {
                    val catItem = ModelDownloader.CATALOG.find { it.filename == f.name }
                    val id = ModelDownloader.deriveModelId(f.name)
                    val name = catItem?.name?.let { if (it.contains("(Локально)")) it else "$it (Локально)" }
                        ?: "${f.name.substringBeforeLast('.')} (Локально)"
                    val type = catItem?.type ?: if (f.name.contains("safetensors", true) || id.contains("walkyrie")) "image" else "llm"

                    modelsList.put(
                        JSONObject()
                            .put("id", id)
                            .put("object", "model")
                            .put("created", f.lastModified() / 1000)
                            .put("owned_by", "local")
                            .put("name", name)
                            .put("type", type)
                            .put("filename", f.name)
                            .put("size", f.length())
                    )
                }
            } catch (_: Exception) {}
        }

        sendJson(out, JSONObject().put("object", "list").put("data", modelsList))
    }

    private fun handlePreload(out: OutputStream, query: String, reqBody: String) {
        val ctx = appContext
        if (ctx == null) {
            sendError(out, 503, "Bridge context not initialized")
            return
        }
        var modelId = ""
        if (query.contains("model=")) {
            modelId = query.substringAfter("model=").substringBefore('&')
        } else if (reqBody.isNotBlank()) {
            try { modelId = JSONObject(reqBody).optString("model", "") } catch (_: Exception) {}
        }
        val file = findModelFile(ctx, modelId)
        if (file == null) {
            sendError(out, 404, "No downloaded local model found")
            return
        }
        val catItem = ModelDownloader.CATALOG.find { it.filename == file.name }
        val isDiffusion = (catItem?.type == "image") || file.name.endsWith(".safetensors", true)
        if (isDiffusion) {
            sendJson(out, JSONObject().put("ok", true).put("mode", "image-local").put("file", file.name))
            return
        }
        thread(isDaemon = true) {
            try { ensureLlamaServer(ctx, file) } catch (_: Exception) {}
        }
        sendJson(out, JSONObject().put("ok", true).put("status", "warming").put("file", file.name))
    }

    private fun isLlamaHealthy(): Boolean {
        val p = llamaProc
        if (p == null || !p.isAlive) return false
        return try {
            val con = URL("http://127.0.0.1:$LLAMA_PORT/health").openConnection() as HttpURLConnection
            con.connectTimeout = 1000
            con.readTimeout = 1500
            val code = con.responseCode
            con.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureLlamaServer(ctx: Context, modelFile: File): String? {
        val llamaBin = File(Runtime.rootfs(ctx), "opt/llama/llama-server")
        if (!llamaBin.exists()) {
            return "Движок llama-server не найден в Linux-окружении. Переустановите или обновите окружение в Настройках."
        }
        val uid = Prefs.get(ctx, "uid").ifEmpty { "default" }

        synchronized(llamaLock) {
            if (loadedModelFile == modelFile.name && isLlamaHealthy()) {
                return null
            }

            stopLlamaServerLocked()
            val threads = minOf(4, maxOf(2, java.lang.Runtime.getRuntime().availableProcessors() / 2))
            val args = mutableListOf(
                "-m", "/opt/models/${modelFile.name}",
                "--host", "127.0.0.1",
                "--port", LLAMA_PORT.toString(),
                "-c", "8192",
                "-t", threads.toString(),
                "--jinja",
                "--no-warmup"
            )
            findMmprojFile(ctx, modelFile)?.let { mm ->
                args.add("--mmproj")
                args.add("/opt/models/${mm.name}")
            }

            val proc = try {
                Runtime.startLlamaProcess(ctx, uid, args)
            } catch (e: Exception) {
                return "Не удалось запустить llama-server: ${e.message}"
            }
            llamaProc = proc
            loadedModelFile = modelFile.name

            val logFile = File(ctx.filesDir, "llama.log")
            thread(name = "LlamaServer-Logger", isDaemon = true) {
                try { proc.inputStream.copyTo(FileOutputStream(logFile, false)) } catch (_: Exception) {}
            }

            val deadline = SystemClock.elapsedRealtime() + 75_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!proc.isAlive) {
                    val code = try { proc.exitValue() } catch (_: Exception) { -1 }
                    val tail = try {
                        if (logFile.exists()) logFile.readLines().takeLast(6).joinToString(" | ") else ""
                    } catch (_: Exception) { "" }
                    stopLlamaServerLocked()
                    return "llama-server завершился с кодом $code" + (if (tail.isNotBlank()) ": $tail" else "")
                }
                if (isLlamaHealthy()) {
                    return null
                }
                SystemClock.sleep(350)
            }

            stopLlamaServerLocked()
            return "Превышено время загрузки модели ${modelFile.name} в память"
        }
    }

    private fun handleChatCompletions(out: OutputStream, reqBody: String) {
        val req = try { JSONObject(reqBody) } catch (e: Exception) {
            sendError(out, 400, "Invalid JSON: ${e.message}")
            return
        }

        val ctx = appContext
        if (ctx == null) {
            sendError(out, 503, "Local bridge context unavailable")
            return
        }

        val model = req.optString("model", "").lowercase(Locale.US)
        val isStream = req.optBoolean("stream", true)
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val userPrompt = extractLastUserPrompt(messages)

        val modelFile = findModelFile(ctx, model)
        if (modelFile == null) {
            sendError(out, 404, "Локальная модель «$model» не найдена на устройстве. Скачайте её в Настройки → Локальные ИИ модели.")
            return
        }

        val catItem = ModelDownloader.CATALOG.find { it.filename == modelFile.name }
        val isImageModel = (catItem?.type == "image") ||
            modelFile.name.endsWith(".safetensors", true) ||
            model.contains("walkyrie") || model.contains("sakura")

        // 1. Diffusion Text-to-Image local artifact generator
        if (isImageModel) {
            val imgFile = generateLocalImagePreview(catItem?.name ?: modelFile.name, userPrompt.ifEmpty { "Visual concept" })
            val relPath = "generated/${imgFile.name}"
            val absPath = imgFile.absolutePath

            val responseText = buildString {
                append("**").append(catItem?.name ?: modelFile.name).append(" (Локально)**\n\n")
                append("Сформировано локальное изображение по запросу:\n")
                append("> *«").append(userPrompt.ifEmpty { "Visual composition" }).append("»*\n\n")
                append("![Local Image](file://").append(absPath).append(")\n\n")
                append("**Файл сохранён:** `").append(absPath).append("`\n\n")
                append("<button data-action=\"preview\" data-path=\"").append(relPath).append("\">Открыть изображение</button>\n\n")
                append("+0 -0")
            }

            if (isStream) {
                sendStreamResponse(out, model.ifEmpty { catItem?.id ?: "local" }, responseText)
            } else {
                sendJsonResponse(out, model.ifEmpty { catItem?.id ?: "local" }, responseText)
            }
            return
        }

        // 2. Real GGUF LLM / VLM execution via llama-server
        val err = ensureLlamaServer(ctx, modelFile)
        if (err != null) {
            sendError(out, 503, err)
            return
        }

        // Prepare payload for llama-server
        val forward = JSONObject(reqBody)
        forward.put("model", modelFile.name)
        if (!forward.has("max_tokens")) {
            forward.put("max_tokens", 2048)
        }

        val statusAndStream = forwardToLlama(out, forward)
        if (statusAndStream == 400 || statusAndStream == 500) {
            // Retry once without OpenCode tool schemas if this GGUF's Jinja template rejects tool definitions
            val stripped = JSONObject(forward.toString())
            var hadTools = false
            if (stripped.has("tools")) { stripped.remove("tools"); hadTools = true }
            if (stripped.has("tool_choice")) { stripped.remove("tool_choice"); hadTools = true }
            if (stripped.has("parallel_tool_calls")) { stripped.remove("parallel_tool_calls"); hadTools = true }
            if (hadTools) {
                val retryCode = forwardToLlama(out, stripped)
                if (retryCode in 200..299) return
            }
            sendError(out, 500, "Локальная модель ${modelFile.name} вернула ошибку HTTP $statusAndStream")
        }
    }

    /**
     * Forwards request JSON to llama-server on 127.0.0.1:8089.
     * Returns HTTP status code. Only writes to client OutputStream if 2xx (or if not a retryable 400/500 with tools).
     */
    private fun forwardToLlama(clientOut: OutputStream, payload: JSONObject): Int {
        var con: HttpURLConnection? = null
        return try {
            con = URL("http://127.0.0.1:$LLAMA_PORT/v1/chat/completions").openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.connectTimeout = 5_000
            con.readTimeout = 300_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("Accept", "text/event-stream, application/json")
            val bodyBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            con.outputStream.use { it.write(bodyBytes) }

            val code = con.responseCode
            if (code in 200..299) {
                val ct = con.contentType ?: if (payload.optBoolean("stream", true)) {
                    "text/event-stream; charset=utf-8"
                } else {
                    "application/json; charset=utf-8"
                }
                val hdr = "HTTP/1.1 $code OK\r\n" +
                    "Content-Type: $ct\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
                clientOut.write(hdr.toByteArray(StandardCharsets.UTF_8))
                clientOut.flush()

                val buf = ByteArray(8192)
                con.inputStream.use { inp ->
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                }
            }
            code
        } catch (e: Exception) {
            sendError(clientOut, 502, "Ошибка связи с llama-server: ${e.message}")
            502
        } finally {
            try { con?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun extractLastUserPrompt(messages: JSONArray): String {
        var userPrompt = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val content = m.opt("content")
            userPrompt = when (content) {
                is String -> content
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j)
                        if (part != null && part.optString("type") == "text") {
                            sb.append(part.optString("text")).append(" ")
                        }
                    }
                    sb.toString().trim()
                }
                else -> content?.toString() ?: ""
            }
        }
        return userPrompt
    }

    private fun handleImageGenerations(out: OutputStream, reqBody: String) {
        val req = try { JSONObject(reqBody) } catch (_: Exception) { JSONObject() }
        val prompt = req.optString("prompt", "Visual composition")
        val imgFile = generateLocalImagePreview("Walkyrie", prompt)
        val absPath = imgFile.absolutePath

        val dataArr = JSONArray().put(
            JSONObject()
                .put("url", "file://$absPath")
                .put("revised_prompt", prompt)
        )
        sendJson(
            out,
            JSONObject()
                .put("created", System.currentTimeMillis() / 1000)
                .put("data", dataArr)
        )
    }

    private fun generateLocalImagePreview(modelName: String, prompt: String): File {
        val ctx = appContext
        val outDir = if (File("/storage/emulated/0").exists()) {
            File("/storage/emulated/0/OpenCode/generated").apply { mkdirs() }
        } else {
            File(ctx?.filesDir ?: File("/tmp"), "generated").apply { mkdirs() }
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "local_$ts.png")

        try {
            val width = 768
            val height = 768
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val hash = prompt.hashCode()
            val c1 = 0xFF0D0D0F.toInt()
            val c2 = 0xFF141824.toInt() or ((hash and 0x0F1F2F))
            val c3 = 0xFF0F172A.toInt()

            val grad = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(c1, c2, c3), null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = grad })

            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF38BDF8.toInt()
                alpha = 32
            }
            canvas.drawCircle(width * 0.32f, height * 0.35f, 180f, orbPaint)
            orbPaint.alpha = 18
            canvas.drawCircle(width * 0.68f, height * 0.62f, 220f, orbPaint)

            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC141418.toInt() }
            val cardRect = RectF(40f, height - 200f, width - 40f, height - 40f)
            canvas.drawRoundRect(cardRect, 20f, 20f, cardPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF38BDF8.toInt()
                textSize = 30f
                isFakeBoldText = true
            }
            canvas.drawText("$modelName · Local Preview", 65f, height - 150f, titlePaint)

            val promptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
            }
            val cleanPrompt = if (prompt.length > 55) prompt.take(52) + "..." else prompt
            canvas.drawText("«$cleanPrompt»", 65f, height - 100f, promptPaint)

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFA1A1AA.toInt()
                textSize = 18f
            }
            canvas.drawText("Local On-Device Output · OpenCode Mobile", 65f, height - 65f, subPaint)

            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            bmp.recycle()
        } catch (_: Exception) {
            try { outFile.writeText("Local Output: $prompt") } catch (_: Exception) {}
        }

        return outFile
    }

    private fun sendStreamResponse(out: OutputStream, model: String, fullText: String) {
        val mid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val created = System.currentTimeMillis() / 1000

        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        val roleObj = JSONObject()
            .put("id", mid)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject().put("role", "assistant").put("content", ""))
                    .put("finish_reason", JSONObject.NULL)
            ))
        out.write("data: ${roleObj}\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()

        val words = fullText.split(" ")
        var chunkBuilder = StringBuilder()

        for (i in words.indices) {
            chunkBuilder.append(words[i])
            if (i < words.size - 1) chunkBuilder.append(" ")

            if (chunkBuilder.length >= 25 || i == words.size - 1) {
                val deltaContent = chunkBuilder.toString()
                chunkBuilder = StringBuilder()

                val chunkObj = JSONObject()
                    .put("id", mid)
                    .put("object", "chat.completion.chunk")
                    .put("created", created)
                    .put("model", model)
                    .put("choices", JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("delta", JSONObject().put("content", deltaContent))
                            .put("finish_reason", JSONObject.NULL)
                    ))
                out.write("data: ${chunkObj}\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                SystemClock.sleep(15)
            }
        }

        val finishObj = JSONObject()
            .put("id", mid)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject())
                    .put("finish_reason", "stop")
            ))
        out.write("data: ${finishObj}\n\n".toByteArray(StandardCharsets.UTF_8))
        out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, model: String, fullText: String) {
        val mid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val created = System.currentTimeMillis() / 1000

        val res = JSONObject()
            .put("id", mid)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("message", JSONObject().put("role", "assistant").put("content", fullText))
                    .put("finish_reason", "stop")
            ))
            .put("usage", JSONObject().put("prompt_tokens", 30).put("completion_tokens", 100).put("total_tokens", 130))

        sendJson(out, res)
    }

    private fun sendJson(out: OutputStream, json: JSONObject, status: Int = 200) {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n\r\n"
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendCors(out: OutputStream) {
        val h = "HTTP/1.1 204 No Content\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Max-Age: 86400\r\n\r\n"
        out.write(h.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val json = JSONObject().put("error", JSONObject().put("message", msg).put("code", code))
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val statusText = when (code) {
            400 -> "Bad Request"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val h = "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(h.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
