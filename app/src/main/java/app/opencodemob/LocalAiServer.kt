package app.opencodemob

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object LocalAiServer {
    @Volatile var activeProcess: Process? = null
    @Volatile var activePort: Int = 0
    @Volatile var activeModel: String = ""
    @Volatile var activeUid: String = ""

    fun getPort(c: Context, uid: String): Int = Runtime.stableAiPort(c, uid)

    fun isAlive(port: Int): Boolean {
        if (port <= 0) return false
        val endpoints = listOf(
            "http://127.0.0.1:$port/health",
            "http://127.0.0.1:$port/v1/models",
            "http://127.0.0.1:$port/api/tags",
            "http://127.0.0.1:$port/"
        )
        for (ep in endpoints) {
            try {
                val conn = URL(ep).openConnection() as HttpURLConnection
                conn.connectTimeout = 400
                conn.readTimeout = 800
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) return true
            } catch (_: Exception) {}
        }
        return false
    }

    @Synchronized
    fun start(c: Context, uid: String, model: String): JSONObject {
        val port = getPort(c, uid)
        if (activePort == port && isAlive(port) && (model.isEmpty() || model == activeModel)) {
            return JSONObject().apply {
                put("ok", true)
                put("port", port)
                put("model", activeModel)
                put("ready", true)
                put("alreadyRunning", true)
            }
        }

        stop()

        activePort = port
        activeModel = model
        activeUid = uid

        return try {
            val p = Runtime.startAiServer(c, uid, port, model)
            activeProcess = p

            // Consume process logs in a background thread to prevent buffer stalls
            val logFile = File(c.filesDir, "ai-server.log")
            thread(isDaemon = true) {
                try {
                    p.inputStream.copyTo(FileOutputStream(logFile, false))
                } catch (_: Exception) {}
            }

            var ready = false
            val end = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < end) {
                if (isAlive(port)) {
                    ready = true
                    break
                }
                if (p.isAlive == false) break
                Thread.sleep(250)
            }

            JSONObject().apply {
                put("ok", true)
                put("port", port)
                put("ready", ready)
                put("model", model)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("err", e.message ?: "Failed to start AI server")
                put("port", port)
            }
        }
    }

    @Synchronized
    fun stop(): Boolean {
        return try {
            activeProcess?.destroy()
            activeProcess = null
            true
        } catch (_: Exception) {
            false
        }
    }

    fun status(c: Context, uid: String): JSONObject {
        val port = getPort(c, uid)
        val running = isAlive(port)
        return JSONObject().apply {
            put("running", running)
            put("port", port)
            put("model", activeModel)
            put("uid", uid)
        }
    }

    fun ensureRunningIfModelsExist(c: Context, uid: String) {
        thread(isDaemon = true) {
            try {
                val list = ModelDownloader.listModels(c, uid)
                if (list.length() > 0) {
                    val port = getPort(c, uid)
                    if (!isAlive(port)) {
                        val first = list.getJSONObject(0).optString("fileName", "")
                        start(c, uid, first)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
