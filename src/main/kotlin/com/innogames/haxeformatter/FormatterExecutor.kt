package com.innogames.haxeformatter

import java.nio.file.Path

data class FormatterCommand(
    val argv: List<String>,
    val workDir: Path,
    val environment: Map<String, String>,
)

data class FormatterResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/** Started, running formatter process. */
interface FormatterProcess {
    /** Blocks until the process exits. */
    fun waitFor(): FormatterResult

    /** Kills the process; waitFor() must then return promptly. */
    fun destroy()
}

/** Mockable seam between the service and the OS (design §4). */
interface FormatterExecutor {
    fun start(command: FormatterCommand, stdin: String): FormatterProcess
}
