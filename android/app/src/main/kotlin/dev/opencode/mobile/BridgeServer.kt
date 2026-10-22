package dev.opencode.mobile

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

/** What the Linux side may ask the app to do. Implemented by the Android layer. */
interface BridgeHandler {
    /** Shows [url] in a preview window. Returns null on success or a short reason. */
    fun preview(url: String): String?

    /** Opens [url] in the system browser. */
    fun open(url: String): String?

    fun notify(title: String, text: String)

    /**
     * Copies a file from the Linux environment (a path as the guest sees it) into the phone's Download folder and returns
     * where it ended up. Throws [IOException] with a readable reason when it cannot.
     */
    fun save(path: String, name: String): String

    /** Creates or updates a named home-screen widget. Returns a short message for the agent. ([src] holds rich content: HTML, an SVG or a picture.) */
    fun widgetSet(name: String, title: String, text: String, url: String, pin: Boolean, src: String, w: Int, h: Int): String

    /** Removes the widget called [name]; false when there is none. */
    fun widgetRemove(name: String): Boolean

    /** Lists the widgets, one per line. */
    fun widgetList(): String

    fun version(): String
}

data class BridgeRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
)

object BridgeHttp {
    const val MAX_HEADER = 16 * 1024
    // Rich widget sources (self-contained pages or pictures) ride along base64 in the form body.
    const val MAX_BODY = 6 * 1024 * 1024

    fun parse(input: InputStream): BridgeRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')

        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null

        val headers = HashMap<String, String>()

        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')

            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0

        if (length < 0 || length > MAX_BODY) return null

        val body = ByteArray(length)
        var read = 0

        while (read < length) {
            val n = input.read(body, read, length - read)

            if (n < 0) return null

            read += n
        }

        val target = parts[1]
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val query = if (question < 0) emptyMap() else form(target.substring(question + 1))

        return BridgeRequest(parts[0].uppercase(), path, query, headers, body)
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0

        while (out.size() < MAX_HEADER) {
            val b = input.read()

            if (b < 0) return null

            out.write(b)

            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }

            if (matched == 4) return out.toString(Charsets.ISO_8859_1.name()).dropLast(4)
        }

        return null
    }

    fun form(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue

            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)

            out[decode(key)] = decode(value)
        }

        return out
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun response(code: Int, body: String): ByteArray {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"

        return head.toByteArray(Charsets.ISO_8859_1) + payload
    }
}

/** The shape of a widget name the agent may use: 1-32 of letters, digits, hyphens and underscores. */
private val WIDGET_NAME = Regex("^[A-Za-z0-9_-]{1,32}$")

private const val WIDGET_TITLE_MAX = 60

private const val WIDGET_TEXT_MAX = 400

/** How many bytes of rich widget content (decoded) the agent may send at once. */
private const val WIDGET_SRC_MAX = 4 * 1024 * 1024

/**
 * A tiny HTTP server on the phone's loopback that lets commands inside the Linux environment reach the app: show a
 * preview, open a link, post a notification. Every request needs the per-install token, and anything that looks like
 * it came from a web page (an Origin header or a foreign Host) is refused.
 */
