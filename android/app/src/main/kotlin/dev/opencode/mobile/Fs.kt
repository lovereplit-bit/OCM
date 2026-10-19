package dev.opencode.mobile

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object Fs {
    /**
     * Deletes [root] and everything under it without ever following a symlink. A root filesystem is full of absolute
     * links, and `File.deleteRecursively` would walk straight through them into the real directories they name.
     */
    fun deleteTree(root: File) {
        val start = root.toPath()

        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return

        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // Extracted directories can be read-only, which would block deleting their children.
                    dir.toFile().setWritable(true, true)
                    dir.toFile().setExecutable(true, true)
                    dir.toFile().setReadable(true, true)

                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)

                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }

                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Copies regular files (not directories or links) directly inside [from] whose names match [accept] into [to]. */
    fun copyFlat(from: File, to: File, accept: (String) -> Boolean): Long {
        var total = 0L

        to.mkdirs()

        for (file in from.listFiles().orEmpty()) {
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || !accept(file.name)) continue

            file.copyTo(File(to, file.name), overwrite = true)
            total += file.length()
        }

        return total
    }

    fun sizeFlat(dir: File, accept: (String) -> Boolean): Long =
        dir.listFiles().orEmpty().filter { it.isFile && accept(it.name) }.sumOf { it.length() }
}
