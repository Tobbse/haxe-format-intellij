package com.innogames.haxeformatter.it

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
        // Same env/cwd the plugin would use (production pipeline); only the argv differs.
        val cmd = ItEnv.command("src/Unformatted.hx").copy(argv = listOf("haxelib", "libpath", "formatter"))
        val result = ItEnv.executor.start(cmd, "").waitFor()
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.trim().isNotEmpty())
        // Record the resolved path in the task notes — input for the future fast path.
    }
}
