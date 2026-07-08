package com.innogames.haxeformatter

import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class HaxeExternalFormattingServiceTest : BasePlatformTestCase() {

    private val ep = ExtensionPointName.create<FormattingService>("com.intellij.formattingService")
    private lateinit var fake: FakeFormatterExecutor

    private fun install(result: FormatterResult): HaxeExternalFormattingService {
        fake = FakeFormatterExecutor(result)
        val service = HaxeExternalFormattingService(fake, FormatterContextResolver()) {
            mapOf("PATH" to "/usr/bin:/bin")
        }
        // Ours first, then the existing services (CoreFormattingService stays as fallback).
        ExtensionTestUtil.maskExtensions(ep, listOf(service) + ep.extensionList, testRootDisposable)
        return service
    }

    /**
     * Runs Reformat Code through the real pipeline and returns everything the platform's
     * FormattingNotificationService reported. In headless/test mode onError is routed to
     * HeadlessNotificationService, which prints to System.err — Notifications.TOPIC never
     * fires — so error reporting is observed by capturing stderr.
     */
    private fun reformatCapturingErrors(): String {
        val captured = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(captured, true))
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                CodeStyleManager.getInstance(project).reformatText(myFixture.file, 0, myFixture.file.textLength)
            }
            UIUtil.dispatchAllInvocationEvents()
        } finally {
            System.setErr(originalErr)
        }
        return captured.toString()
    }

    // --- canFormat -----------------------------------------------------------

    fun `test canFormat accepts hx files by extension without the Haxe plugin`() {
        val service = install(FormatterResult("", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        assertTrue(service.canFormat(myFixture.file))
    }

    fun `test canFormat rejects non-Haxe files`() {
        val service = install(FormatterResult("", "", 0))
        myFixture.configureByText("Foo.txt", "hello")
        assertFalse(service.canFormat(myFixture.file))
    }

    // --- exit-code behaviour through the real reformat pipeline ---------------

    fun `test exit 0 replaces the document with normalised stdout`() {
        install(FormatterResult("class Foo {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        val errors = reformatCapturingErrors()
        assertEquals("class Foo {}\n", myFixture.editor.document.text)
        assertFalse(errors.contains(ResultPolicy.NOTIFICATION_TITLE))
    }

    fun `test exit 1 leaves the document untouched with no notification`() {
        install(FormatterResult("class Foo{}\n", "", 1))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        val errors = reformatCapturingErrors()
        assertEquals("class Foo{}", myFixture.editor.document.text)
        assertFalse(errors.contains(ResultPolicy.NOTIFICATION_TITLE))
    }

    fun `test exit 2 leaves the document untouched and raises a notification`() {
        install(FormatterResult("class Foo{}\n", "Parse error line 1", 2))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        val errors = reformatCapturingErrors()
        assertEquals("class Foo{}", myFixture.editor.document.text)
        assertTrue("expected the formatter error to be reported, got: $errors", errors.contains("Parse error"))
    }

    // --- wiring: what reaches the executor ------------------------------------

    fun `test executor receives document text on stdin and the stdin flags`() {
        install(FormatterResult("class Foo {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class Foo{}")
        reformatCapturingErrors()
        assertEquals("class Foo{}", fake.lastStdin)
        val argv = fake.lastCommand!!.argv
        assertEquals(listOf("haxelib", "run", "formatter", "--stdin", "-s"), argv.dropLast(1))
        assertTrue(argv.last().endsWith("Foo.hx"))
    }

    // --- D6: selection formats the whole file ---------------------------------

    fun `test selection reformat formats the whole file`() {
        install(FormatterResult("class A {}\nclass B {}\n\n", "", 0))
        myFixture.configureByText("Foo.hx", "class A{}\n<selection>class B{}</selection>")
        myFixture.performEditorAction("ReformatCode")
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("class A {}\nclass B {}\n", myFixture.editor.document.text)
    }

    // --- cancellation ----------------------------------------------------------

    fun `test cancel destroys the running process`() {
        val service = install(FormatterResult("", "", 0))
        fake.blockUntil = java.util.concurrent.CountDownLatch(1)
        myFixture.configureByText("Foo.hx", "class Foo{}")

        val request = TestFormattingRequests.forFile(myFixture.file, myFixture.editor.document.text)
        // createFormattingTask/FormattingTask are protected on 2025.3 — drive the job directly.
        val task = service.createJob(request)!!
        val runner = kotlin.concurrent.thread { task.run() }
        // Let run() reach waitFor(), then cancel.
        Thread.sleep(200)
        assertTrue(task.cancel())
        runner.join(5_000)
        assertTrue(fake.destroyed)
    }
}
