package com.innogames.haxeformatter

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile
import com.intellij.util.EnvironmentUtil
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumSet

/**
 * Routes Reformat Code for Haxe files through the haxe-formatter CLI (design §3, §4).
 * - FORMAT_FRAGMENTS declared, ranges ignored: selection ⌘⌥L formats the whole file (D6).
 * - AD_HOC_FORMATTING deliberately NOT declared: never runs on typing/autosave.
 * - No LanguageFormattingRestriction anywhere (would grey out the action — design §3).
 * - request.getIOFile() deliberately unused: config discovery and excludes regexes key
 *   off the REAL path (design §5.1), which we pass via -s.
 */
class HaxeExternalFormattingService(
    private val executor: FormatterExecutor,
    private val resolver: FormatterContextResolver,
    private val baseEnvProvider: () -> Map<String, String>,
) : AsyncDocumentFormattingService() {

    /** Used by the platform when instantiating from plugin.xml. */
    @Suppress("unused")
    constructor() : this(
        CliFormatterExecutor(),
        FormatterContextResolver(),
        { EnvironmentUtil.getEnvironmentMap() }, // macOS login-shell PATH capture (design §5.4)
    )

    override fun getFeatures(): Set<FormattingService.Feature> =
        EnumSet.of(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean =
        file.language.id == "Haxe" || file.name.endsWith(".hx", ignoreCase = true)

    override fun getName(): String = ResultPolicy.NOTIFICATION_TITLE

    override fun getNotificationGroupId(): String = ResultPolicy.NOTIFICATION_TITLE

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val job = createJob(request) ?: return null
        // FormattingTask is a protected nested type on 2025.3 — wrap the (testable) job.
        return object : FormattingTask {
            override fun run() = job.run()
            override fun cancel(): Boolean = job.cancel()
            override fun isRunUnderProgress(): Boolean = true
        }
    }

    /**
     * The actual formatting task, publicly typed so tests can drive run()/cancel()
     * directly (AsyncDocumentFormattingService.FormattingTask is protected on 2025.3).
     */
    internal fun createJob(request: AsyncFormattingRequest): FormattingJob? {
        // EDT — snapshot only, no I/O (design §3).
        val file = request.context.containingFile
        val virtualFile = file.virtualFile ?: return null // let the built-in engine handle scratch-like files
        return FormattingJob(request, request.documentText, Paths.get(virtualFile.path))
    }

    internal inner class FormattingJob(
        private val request: AsyncFormattingRequest,
        private val documentText: String,
        private val filePath: Path,
    ) {
        @Volatile private var process: FormatterProcess? = null

        @Volatile private var cancelled = false

        fun run() { // pooled background thread
            try {
                val context = resolver.resolve(filePath)
                val command = FormatterCommandBuilder.build(context, filePath, baseEnvProvider())
                val started = executor.start(command, documentText)
                process = started
                if (cancelled) {
                    started.destroy()
                    return
                }
                when (val outcome = ResultPolicy.decide(documentText, started.waitFor())) {
                    is FormatOutcome.ApplyText -> request.onTextReady(outcome.text)
                    FormatOutcome.NoChange -> request.onTextReady(null)
                    is FormatOutcome.Error -> {
                        resolver.invalidate(filePath) // stale root? re-discover next time (design §4)
                        request.onError(outcome.title, outcome.message)
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    resolver.invalidate(filePath)
                    request.onError(ResultPolicy.NOTIFICATION_TITLE, e.message ?: e.javaClass.simpleName)
                }
            }
        }

        fun cancel(): Boolean {
            cancelled = true
            process?.destroy()
            return true
        }
    }
}
