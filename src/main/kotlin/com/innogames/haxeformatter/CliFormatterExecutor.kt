package com.innogames.haxeformatter

import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * Real CLI invocation (design §4): GeneralCommandLine, injected environment only
 * (ParentEnvironmentType.NONE — the caller supplies EnvironmentUtil.getEnvironmentMap()),
 * UTF-8, stdin piped, stdout/stderr fully captured, destroy() for cancellation.
 */
class CliFormatterExecutor : FormatterExecutor {

    override fun start(command: FormatterCommand, stdin: String): FormatterProcess {
        val commandLine = GeneralCommandLine(command.argv)
            .withWorkDirectory(command.workDir.toFile())
            .withEnvironment(command.environment)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withCharset(StandardCharsets.UTF_8)

        val process = commandLine.createProcess()

        // Drain stdout/stderr concurrently, then feed stdin — never deadlocks on pipe buffers.
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
        val feeder = CompletableFuture.runAsync {
            process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
        }

        return object : FormatterProcess {
            override fun waitFor(): FormatterResult {
                val exit = process.waitFor()
                runCatching { feeder.join() } // broken pipe on early exit is fine
                return FormatterResult(
                    stdout = String(stdout.join(), StandardCharsets.UTF_8),
                    stderr = String(stderr.join(), StandardCharsets.UTF_8),
                    exitCode = exit,
                )
            }

            override fun destroy() {
                process.destroyForcibly()
            }
        }
    }
}
