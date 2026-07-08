package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import kotlin.concurrent.thread

class CliFormatterExecutorTest {

    private val executor = CliFormatterExecutor()
    private val cwd = Paths.get(System.getProperty("java.io.tmpdir"))
    private fun cmd(vararg argv: String) =
        FormatterCommand(argv.toList(), cwd, mapOf("PATH" to "/usr/bin:/bin"))

    @Test
    fun `stdin is piped to the process and stdout captured, exit 0`() {
        val result = executor.start(cmd("/bin/cat"), "class Foo {}\n").waitFor()
        assertEquals(0, result.exitCode)
        assertEquals("class Foo {}\n", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `stderr and non-zero exit code are captured`() {
        val result = executor.start(
            cmd("/bin/sh", "-c", "cat > /dev/null; echo 'Parse error' 1>&2; exit 2"), "input",
        ).waitFor()
        assertEquals(2, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("Parse error"))
    }

    @Test
    fun `utf8 content round-trips`() {
        val text = "// überprüfe ✓ emoji 🎮\n"
        val result = executor.start(cmd("/bin/cat"), text).waitFor()
        assertEquals(text, result.stdout)
    }

    @Test
    fun `large input does not deadlock the pipes`() {
        val big = "x".repeat(2 * 1024 * 1024) + "\n" // > 64k pipe buffer
        val result = executor.start(cmd("/bin/cat"), big).waitFor()
        assertEquals(0, result.exitCode)
        assertEquals(big.length, result.stdout.length)
    }

    @Test
    fun `destroy kills a hung process and waitFor returns promptly with non-zero exit`() {
        val process = executor.start(cmd("/bin/sh", "-c", "sleep 30"), "")
        thread { Thread.sleep(200); process.destroy() }
        val start = System.currentTimeMillis()
        val result = process.waitFor()
        assertTrue("waitFor took too long", System.currentTimeMillis() - start < 10_000)
        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun `working directory is respected`() {
        val result = executor.start(cmd("/bin/sh", "-c", "pwd"), "").waitFor()
        // macOS: java.io.tmpdir is under /var -> /private/var; compare canonical paths
        assertEquals(cwd.toRealPath(), Paths.get(result.stdout.trim()).toRealPath())
    }
}
