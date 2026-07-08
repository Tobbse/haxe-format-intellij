package com.innogames.haxeformatter.it

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Design §7: "Empirically byte-diff --stdin output vs input for the no-change case
 * (trailing newline, Neko vs Node)." This pins ResultPolicy.normaliseTrailingNewline's
 * rule (strip exactly one '\n') against the real CLI. If this test fails, the rule —
 * and its unit test in ResultPolicyTest — must be corrected to match reality.
 */
class TrailingNewlineIT {

    @Before
    fun requireEnvironment() = assumeTrue(ItEnv.ready)

    @Test
    fun `no-change input comes back as input plus exactly one newline`() {
        val formatted = ItEnv.read(ItEnv.goldenDir.resolve("Unformatted.hx.golden"))
            .let(com.innogames.haxeformatter.ResultPolicy::normaliseTrailingNewline)
        val result = ItEnv.run("src/Unformatted.hx", formatted)
        assertEquals(0, result.exitCode)
        assertEquals(formatted + "\n", result.stdout) // THE empirical claim from design §5.3
    }
}
