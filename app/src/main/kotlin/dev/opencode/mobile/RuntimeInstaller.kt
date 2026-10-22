package dev.opencode.mobile

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import java.io.File
import java.io.IOException

/**
 * Unpacks the bundled Ubuntu root filesystem (with the OpenCode server, Node, Python, git and friends) into the app's
 * private storage. It runs once, and again only when a newer app ships a different root filesystem.
 */
class RuntimeInstaller(private val context: Context, private val paths: Paths) {
    private fun bundledStamp(): String? = try {
        context.assets.open(STAMP_ASSET).use { it.readBytes().toString(Charsets.UTF_8).trim() }.ifEmpty { null }
    } catch (_: IOException) {
        null
    }

    fun installedStamp(): String? = if (paths.rootfsStamp.exists()) paths.rootfsStamp.readText().trim() else null

    private fun hasServer() = File(paths.rootfs, "usr/local/bin/opencode").exists()

    /** A build made without the runtime asset (a plain `gradle assembleDebug`) can still open the settings screens. */
    fun hasBundledRuntime() = bundledStamp() != null

    fun needsInstall(): Boolean {
        val bundled = bundledStamp() ?: return false

        return installedStamp() != bundled || !hasServer()
    }

    fun ready() = hasServer()

    fun install(progress: (Int) -> Unit) {
        val stamp = bundledStamp() ?: throw IOException(
            tr(
                "В эту сборку не вложено Linux-окружение. Соберите приложение через GitHub Actions (см. README).",
                "This build has no Linux runtime inside. Build the app with GitHub Actions (see the README).",
            ),
        )

        val staging = File(paths.files, "rootfs.new")
        val old = File(paths.files, "rootfs.old")

        Fs.deleteTree(staging)
        Fs.deleteTree(old)

        val total = try {
            context.assets.openFd(ARCHIVE_ASSET).use { it.length }
        } catch (_: IOException) {
            -1L
        }

        val extractor = TarGzExtractor(staging, AndroidFs)

        context.assets.open(ARCHIVE_ASSET, AssetManager.ACCESS_STREAMING).use { raw ->
            extractor.extract(
                CountingInputStream(raw) { done ->
                    if (total > 0) progress((done * 100 / total).toInt().coerceIn(0, 99))
                },
            )
        }

        if (extractor.skipped > 0) Log.w("runtime", "${extractor.skipped} archive entries were skipped")

        if (!File(staging, "usr/local/bin/opencode").exists() || !File(staging, "usr/bin/env").exists()) {
            Fs.deleteTree(staging)

            throw IOException(tr("Архив Linux-окружения повреждён или неполон", "The Linux runtime archive is damaged or incomplete"))
        }

        finish(staging)

        if (paths.rootfs.exists()) paths.rootfs.renameTo(old)

        if (!staging.renameTo(paths.rootfs)) throw IOException("Could not move the runtime into place")

        paths.rootfsStamp.writeText(stamp)
        progress(100)

        // Deleting the previous tree can take a while, and nothing depends on it. It runs on its own thread, where an
        // uncaught error would kill the whole process - so it is guarded.
        Thread { runCatching { Fs.deleteTree(old) } }.apply { isDaemon = true }.start()
    }

    /** Things the archive cannot carry: DNS (Android hides its resolver from apps), hosts and the scratch directories. */
    private fun finish(root: File) {
        File(root, "tmp").mkdirs()
        runCatching { Os.chmod(File(root, "tmp").path, 1023) }
        File(root, "root").mkdirs()
        File(root, "etc").mkdirs()

        val resolv = File(root, "etc/resolv.conf")

        resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 2606:4700:4700::1111\n")

        val hosts = File(root, "etc/hosts")

        hosts.delete()
        hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
    }

    /** The /proc files Android denies to apps. Rewritten on every start; they are tiny. */
    fun writeFakeProc() {
        paths.fakeProc.mkdirs()

        for ((name, text) in FakeProc.contents()) File(paths.fakeProc, name).writeText(text)
    }

    companion object {
        const val ARCHIVE_ASSET = "rootfs.tgz"
        const val STAMP_ASSET = "rootfs.stamp"
    }
}
