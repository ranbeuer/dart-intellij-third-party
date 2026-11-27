/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package com.jetbrains.lang.dart.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.platform.dartlsp.api.customization.LspDiagnosticsDisabled
import com.jetbrains.lang.dart.DartBundle
import com.jetbrains.lang.dart.DartCodeInsightFixtureTestCase
import com.jetbrains.lang.dart.sdk.DartSdk
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticCodeDescription
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class DartLspDiagnosticsSupportTest : DartCodeInsightFixtureTestCase() {

    override fun tearDown() {
        try {
            PropertiesComponent.getInstance(project).unsetValue("dart.lsp.experimental.enabled")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testGetMessageCollapsesNewlinesForProblemsView() {
        val diagnostic = Diagnostic(
            Range(Position(2, 2), Position(2, 14)),
            "Undefined name 'brokenSyntax'.\nTry importing the library that defines 'brokenSyntax', correcting the name to the name of an existing, or defining a name named 'brokenSyntax'.",
            DiagnosticSeverity.Error,
            "dart",
            "undefined_identifier"
        )

        assertEquals(
            "Undefined name 'brokenSyntax'. Try importing the library that defines 'brokenSyntax', correcting the name to the name of an existing, or defining a name named 'brokenSyntax'.",
            DartLspDiagnosticsSupport.getMessage(diagnostic)
        )
    }

    fun testGetMessageCollapsesCrLfSequences() {
        val diagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "First sentence.\r\n\r\nSecond sentence.",
            DiagnosticSeverity.Warning,
            "dart",
            "some_warning"
        )

        assertEquals("First sentence. Second sentence.", DartLspDiagnosticsSupport.getMessage(diagnostic))
    }

    fun testGetTooltipFormatsHtmlMatchingLegacyProblemTooltip() {
        val diagnostic = Diagnostic(
            Range(Position(2, 2), Position(2, 14)),
            "A value of type 'List<String>' can't be assigned to a variable of type 'List<int>'.\nTry changing the type of the variable.",
            DiagnosticSeverity.Error,
            "dart",
            "invalid_assignment"
        ).apply {
            codeDescription = DiagnosticCodeDescription("https://dart.dev/diagnostics/invalid_assignment")
            relatedInformation = listOf(
                DiagnosticRelatedInformation(
                    Location("file:///project/lib/helper.dart", Range(Position(9, 4), Position(9, 10))),
                    "The declaration of 'helper' is here."
                )
            )
        }

        val docLinkText = DartBundle.message("error.doc.link")
        val tooltip = DartLspDiagnosticsSupport.getTooltip(diagnostic)

        assertTrue(tooltip.startsWith("<html>") && tooltip.endsWith("</html>"))
        assertTrue("Angle brackets in generic types must be HTML-escaped", tooltip.contains("List&lt;String&gt;") && tooltip.contains("List&lt;int&gt;"))
        assertTrue("Documentation link must be rendered", tooltip.contains("<a href=\"https://dart.dev/diagnostics/invalid_assignment\">$docLinkText</a>"))
        assertTrue("Related information must render 1-based line number", tooltip.contains("<li>The declaration of &#39;helper&#39; is here (helper.dart:10).</li>") || tooltip.contains("<li>The declaration of 'helper' is here (helper.dart:10).</li>"))
        assertTrue("Correction hint must be separated with <br/>", tooltip.contains("<br/>Try changing the type of the variable."))
    }

    fun testGetHighlightSeverityFiltersTodoDiagnostics() {
        val todoDiagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 10)),
            "TODO: refactor",
            DiagnosticSeverity.Information,
            "dart",
            "todo"
        )
        assertNull("TODO diagnostics should be ignored so they are not shown as warnings in Problems view", DartLspDiagnosticsSupport.getHighlightSeverity(todoDiagnostic))

        val errorDiagnostic = Diagnostic(
            Range(Position(0, 0), Position(0, 5)),
            "Error",
            DiagnosticSeverity.Error,
            "dart",
            "compile_error"
        )
        assertEquals(HighlightSeverity.ERROR, DartLspDiagnosticsSupport.getHighlightSeverity(errorDiagnostic))
    }

    private fun setSdkVersion(version: String) {
        val sdk = DartSdk.getDartSdk(project)
        assertNotNull(sdk)
        val versionField = DartSdk::class.java.getDeclaredField("myVersion").apply { isAccessible = true }
        versionField.set(sdk, version)
    }

    fun testDiagnosticsCustomizerFollowsPublishDiagnosticsGate() {
        val descriptor = DartLspServerDescriptor(project)
        val originalVersion = DartSdk.getDartSdk(project)?.version

        try {
            setSdkVersion("3.14.0-137.0.dev")
            PropertiesComponent.getInstance(project).setValue("dart.lsp.experimental.enabled", true, true)
            assertSame(DartLspDiagnosticsSupport, descriptor.lspCustomization.diagnosticsCustomizer)

            setSdkVersion("3.13.0")
            assertSame(LspDiagnosticsDisabled, descriptor.lspCustomization.diagnosticsCustomizer)

            setSdkVersion("3.14.0-137.0.dev")
            PropertiesComponent.getInstance(project).setValue("dart.lsp.experimental.enabled", false, true)
            assertSame(LspDiagnosticsDisabled, descriptor.lspCustomization.diagnosticsCustomizer)
        } finally {
            if (originalVersion != null) {
                setSdkVersion(originalVersion)
            }
        }
    }
}
