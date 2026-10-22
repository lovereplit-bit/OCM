package dev.opencode.mobile

import java.io.File
import java.io.IOException

/**
 * Runs the real bridge server with a handler that records every call, so the shell commands that ship in the Linux
 * environment can be exercised against it. Usage: GuestMain <port-file> <log-file> <token> <shared-dir>
 */
fun main(args: Array<String>) {
    val portFile = File(args[0])
    val log = File(args[1])
    val token = args[2]
    val shared = File(args[3])

    // The host's root stands in for the guest's root filesystem; the "home" is a scratch directory.
    val guest = GuestPaths(File("/"), File(args[3], "home").apply { mkdirs() }, null)

    val handler = object : BridgeHandler {
        override fun preview(url: String): String? { log.appendText("preview $url\n"); return null }
        override fun open(url: String): String? { log.appendText("open $url\n"); return null }
        override fun notify(title: String, text: String) { log.appendText("notify $title|$text\n") }
        override fun save(path: String, name: String): String {
            val file = guest.resolve(path) ?: throw IOException("File not found: $path")
            val target = File(shared, name.ifEmpty { file.name })

            file.copyTo(target, true)
            log.appendText("save $path|$name\n")

            return "Download/${target.name}"
        }
        override fun widgetSet(name: String, title: String, text: String, url: String, pin: Boolean, src: String, w: Int, h: Int): String {
            log.appendText("widget $name|$title|$text|$url|$pin|$src|$w|$h\n")
            return "widget $name: saved"
        }
        override fun widgetRemove(name: String): Boolean { log.appendText("widget-remove $name\n"); return name != "missing" }
        override fun widgetList() = "demo\ton screen\tTitle"
        override fun version() = "test"
    }

    val server = BridgeServer(token, handler)

    portFile.writeText(server.start().toString())
    Thread.currentThread().join()
}
