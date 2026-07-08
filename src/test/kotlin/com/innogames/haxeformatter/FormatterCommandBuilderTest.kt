package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths

class FormatterCommandBuilderTest {

    private val root = Paths.get("/repo")
    private val file = Paths.get("/repo/src/Foo.hx")

    @Test
    fun `builds the stdin pipe command with the real file path (design §5-3, D2)`() {
        val cmd = FormatterCommandBuilder.build(FormatterContext(root, null), file, emptyMap())
        assertEquals(listOf("haxelib", "run", "formatter", "--stdin", "-s", "/repo/src/Foo.hx"), cmd.argv)
    }

    @Test
    fun `working directory is the configRoot`() {
        val cmd = FormatterCommandBuilder.build(FormatterContext(root, null), file, emptyMap())
        assertEquals(root, cmd.workDir)
    }

    @Test
    fun `node_modules bin is prepended to an existing PATH`() {
        val ctx = FormatterContext(root, Paths.get("/repo/node_modules/.bin"))
        val cmd = FormatterCommandBuilder.build(ctx, file, mapOf("PATH" to "/usr/bin:/bin"))
        assertEquals("/repo/node_modules/.bin:/usr/bin:/bin", cmd.environment["PATH"])
    }

    @Test
    fun `node_modules bin becomes PATH when the base env has none`() {
        val ctx = FormatterContext(root, Paths.get("/repo/node_modules/.bin"))
        val cmd = FormatterCommandBuilder.build(ctx, file, emptyMap())
        assertEquals("/repo/node_modules/.bin", cmd.environment["PATH"])
    }

    @Test
    fun `base environment entries pass through untouched`() {
        val cmd = FormatterCommandBuilder.build(
            FormatterContext(root, null), file, mapOf("PATH" to "/usr/bin", "HOME" to "/Users/dev"),
        )
        assertEquals("/usr/bin", cmd.environment["PATH"])
        assertEquals("/Users/dev", cmd.environment["HOME"])
    }
}
