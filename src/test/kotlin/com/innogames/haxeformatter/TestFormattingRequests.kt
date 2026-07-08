package com.innogames.haxeformatter

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingMode
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.io.File

object TestFormattingRequests {
    fun forFile(file: PsiFile, text: String): AsyncFormattingRequest = object : AsyncFormattingRequest {
        override fun getDocumentText(): String = text
        override fun getFormattingRanges(): List<TextRange> = listOf(TextRange(0, text.length))
        override fun getIOFile(): File? = null
        override fun isQuickFormat(): Boolean = false
        override fun canChangeWhitespaceOnly(): Boolean = false
        override fun onTextReady(updatedText: String?) {}
        override fun onError(title: String, message: String) {}
        override fun onError(title: String, message: String, offset: Int) {}
        override fun getContext(): FormattingContext =
            FormattingContext.create(file, TextRange(0, text.length), CodeStyle.getSettings(file), FormattingMode.REFORMAT)
    }
}
