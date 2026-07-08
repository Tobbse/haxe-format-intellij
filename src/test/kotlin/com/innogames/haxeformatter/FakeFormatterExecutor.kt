package com.innogames.haxeformatter

import java.util.concurrent.CountDownLatch

class FakeFormatterExecutor(val result: FormatterResult) : FormatterExecutor {
    var lastCommand: FormatterCommand? = null
    var lastStdin: String? = null
    var destroyed = false

    /** Released when waitFor() is entered — lets tests synchronise instead of sleeping. */
    val started = CountDownLatch(1)

    /** When set, waitFor() blocks until destroy() or the latch is released (cancellation test). */
    var blockUntil: CountDownLatch? = null

    override fun start(command: FormatterCommand, stdin: String): FormatterProcess {
        lastCommand = command
        lastStdin = stdin
        return object : FormatterProcess {
            override fun waitFor(): FormatterResult {
                started.countDown()
                blockUntil?.await()
                return result
            }

            override fun destroy() {
                destroyed = true
                blockUntil?.countDown()
            }
        }
    }
}
