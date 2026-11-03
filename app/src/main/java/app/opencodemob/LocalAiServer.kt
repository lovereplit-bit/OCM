package app.opencodemob

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LocalAiServer {
    @Volatile var activeProcess: Process? = null
    @Volatile var activePort: Int = 0
    @Volatile var activeModel: String = ""
    @Volatile var activeUid: String = ""

    fun getPort(c: Context, uid: String): Int = Runtime.stableAiPort(c, uid)

    fun isAlive(port: Int): Boolean {
        return try {
            val u = URL("http://127.0.0.1:$port/health")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 1200
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (_: Exception) {
            try {
                val u2 = URL("http://127.0.0.1:$port/v1/models")
                val conn2 = u2.openConnection() as HttpURLConnection
                conn2.connectTimeout = 800
                conn2.readTimeout = 1200
                val code2 = conn2.responseCode
                conn2.disconnect()
                code2 in 200..499
            } catch (_: Exception) {
                false
            }
        }
    }

    fun start(c: Context, uid: String, model: String): JSONObject {
        val port = getPort(c, uid)
        if (activePort == port && activeModel == model && activeProcess?.isAlive == true && isAlive(port)) {
            return JSONObject().put("ok", true).put("port", port).put("model", model).put("alreadyRunning", true)
        }

        stop()

        activePort = port
        activeModel = model
        activeUid = uid

        return try {
            val cmd = listOf("/usr/local/bin/aiserver", "start", model, port.toString())
            val p = Runtime.prootCmd(c, uid, cmd)
            activeProcess = p

            var ready = false
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                if (isAlive(port)) {
                    ready = true
                    break
                }
                Thread.sleep(250)
            }

            JSONObject().put("ok", true).put("port", port).put("ready", ready).put("model", model)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("err", e.message ?: "Failed to start AI server").put("port", port)
        }
    }

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
}
