package com.innogames.haxeformatter.it

import com.innogames.haxeformatter.CliFormatterExecutor
import com.innogames.haxeformatter.FormatterCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Design §7: "Confirm `haxelib libpath formatter` works under the lix haxeshim" —
 * only needed for the optional post-v1 direct-node fast path (design §5.4). Informational.
 */
class LibpathProbeIT {

    @Before
    fun requireEnvironment() = assumeTrue(ItEnv.ready)

    @Test
    fun `haxelib libpath formatter resolves under the lix shim`() {
        val cmd = FormatterCommand(
            argv = listOf("haxelib", "libpath", "formatter"),
            workDir = ItEnv.fixtureRoot,
            environment = System.getenv() + mapOf(
                "PATH" to "${ItEnv.fixtureRoot.resolve("node_modules/.bin")}:${System.getenv("PATH")}",
            ),
        )
        val result = CliFormatterExecutor().start(cmd, "").waitFor()
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trim().isNotEmpty())
        // Record the resolved path in the task notes — input for the future fast path.
    }
}
