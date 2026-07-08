package com.innogames.haxeformatter

import java.io.File
import java.nio.file.Path

/**
 * Builds the CLI invocation (design §5.4):
 *   haxelib run formatter --stdin -s <absoluteFilePath>
 * cwd = configRoot; PATH gets <root>/node_modules/.bin prepended (lix haxeshim).
 * The base env is injected (production: EnvironmentUtil.getEnvironmentMap()).
 */
object FormatterCommandBuilder {
    fun build(context: FormatterContext, filePath: Path, baseEnv: Map<String, String>): FormatterCommand {
        val env = baseEnv.toMutableMap()
        val bin = context.nodeBinDir
        if (bin != null) {
            val existing = env["PATH"].orEmpty()
            env["PATH"] = if (existing.isEmpty()) bin.toString()
                          else "$bin${File.pathSeparator}$existing"
        }
        return FormatterCommand(
            argv = listOf("haxelib", "run", "formatter", "--stdin", "-s", filePath.toAbsolutePath().toString()),
            workDir = context.configRoot,
            environment = env,
        )
    }
}
