package dev.opencode.mobile

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Turns a path as the Linux environment sees it into the file on the phone that backs it, without ever leaving the
 * places that environment can reach: its root filesystem, the active profile's home (bound at /root) and, when it is
 * bound, shared storage. Symbolic links are followed the way the guest would follow them, so an absolute link inside the
 * root filesystem points back into the root filesystem and not at the phone's own files.
 */
class GuestPaths(private val rootfs: File, private val home: File, private val shared: File?) {
    /** The backing regular file for [guestPath], or null when it does not exist, is not a plain file or cannot be reached. */
    fun resolve(guestPath: String): File? {
        if (!guestPath.startsWith("/") || guestPath.contains('\u0000')) return null

        val resolved = ArrayList<String>()
        val queue = ArrayDeque(split(guestPath))
        var links = 0

        while (queue.isNotEmpty()) {
            val part = queue.removeFirst()

            when (part) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> {
                    val candidate = toHost(resolved + part)

                    if (Files.isSymbolicLink(candidate.toPath())) {
                        if (++links > MAX_LINKS) return null

                        val target = try {
                            Files.readSymbolicLink(candidate.toPath()).toString()
                        } catch (_: Exception) {
                            return null
                        }

                        if (target.startsWith("/")) resolved.clear()

                        // The link's own text goes in front of what is still to be walked.
                        for (piece in split(target).asReversed()) queue.addFirst(piece)
                    } else {
                        resolved += part
                    }
                }
            }
        }

        val file = toHost(resolved)

        return if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file else null
    }

    private fun split(path: String): List<String> = path.split('/')

    /** [parts] never contains a separator or `..`, so the result cannot climb out of the directory it starts from. */
    private fun toHost(parts: List<String>): File {
        fun under(base: File, rest: List<String>): File = rest.fold(base) { dir, name -> File(dir, name) }

        if (parts.isNotEmpty() && parts[0] == "root") return under(home, parts.drop(1))

        if (shared != null && parts.isNotEmpty() && parts[0] == "sdcard") return under(shared, parts.drop(1))

        if (shared != null && parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" && parts[2] == "0") {
            return under(shared, parts.drop(3))
        }

        return under(rootfs, parts)
    }

    companion object {
        const val MAX_LINKS = 40
    }
}
