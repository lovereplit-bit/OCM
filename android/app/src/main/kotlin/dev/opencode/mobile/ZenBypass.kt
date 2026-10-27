package dev.opencode.mobile

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * The Zen bypass gateway: a local opencode2api process on 127.0.0.1 that stands between the bundled OpenCode
 * server and opencode.ai. Free models are served through the anonymous "public" credential and, when WARP is
 * on, every free-lane request leaves through a userspace Cloudflare WARP tunnel whose exit the gateway rotates
 * on rate limits (up to 8 exits per request), so one burnt-through IP no longer stops the chat.
 *
 * The gateway is independent of the Linux environment: it is a single static binary shipped as
 * libopencode2api.so, started before the server and stopped with the service.
 */
class ZenBypass(private val context: Context, private val prefs: Prefs) {

    @Volatile
    var process: Process? = null
        private set

    /** The port the running (or next) gateway listens on; 0 when no port was picked yet. */
    var port: Int = prefs.zenBypassPort
        private set

    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    val enabled: Boolean get() = prefs.zenBypass

    private val config: File get() = File(context.filesDir, "zen-bypass-config.json")
    private val log: File get() = File(context.filesDir, "zen-bypass.log")

    /** The gateway binary: packaged as a JNI library so Android unpacks it executable. */
    fun binary(): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, "libopencode2api.so")

        return if (f.exists()) f else null
    }

    /** True when the toggle is on and the build actually carries the gateway binary. */
    fun available(): Boolean = prefs.zenBypass && binary() != null

    /** Writes the gateway configuration. [zenApiKey] is the profile's personal key, kept for paid models. */
    fun writeConfig(zenApiKey: String?) {
        val warp = if (prefs.zenBypassWarp) "true" else "false"
        val zenKeys = if (zenApiKey.isNullOrBlank()) "[]" else "[${MiniJson.quote(zenApiKey)}]"

        val json = """
            {
              "listen": "127.0.0.1:$port",
              "server_keys": [${MiniJson.quote(prefs.zenBypassKey)}],
              "zen_keys": $zenKeys,
              "go_keys": [],
              "anonymous": true,
              "warp": {"enabled": $warp, "pool_size": 2, "stack": "ipv6"},
              "proxies": ["direct"],
              "webui": {"enabled": false},
              "retry": {"max_attempts": 3, "timeout_seconds": 300},
              "performance": {"max_anonymous_exits": 8},
              "logging": {"level": "info", "ring_size": 500}
            }
        """.trimIndent() + "\n"

        config.writeText(json)
    }

    /** Starts the gateway and waits until it answers on /v1/models. Returns false when it did not come up. */
    fun start(zenApiKey: String?): Boolean {
        if (!prefs.zenBypass) return false

        val bin = binary() ?: run {
            Log.w("zen-bypass", "the gateway binary is not in this build; bypass stays off")

            return false
        }

        if (process != null && healthy()) return true

        stop()

        // The saved port may have been taken by another app meanwhile: one retry on a fresh free port before
        // giving up, so a rebooted phone does not silently lose the bypass.
        val firstTry = if (startOn(prefs.zenBypassPort, bin, zenApiKey)) true else {
            Log.w("zen-bypass", "the gateway did not come up on port ${prefs.zenBypassPort}, trying a fresh one")

            stop()
            prefs.zenBypassPort = 0

            startOn(0, bin, zenApiKey)
        }

        if (!firstTry) Log.w("zen-bypass", "the gateway did not come up; continuing without the bypass")

        return firstTry
    }

    /** One start attempt on [wanted] (0 picks a fresh port). Cleans up after itself on failure. */
    private fun startOn(wanted: Int, bin: File, zenApiKey: String?): Boolean {
        return try {
            if (wanted == 0) {
                prefs.zenBypassPort = 0

                ServerSocket(0).use { s -> port = s.localPort }
                prefs.zenBypassPort = port
            } else {
                port = wanted
            }

            writeConfig(zenApiKey)

            Log.i("zen-bypass", "starting the gateway on 127.0.0.1:$port (warp=${prefs.zenBypassWarp})")
            rotateLog()

            val p = ProcessBuilder(listOf(bin.absolutePath, "-config", config.absolutePath))
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()

            process = p

            val up = waitHealthy(20_000)

            if (!up) stop()

            up
        } catch (e: Exception) {
            Log.e("zen-bypass", "could not start the gateway", e)

            stop()

            false
        }
    }

    fun stop() {
        val p = process

        process = null

        if (p != null) {
            runCatching { p.destroy() }
            runCatching { if (p.isAlive) p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { if (p.isAlive) p.destroyForcibly() }
        }
    }

    fun running(): Boolean {
        val p = process ?: return false

        return p.isAlive && healthy()
    }

    /** HTTP status of GET /v1/models with the local key, or -1 when nothing answers. */
    fun health(): Int {
        if (port == 0) return -1

        return try {
            val c = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection

            try {
                c.connectTimeout = 1200
                c.readTimeout = 3000
                c.setRequestProperty("Authorization", "Bearer ${prefs.zenBypassKey}")
                c.responseCode
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun healthy(): Boolean = health() == 200

    private fun waitHealthy(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            if (healthy()) return true
            if (process?.isAlive != true) return false

            Thread.sleep(400)
        }

        return false
    }

    /** The gateway's own log tail, for the logs dialog. */
    fun logTail(): String = try {
        if (log.exists()) log.readText().takeLast(6 * 1024) else ""
    } catch (_: Exception) {
        ""
    }

    private fun rotateLog() {
        if (log.exists() && log.length() > 512 * 1024) {
            val old = File(context.filesDir, "zen-bypass.log.1")

            old.delete()
            log.renameTo(old)
        }
    }
}
