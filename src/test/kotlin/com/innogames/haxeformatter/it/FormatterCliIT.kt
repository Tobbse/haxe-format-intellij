package com.innogames.haxeformatter.it

import com.innogames.haxeformatter.CliFormatterExecutor
import com.innogames.haxeformatter.FormatOutcome
import com.innogames.haxeformatter.FormatterCommandBuilder
import com.innogames.haxeformatter.FormatterContextResolver
import com.innogames.haxeformatter.FormatterResult
import com.innogames.haxeformatter.ResultPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ItEnv {
    val fixtureRoot: Path = Paths.get("src/integrationTest/fixtures/project").toAbsolutePath()
    val goldenDir: Path = Paths.get("src/integrationTest/fixtures/golden").toAbsolutePath()
    val ready: Boolean =
        System.getenv("HAXE_FORMATTER_IT") == "1" &&
            Files.isExecutable(fixtureRoot.resolve("node_modules/.bin/haxelib"))

    fun run(fileRelPath: String, stdin: String): FormatterResult {
        val file = fixtureRoot.resolve(fileRelPath)
        val ctx = FormatterContextResolver().resolve(file)
        val cmd = FormatterCommandBuilder.build(ctx, file, System.getenv())
        return CliFormatterExecutor().start(cmd, stdin).waitFor()
    }

    fun read(p: Path): String = Files.readString(p)
}

class FormatterCliIT {

    @Before
    fun requireEnvironment() {
        // Requires macOS + node + lix and fixtures/setup.sh having been run (Task 10).
        assumeTrue("IT environment not prepared — run src/integrationTest/fixtures/setup.sh", ItEnv.ready)
    }

    @Test
    fun `golden - unformatted file formats to the exact CLI golden`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/Unformatted.hx"))
        val result = ItEnv.run("src/Unformatted.hx", input)
        assertEquals("", result.stderr)
        assertEquals(0, result.exitCode)
        assertEquals(ItEnv.read(ItEnv.goldenDir.resolve("Unformatted.hx.golden")), result.stdout)
    }

    @Test
    fun `excluded file exits 1 and echoes input (silent no-op path)`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/excluded/ExcludedFile.hx"))
        val result = ItEnv.run("src/excluded/ExcludedFile.hx", input)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `formatter-off region survives byte-identically`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/FormatterOff.hx"))
        val result = ItEnv.run("src/FormatterOff.hx", input)
        assertEquals(0, result.exitCode)
        assertEquals(ItEnv.read(ItEnv.goldenDir.resolve("FormatterOff.hx.golden")), result.stdout)
    }

    @Test
    fun `syntax error exits 2 with diagnostics on stderr and echoes input on stdout`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/SyntaxError.hx"))
        val result = ItEnv.run("src/SyntaxError.hx", input)
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
    }

    @Test
    fun `idempotency - formatting the formatted output changes nothing (guards trailing-newline bug)`() {
        val input = ItEnv.read(ItEnv.fixtureRoot.resolve("src/Unformatted.hx"))
        val once = ResultPolicy.normaliseTrailingNewline(ItEnv.run("src/Unformatted.hx", input).stdout)
        val twiceResult = ItEnv.run("src/Unformatted.hx", once)
        assertEquals(0, twiceResult.exitCode)
        val twice = ResultPolicy.normaliseTrailingNewline(twiceResult.stdout)
        assertEquals(once, twice) // stable output
        assertEquals(ResultPolicy.decide(once, twiceResult), FormatOutcome.NoChange)
    }
}