class BridgeServer(
    private val token: String,
    private val handler: BridgeHandler,
    private val requestedPort: Int = 0,
) {
    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var thread: Thread? = null

    val port: Int get() = socket?.localPort ?: -1

    val url: String get() = "http://127.0.0.1:$port"

    fun start(): Int {
        socket?.let { return it.localPort }

        val server = try {
            ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"))
        } catch (_: IOException) {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        }

        socket = server

        thread = Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }

                Thread { serve(client) }.apply { isDaemon = true; name = "bridge-client" }.start()
            }
        }, "bridge-accept").apply { isDaemon = true; start() }

        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun serve(client: Socket) {
        client.use {
            it.soTimeout = 5_000

            val reply = try {
                answer(BridgeHttp.parse(it.getInputStream()))
            } catch (_: Exception) {
                BridgeHttp.response(400, "bad request")
            }

            runCatching {
                it.getOutputStream().apply {
                    write(reply)
                    flush()
                }
            }
        }
    }

    fun answer(request: BridgeRequest?): ByteArray {
        if (request == null) return BridgeHttp.response(400, "bad request")

        val host = request.headers["host"]?.substringBefore(':')?.lowercase()

        if (request.headers.containsKey("origin") || (host != "127.0.0.1" && host != "localhost")) {
            return BridgeHttp.response(403, "forbidden")
        }

        val given = request.headers["x-oc-token"].orEmpty()

        if (!MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
            return BridgeHttp.response(401, "unauthorized")
        }

        val args = LinkedHashMap(request.query)

        if (request.body.isNotEmpty()) args.putAll(BridgeHttp.form(request.body.toString(Charsets.UTF_8)))

        return when (request.path) {
            "/ping" -> BridgeHttp.response(200, "pong ${handler.version()}")

            "/preview" -> {
                val target = Urls.normalizePreview(args["url"].orEmpty())
                    ?: return BridgeHttp.response(400, "not a valid address")

                val error = handler.preview(target)

                if (error == null) BridgeHttp.response(200, "ok $target") else BridgeHttp.response(400, error)
            }

            "/open" -> {
                val url = args["url"].orEmpty()
                val uri = Urls.parse(url)

                if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
                    return BridgeHttp.response(400, "not a valid address")
                }

                val error = handler.open(url)

                if (error == null) BridgeHttp.response(200, "ok") else BridgeHttp.response(400, error)
            }

            "/save" -> {
                val path = args["path"].orEmpty()

                if (path.isEmpty()) return BridgeHttp.response(400, "path is required")

                try {
                    BridgeHttp.response(200, handler.save(path, args["name"].orEmpty()))
                } catch (e: IOException) {
                    BridgeHttp.response(400, e.message ?: "could not save the file")
                }
            }

            "/notify" -> {
                handler.notify(args["title"].orEmpty().take(120), args["text"].orEmpty().take(500))
                BridgeHttp.response(200, "ok")
            }

            "/widget/set" -> {
                val name = args["name"].orEmpty()

                if (!WIDGET_NAME.matches(name)) return BridgeHttp.response(400, "not a valid widget name")

                val title = args["title"].orEmpty()
                val text = args["text"].orEmpty()

                if (title.length > WIDGET_TITLE_MAX) return BridgeHttp.response(400, "title is too long")
                if (text.length > WIDGET_TEXT_MAX) return BridgeHttp.response(400, "text is too long")

                val wanted = args["url"].orEmpty()
                val url = if (wanted.isEmpty()) "" else (Urls.normalizePreview(wanted) ?: return BridgeHttp.response(400, "not a valid address"))
                val pin = args["pin"]?.lowercase() !in setOf("0", "false", "no", "off")

                val src = try {
                    val encoded = args["src_b64"].orEmpty()

                    if (encoded.isEmpty()) "" else String(java.util.Base64.getMimeDecoder().decode(encoded), Charsets.ISO_8859_1)
                } catch (_: IllegalArgumentException) {
                    return BridgeHttp.response(400, "src_b64 is not valid base64")
                }

                if (src.length > WIDGET_SRC_MAX) return BridgeHttp.response(400, "source is too big (max ${WIDGET_SRC_MAX / 1024} kB)")

                val w = (args["w"]?.toIntOrNull() ?: 0).coerceIn(0, WidgetContent.MAX_PX)
                val h = (args["h"]?.toIntOrNull() ?: 0).coerceIn(0, WidgetContent.MAX_PX)

                BridgeHttp.response(200, handler.widgetSet(name, title, text, url, pin, src, w, h))
            }

            "/widget/list" -> BridgeHttp.response(200, handler.widgetList())

            "/widget/remove" -> {
                val name = args["name"].orEmpty()

                if (!WIDGET_NAME.matches(name)) return BridgeHttp.response(400, "not a valid widget name")

                if (handler.widgetRemove(name)) BridgeHttp.response(200, "ok") else BridgeHttp.response(404, "no such widget")
            }

            else -> BridgeHttp.response(404, "not found")
        }
    }
}
