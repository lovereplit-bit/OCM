package dev.opencode.mobile

import java.security.MessageDigest

/**
 * How to interpret a widget's rich content. The content rides through the bridge as opaque bytes stored in a
 * one-character-per-byte string (see [WidgetContent.bytesOf]), because the Linux side may send HTML, SVG or pictures.
 *
 * This file stays free of Android imports so the JVM tests can cover the bridge contract around it.
 */
object WidgetContent {
    const val KIND_TEXT = "text"
    const val KIND_HTML = "html"
    const val KIND_SVG = "svg"
    const val KIND_IMAGE = "image"

    /** A widget canvas is at most this many pixels on a side. */
    const val MAX_PX = 1280
    const val DEFAULT_W = 512
    const val DEFAULT_H = 384

    /** Decodes the content into raw bytes without touching encoding. */
    fun bytesOf(src: String): ByteArray = src.toByteArray(Charsets.ISO_8859_1)

    /** Which renderer draws this content: plain text, a web (or wrapped SVG) page, or a raster picture. */
    fun kindOf(src: String): String {
        if (src.isEmpty()) return KIND_TEXT

        val raw = bytesOf(src)

        if (Sniff.isRaster(raw)) return KIND_IMAGE

        val head = String(raw, Charsets.ISO_8859_1).trimStart(' ', '\n', '\r', '\t', '\uFEFF').lowercase()

        if (head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head.takeFirst(2_000))) return KIND_SVG

        return KIND_HTML
    }

    /** A content fingerprint that changes when the source or the requested canvas size changes. */
    fun hashOf(src: String, w: Int, h: Int): String {
        val input = bytesOf(src) + byteArrayOf(w.toByte(), h.toByte())
        val digest = MessageDigest.getInstance("SHA-256").digest(input)

        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Detects picture formats the system decoder understands, from content bytes only. */
object Sniff {
    fun isRaster(raw: ByteArray): Boolean {
        if (raw.size < 12) return false

        return when {
            raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() && raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte() -> true
            raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() && raw[2] == 0xFF.toByte() -> true
            head(raw).startsWith("RIFF") && head(raw).startsWith("WEBP", 8) -> true
            head(raw).startsWith("GIF") -> true
            else -> false
        }
    }

    private fun head(raw: ByteArray) = String(raw, Charsets.ISO_8859_1)
}

/** Just the leading [limit] characters, on a string that may be huge. */
internal fun String.takeFirst(limit: Int) = if (length > limit) take(limit) else this
