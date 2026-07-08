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

    fun resolve(filePath: Path): FormatterContext =
        cache.computeIfAbsent(cacheKey(filePath), ::compute)

    fun invalidate(filePath: Path) {
        cache.remove(cacheKey(filePath))
    }

    private fun cacheKey(filePath: Path): Path =
        filePath.toAbsolutePath().let { it.parent ?: it }

    private fun compute(startDir: Path): FormatterContext {
        val ancestors = generateSequence(startDir) { it.parent }
        val configRoot = ancestors.firstOrNull { Files.isRegularFile(it.resolve("hxformat.json")) } ?: startDir
        val nodeBin = ancestors.map { it.resolve("node_modules").resolve(".bin") }.firstOrNull(Files::isDirectory)
        return FormatterContext(configRoot, nodeBin)
    }
}
