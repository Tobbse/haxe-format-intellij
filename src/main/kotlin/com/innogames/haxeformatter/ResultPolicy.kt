package com.innogames.haxeformatter

sealed interface FormatOutcome {
    data class ApplyText(val text: String) : FormatOutcome
    object NoChange : FormatOutcome
    data class Error(val title: String, val message: String) : FormatOutcome
}

/**
 * Maps a formatter CLI result onto a document action (design §5.3).
 * Apply ONLY on exit 0; exit 1 (excluded/disableFormatting) is a silent no-op;
 * anything else (2 format failure, 3 usage/path, 255 = Neko/Node -1) is an error
 * that must leave the buffer untouched.
 */
object ResultPolicy {
    /** Also the service display name; must match the `notificationGroup` id in plugin.xml. */
    const val NOTIFICATION_TITLE = "Haxe Formatter"

    fun decide(input: String, result: FormatterResult): FormatOutcome = when (result.exitCode) {
        0 -> {
            val normalised = normaliseTrailingNewline(result.stdout)
            if (normalised == input) FormatOutcome.NoChange else FormatOutcome.ApplyText(normalised)
        }
        1 -> FormatOutcome.NoChange
        else -> FormatOutcome.Error(
            NOTIFICATION_TITLE,
            result.stderr.ifBlank { "haxe-formatter exited with code ${result.exitCode}" },
        )
    }

    /**
     * The CLI emits its result with Sys.println, appending exactly one '\n' beyond the
     * formatted text (design §5.3 ⚠). Strip exactly one. The empirical rule is pinned
     * permanently by the idempotency + trailing-newline integration tests (Task 11).
     */
    fun normaliseTrailingNewline(stdout: String): String = stdout.removeSuffix("\n")
}
