package com.innogames.haxeformatter

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class FormatterContext(
    /** Directory containing the nearest hxformat.json, else the file's own directory. */
    val configRoot: Path,
    /** Nearest ancestor's node_modules/.bin (lix shims), or null. */
    val nodeBinDir: Path?,
)

/**
 * Walk-up discovery of the formatter context for a file (design §4, §5.4).
 * Worktree-correct by construction: only the file's own ancestors are consulted.
 * Cached per containing directory; invalidate() on invocation failure.
 */
class FormatterContextResolver {
    private val cache = ConcurrentHashMap<Path, FormatterContext>()

    fun resolve(filePath: Path): FormatterContext {
        val dir = filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
        return cache.computeIfAbsent(dir, ::compute)
    }

    fun invalidate(filePath: Path) {
        val dir = filePath.toAbsolutePath().parent ?: filePath.toAbsolutePath()
        cache.remove(dir)
    }

    private fun compute(startDir: Path): FormatterContext {
        val configRoot = findUp(startDir) { Files.isRegularFile(it.resolve("hxformat.json")) } ?: startDir
        val nodeBin = findUp(startDir) { Files.isDirectory(it.resolve("node_modules").resolve(".bin")) }
            ?.resolve("node_modules")?.resolve(".bin")
        return FormatterContext(configRoot, nodeBin)
    }

    private fun findUp(from: Path, predicate: (Path) -> Boolean): Path? {
        var dir: Path? = from
        while (dir != null) {
            if (predicate(dir)) return dir
            dir = dir.parent
        }
        return null
    }
}
