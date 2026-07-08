package com.innogames.haxeformatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPolicyTest {

    private fun result(stdout: String = "", stderr: String = "", exit: Int) =
        FormatterResult(stdout, stderr, exit)

    // --- exit 0 -------------------------------------------------------------

    @Test
    fun `exit 0 with changed output applies text with one trailing newline stripped`() {
        // formatter prints formattedText via Sys.println -> exactly one extra \n (design §5.3)
        val outcome = ResultPolicy.decide("class Foo{}", result(stdout = "class Foo {}\n\n", exit = 0))
        assertEquals(FormatOutcome.ApplyText("class Foo {}\n"), outcome)
    }

    @Test
    fun `exit 0 with output equal to input after normalisation is a silent no-change`() {
        val input = "class Foo {}\n"
        val outcome = ResultPolicy.decide(input, result(stdout = input + "\n", exit = 0))
        assertEquals(FormatOutcome.NoChange, outcome)
    }

    // --- exit 1: excluded / disableFormatting -> silent no-op (design §5.3) --

    @Test
    fun `exit 1 is a silent no-change even though stdout echoes the input`() {
        val input = "class Foo{}"
        val outcome = ResultPolicy.decide(input, result(stdout = input + "\n", exit = 1))
        assertEquals(FormatOutcome.NoChange, outcome)
    }

    // --- errors: 2, 3, -1(=255) ----------------------------------------------

    @Test
    fun `exit 2 is an error carrying stderr`() {
        val outcome = ResultPolicy.decide("x", result(stderr = "Parse error line 3", exit = 2))
        assertEquals(FormatOutcome.Error("Haxe Formatter", "Parse error line 3"), outcome)
    }

    @Test
    fun `exit 3 is an error`() {
        assertTrue(ResultPolicy.decide("x", result(stderr = "bad -s path", exit = 3)) is FormatOutcome.Error)
    }

    @Test
    fun `exit 255 (formatter's -1) is an error`() {
        assertTrue(ResultPolicy.decide("x", result(exit = 255)) is FormatOutcome.Error)
    }

    @Test
    fun `error with blank stderr falls back to a message naming the exit code`() {
        val outcome = ResultPolicy.decide("x", result(exit = 2)) as FormatOutcome.Error
        assertTrue(outcome.message.contains("2"))
    }

    // --- normalisation rule in isolation --------------------------------------
    // Rule (v1, pinned empirically by TrailingNewlineIT in Task 11): the CLI emits the
    // formatted text via Sys.println, i.e. exactly one '\n' beyond the true result.

    @Test
    fun `normalise strips exactly one trailing newline`() {
        assertEquals("a\n", ResultPolicy.normaliseTrailingNewline("a\n\n"))
        assertEquals("a", ResultPolicy.normaliseTrailingNewline("a\n"))
        assertEquals("a", ResultPolicy.normaliseTrailingNewline("a"))
        assertEquals("", ResultPolicy.normaliseTrailingNewline("\n"))
    }
}
