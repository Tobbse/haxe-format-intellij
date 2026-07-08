package com.innogames.haxeformatter

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessIOExecutorService
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
        // Blocking pipe I/O belongs on the platform's process-I/O pool, not the common pool.
        val io = ProcessIOExecutorService.INSTANCE
        val stdout = CompletableFuture.supplyAsync({ process.inputStream.readBytes() }, io)
        val stderr = CompletableFuture.supplyAsync({ process.errorStream.readBytes() }, io)
        val feeder = CompletableFuture.runAsync({
            process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
        }, io)

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
                // Kill the whole tree, not just the direct child: the formatter is a process
                // chain (haxelib/lix shim → neko run.n → node run.js), and an orphaned
                // descendant keeps the stdout/stderr pipe write-ends open — waitFor() would
                // then block on the stream reads until the orphan exits. (Same reason the
                // destroy test failed on Linux: dash forks for `sh -c`, unlike macOS bash.)
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
