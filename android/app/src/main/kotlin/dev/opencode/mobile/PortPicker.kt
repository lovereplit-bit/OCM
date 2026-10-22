package dev.opencode.mobile

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Each profile gets a port of its own on the phone's loopback. When the port a profile wants is already held by
 * another app on the phone, the next free one is used, so the server starts instead of failing over a busy port.
 */
object PortPicker {
    /** How many ports at and above the wanted one are tried before giving up. */
    const val SPAN = 64

    /**
     * The first port at or after [wanted] that [busy] does not report as taken, or null when [SPAN] ports in a row
     * are all taken. Pure, so the rule can be tested off-device.
     */
    fun pick(wanted: Int, busy: (Int) -> Boolean): Int? {
        if (wanted !in 1..65535) return null

        var candidate = wanted

        while (candidate < wanted + SPAN) {
            if (candidate > 65535) return null
            if (!busy(candidate)) return candidate

            candidate++
        }

        return null
    }

    /**
     * True when nothing on this phone listens on the loopback [port] right now. The server this picks a port for is
     * started right after, so a leftover socket waiting in TIME_WAIT must not count as busy; a live listener does.
     */
    fun free(port: Int): Boolean {
        if (port !in 1..65535) return false

        return try {
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
            }

            true
        } catch (_: IOException) {
            false
        }
    }
}
