package dev.opencode.mobile

import java.net.URI
import java.util.Base64

/** Where a link the web UI tries to open should go. */
enum class Target {
    /** Stays in the app's own WebView. */
    INTERNAL,

    /** A site running on this phone: shown in a preview window. */
    PREVIEW,

    /** The system browser or another app. */
    EXTERNAL,

    /** Dropped. */
    IGNORE,
}

object Urls {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1", "0.0.0.0")

    fun isLoopback(host: String?): Boolean {
        val h = host?.lowercase() ?: return false

        return h in loopbackHosts || h.endsWith(".localhost") || h.startsWith("127.")
    }

    fun parse(url: String): URI? = try {
        URI(url.trim().replace(" ", "%20"))
    } catch (_: Exception) {
        null
    }

    fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    /**
     * Turns what a user or the agent typed into a URL a preview window can load. A bare port means localhost, a bare
     * host gets `http://`, and `0.0.0.0` / `127.0.0.1` become `localhost`: the app itself lives on `127.0.0.1`, and
     * cookies are shared across ports of one host, so a dev server must not see the app's session cookie.
     */
    fun normalizePreview(input: String): String? {
        val text = input.trim()

        if (text.isEmpty() || text.length > 2048) return null

        val port = text.toIntOrNull()

        if (port != null) return if (port in 1..65535) "http://localhost:$port" else null

        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "http://$text"
        val uri = parse(withScheme) ?: return null

        if (uri.scheme != "http" && uri.scheme != "https") return null

        val host = uri.host ?: return null

        if (host == "0.0.0.0" || host == "127.0.0.1") {
            val rebuilt = StringBuilder(uri.scheme).append("://localhost")

            if (uri.port != -1) rebuilt.append(':').append(uri.port)
            rebuilt.append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            if (uri.rawQuery != null) rebuilt.append('?').append(uri.rawQuery)
            if (uri.rawFragment != null) rebuilt.append('#').append(uri.rawFragment)

            return rebuilt.toString()
        }

        return uri.toString()
    }

    /**
     * Classifies a link. [internalOrigin] is the origin the app itself runs on, and [reservedPorts] are loopback ports
     * that belong to the app (its server and its bridge) and must never be shown as a preview.
     */
    fun classify(url: String, internalOrigin: String, reservedPorts: Set<Int> = emptySet()): Target {
        val uri = parse(url) ?: return Target.IGNORE
        val scheme = uri.scheme?.lowercase() ?: return Target.IGNORE

        if (scheme == "mailto" || scheme == "tel" || scheme == "sms" || scheme == "geo") return Target.EXTERNAL

        if (scheme != "http" && scheme != "https") return Target.IGNORE

        val internal = parse(internalOrigin)
        val host = uri.host

        if (internal != null) {
            val sameHost = host.equals(internal.host, true) || (isLoopback(host) && isLoopback(internal.host))

            if (sameHost && port(uri) == port(internal) && scheme == internal.scheme) return Target.INTERNAL
        }

        if (isLoopback(host)) return if (port(uri) in reservedPorts) Target.IGNORE else Target.PREVIEW

        return Target.EXTERNAL
    }

    /** `opencode:<password>`, the credential the server accepts in `Authorization: Basic` and `?auth_token=`. */
    fun basicCredential(password: String): String =
        Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    fun displayHost(url: String): String {
        val uri = parse(url) ?: return url
        val host = uri.host ?: return url

        return if (uri.port != -1) "$host:${uri.port}" else host
    }

    fun withoutTrailingSlash(url: String) = url.trim().trimEnd('/')

    /** A server address typed by a person: `host:port` and bare hosts get `http://`. */
    fun normalizeServer(input: String): String? {
        val text = input.trim()

        if (text.isEmpty()) return null

        val other = Regex("^(?!https?://)[a-zA-Z][a-zA-Z0-9+.-]*://", RegexOption.IGNORE_CASE)

        if (other.containsMatchIn(text)) return null

        val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "http://$text"
        val uri = parse(withScheme) ?: return null

        if (uri.host == null || (uri.scheme != "http" && uri.scheme != "https")) return null

        return withoutTrailingSlash(withScheme)
    }
}
