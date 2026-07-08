package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FormatterContextResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = FormatterContextResolver()

    private fun mkdirs(vararg segments: String): Path {
        val p = tmp.root.toPath().resolve(segments.joinToString("/"))
        Files.createDirectories(p)
        return p
    }

    @Test
    fun `configRoot is the nearest ancestor containing hxformat json`() {
        val root = mkdirs("repo")
        Files.createFile(root.resolve("hxformat.json"))
        val src = mkdirs("repo", "src", "game")
        val ctx = resolver.resolve(src.resolve("Foo.hx"))
        assertEquals(root, ctx.configRoot)
    }

    @Test
    fun `nested hxformat json closest to the file wins`() {
        val outer = mkdirs("repo"); Files.createFile(outer.resolve("hxformat.json"))
        val inner = mkdirs("repo", "sub"); Files.createFile(inner.resolve("hxformat.json"))
        val ctx = resolver.resolve(inner.resolve("deep").also(Files::createDirectories).resolve("Foo.hx"))
        assertEquals(inner, ctx.configRoot)
    }

    @Test
    fun `no hxformat json anywhere falls back to the file's own directory`() {
        // Accepted in design §6: formatter then uses its default config, like VS Code.
        val dir = mkdirs("plain", "src")
        val ctx = resolver.resolve(dir.resolve("Foo.hx"))
        assertEquals(dir, ctx.configRoot)
    }

    @Test
    fun `node_modules bin is discovered by walking up (lix shims)`() {
        val root = mkdirs("repo")
        Files.createFile(root.resolve("hxformat.json"))
        val bin = mkdirs("repo", "node_modules", ".bin")
        val src = mkdirs("repo", "src")
        val ctx = resolver.resolve(src.resolve("Foo.hx"))
        assertEquals(bin, ctx.nodeBinDir)
    }

    @Test
    fun `nodeBinDir is null when no node_modules bin exists`() {
        val root = mkdirs("repo2"); Files.createFile(root.resolve("hxformat.json"))
        val ctx = resolver.resolve(root.resolve("Foo.hx"))
        assertNull(ctx.nodeBinDir)
    }

    @Test
    fun `results are cached per directory and refreshed after invalidate`() {
        val root = mkdirs("repo3"); Files.createFile(root.resolve("hxformat.json"))
        val sub = mkdirs("repo3", "sub")
        val file = sub.resolve("Foo.hx")

        assertEquals(root, resolver.resolve(file).configRoot)

        // A closer config appears; the cached answer must still be served ...
        Files.createFile(sub.resolve("hxformat.json"))
        assertEquals(root, resolver.resolve(file).configRoot)

        // ... until invalidated (design: invalidated on invocation failure).
        resolver.invalidate(file)
        assertEquals(sub, resolver.resolve(file).configRoot)
    }
}
