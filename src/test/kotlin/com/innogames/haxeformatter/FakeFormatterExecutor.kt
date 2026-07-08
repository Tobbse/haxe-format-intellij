package com.innogames.haxeformatter

import java.util.concurrent.CountDownLatch

class FakeFormatterExecutor(var result: FormatterResult) : FormatterExecutor {
    var lastCommand: FormatterCommand? = null
    var lastStdin: String? = null
    var destroyed = false

    /** When set, waitFor() blocks until destroy() or the latch is released (cancellation test). */
    var blockUntil: CountDownLatch? = null

    override fun start(command: FormatterCommand, stdin: String): FormatterProcess {
        lastCommand = command
        lastStdin = stdin
        return object : FormatterProcess {
            override fun waitFor(): FormatterResult {
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
