/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package com.jetbrains.lang.dart.lsp

import com.google.dart.server.AnalysisServerSocket
import com.google.dart.server.GetTypeHierarchyConsumer
import com.google.dart.server.internal.remote.ByteLineReaderStream
import com.google.dart.server.internal.remote.RemoteAnalysisServerImpl
import com.google.dart.server.internal.remote.RequestSink
import com.google.dart.server.internal.remote.ResponseStream
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dartlsp.api.LspCommunicationChannel
import com.intellij.platform.dartlsp.api.LspServerDescriptor
import com.intellij.platform.dartlsp.api.LspServerManager
import com.intellij.platform.dartlsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.jetbrains.lang.dart.DartCodeInsightFixtureTestCase
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService
import com.jetbrains.lang.dart.sdk.DartConfigurable
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Exercises the real action and rich Dart PSI, not a manually constructed POM target. */
class DartLspImplementationNavigationTest : DartCodeInsightFixtureTestCase() {
    fun testDeclarationActionUsesImplementationAtExactCrossFileOffset() = withSession { session ->
        session.navigate("abstract class So<caret>urce {}")
    }

    fun testReferenceActionUsesImplementationAtCaretRatherThanResolvedDeclaration() = withSession { session ->
        session.navigate("abstract class Source {}\nvoid main(Source value) { Sou<caret>rce other = value; }")
    }

    fun testEmptyResponseDoesNotNavigateOrFallBack() = withSession { session ->
        session.result = Either.forLeft(emptyList())
        session.reject()
    }

    fun testNullResponseDoesNotNavigateOrFallBack() = withSession { session ->
        session.result = null
        session.reject()
    }

    fun testErrorResponseDoesNotNavigateOrFallBack() = withSession { session ->
        session.error = ResponseErrorException(ResponseError(-32603, "Unavailable", null))
        session.reject()
    }

    fun testMethodNotFoundDoesNotNavigateOrFallBack() = withSession { session ->
        session.error = ResponseErrorException(ResponseError(-32601, "Unavailable", null))
        session.reject()
    }

    fun testLocationAndLocationLinkResponsesNormalizeAndDeduplicate() = withSession { session ->
        session.checkNormalizedTargets()
    }

    fun testDynamicImplementationCapabilityIsRespected() = withSession(capability = null) { session ->
        session.registerImplementation()
        session.navigate("abstract class So<caret>urce {}")
    }

    fun testDefinitionTargetLookupIsUnchanged() = withSession { it.preserveTargetLookup("GotoDeclaration") }
    fun testTypeDefinitionTargetLookupIsUnchanged() = withSession { it.preserveTargetLookup("GotoTypeDeclaration") }
    fun testSuperTargetLookupIsUnchanged() = withSession { it.preserveTargetLookup("GotoSuperMethod") }

    fun testMissingCapabilityDoesNotIntercept() = withSession(capability = null) { it.noInterception() }
    fun testDisabledCapabilityDoesNotIntercept() = withSession(capability = false) { it.noInterception() }
    fun testDisabledCustomizerPreservesLegacyAction() = withSession(enabled = false) { it.noInterception() }

    private fun withSession(enabled: Boolean = true, capability: Boolean? = true, action: (Session) -> Unit) {
        val previous = DartConfigurable.isExperimentalLspFeaturesEnabled(project)
        DartConfigurable.setExperimentalLspFeaturesEnabled(project, enabled)
        try {
            Session(capability).use { session ->
                session.connect()
                action(session)
            }
        } finally {
            DartConfigurable.setExperimentalLspFeaturesEnabled(project, previous)
        }
    }

    private inner class Session(private val capability: Boolean?) : AutoCloseable {
        private val target = myFixture.addFileToProject("target.dart", "// target\nclass Concrete {}\nclass Other {}\n").virtualFile
        private val listener = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool()
        private var socket: java.net.Socket? = null
        private var listening: java.util.concurrent.Future<Void>? = null
        private lateinit var client: LanguageClient
        private val manager = LspServerManager.getInstance(project)
        private val das = DartAnalysisServerService.getInstance(project)
        private val requests = CopyOnWriteArrayList<ImplementationParams>()
        private val hierarchy = CopyOnWriteArrayList<Triple<String, Int, Boolean>>()
        private var source: VirtualFile? = null
        var result: Either<List<Location>, List<LocationLink>>? = Either.forLeft(
            listOf(Location(target.url, Range(Position(1, 6), Position(1, 14)))))
        var error: Throwable? = null
        private val legacy = object : RemoteAnalysisServerImpl(object : AnalysisServerSocket {
            override fun getErrorStream(): ByteLineReaderStream? = null
            override fun getRequestSink(): RequestSink? = null
            override fun getResponseStream(): ResponseStream? = null
            override fun isOpen() = true
            override fun start() {}
            override fun stop() {}
        }) {
            override fun search_getTypeHierarchy(file: String, offset: Int, superOnly: Boolean, consumer: GetTypeHierarchyConsumer) {
                hierarchy.add(Triple(file, offset, superOnly))
                consumer.computedHierarchy(emptyList())
            }
            override fun server_shutdown() {}
            override fun server_openUrlRequest(url: String?) {}
            override fun server_showMessageRequest(
                messageType: String?, message: String?, actions: MutableList<org.dartlang.analysis.server.protocol.MessageAction>?,
                consumer: com.google.dart.server.ShowMessageRequestConsumer?
            ) {}
            override fun lsp_workspaceApplyEdit(
                params: org.dartlang.analysis.server.protocol.DartLspApplyWorkspaceEditParams?,
                consumer: com.google.dart.server.DartLspWorkspaceApplyEditRequestConsumer?
            ) {}
        }

        fun connect() {
            das.setServer(legacy)
            assertEmpty(das.search_getTypeHierarchy(target, 0, false))
            assertEquals(listOf(Triple(das.getFileUri(target), 0, false)), hierarchy.toList())
            hierarchy.clear()
            val accepted = ApplicationManager.getApplication().executeOnPooledThread(Callable { listener.accept() })
            val descriptor = object : LspServerDescriptor(project, "Implementation test") {
                override val lspCustomization = DartLspServerDescriptor(project).lspCustomization
                override val lspCommunicationChannel = LspCommunicationChannel.Socket(listener.localPort, startProcess = false)
                override fun isSupportedFile(file: VirtualFile) = file.extension == "dart"
                override fun getFileUri(file: VirtualFile) = file.url
                override fun findFileByUri(fileUri: String) = when (fileUri) {
                    target.url -> target
                    source?.url -> source
                    else -> null
                }
            }
            manager.ensureServerStarted(DartLspServerSupportProvider::class.java, descriptor)
            waitFor("Socket accepted") { accepted.isDone }
            val connected = accepted.get().also { socket = it }
            val endpoint = object : com.intellij.platform.dartlsp.api.Lsp4jServer, TextDocumentService, WorkspaceService {
                override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
                    requests.add(params)
                    return error?.let { CompletableFuture.failedFuture(it) } ?: CompletableFuture.completedFuture(result)
                }
                override fun initialize(params: InitializeParams) = CompletableFuture.completedFuture(InitializeResult(ServerCapabilities().apply {
                    capability?.let { setImplementationProvider(it) }
                }))
                override fun shutdown() = CompletableFuture.completedFuture<Any>(null)
                override fun exit() {}
                override fun getTextDocumentService(): TextDocumentService = this
                override fun getWorkspaceService(): WorkspaceService = this
                override fun didOpen(params: DidOpenTextDocumentParams) {}
                override fun didChange(params: DidChangeTextDocumentParams) {}
                override fun didClose(params: DidCloseTextDocumentParams) {}
                override fun didSave(params: DidSaveTextDocumentParams) {}
                override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}
                override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
            }
            val launcher = Launcher.Builder<LanguageClient>().setLocalService(endpoint).setRemoteInterface(LanguageClient::class.java)
                .setInput(connected.getInputStream()).setOutput(connected.getOutputStream()).setExecutorService(executor).create()
            client = launcher.remoteProxy
            listening = launcher.startListening()
            waitFor("Running server") { manager.getServersForProvider(DartLspServerSupportProvider::class.java).any { it.state == LspServerState.Running } }
        }

        fun registerImplementation() {
            val registered = client.registerCapability(RegistrationParams(listOf(
                Registration("implementation-test", "textDocument/implementation", ImplementationRegistrationOptions()))))
            waitFor("Dynamic implementation registered") { registered.isDone }
            registered.get()
        }

        private fun invoke(text: String = "abstract class So<caret>urce {}", collectTargets: Boolean = false): Array<com.intellij.psi.PsiElement>? {
            source = myFixture.configureByText("source.dart", text).virtualFile
            manager.getServersForProvider(DartLspServerSupportProvider::class.java).forEach {
                (it as com.intellij.platform.dartlsp.impl.LspServerImpl).documentSyncManager.openForOpenedOrUnsavedFiles()
            }
            NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
            // Startup/file-open notifications must finish before the action chooses its provider.
            waitFor("Source opened by LSP") {
                com.intellij.platform.dartlsp.impl.LspServerManagerImpl.getInstanceImpl(project)
                    .getServersWithThisFileOpen(requireNotNull(source)).isNotEmpty()
            }
            if (!collectTargets) {
                myFixture.performEditorAction(IdeActions.ACTION_GOTO_IMPLEMENTATION)
                return null
            }
            val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_IMPLEMENTATION)
            val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, myFixture.file).build()
            val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "test", context)
            var targets: Array<com.intellij.psi.PsiElement>? = null
            com.intellij.openapi.actionSystem.ex.ActionUtil.performDumbAwareWithCallbacks(action, event) {
                targets = com.intellij.testFramework.fixtures.CodeInsightTestUtil.gotoImplementation(myFixture.editor, myFixture.file)?.targets
            }
            return targets
        }

        fun preserveTargetLookup(actionId: String) {
            myFixture.configureByText("source.dart", "abstract class So<caret>urce {}")
            val action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(actionId)
            assertNotNull(action)
            val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, myFixture.file).build()
            val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "test", context)
            var target: com.intellij.psi.PsiElement? = null
            com.intellij.openapi.actionSystem.ex.ActionUtil.performDumbAwareWithCallbacks(action, event) {
                target = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.editor,
                    com.intellij.codeInsight.TargetElementUtil.getInstance().allAccepted)
            }
            assertTrue(target is com.jetbrains.lang.dart.psi.DartComponentName)
            assertEquals("Source", (target as com.jetbrains.lang.dart.psi.DartComponentName).name)
            assertEquals(15, requireNotNull(target).textOffset)
            assertEmpty(requests)
            assertEmpty(hierarchy)
        }

        fun checkNormalizedTargets() {
            val ranges = listOf(Range(Position(1, 6), Position(1, 14)), Range(Position(2, 6), Position(2, 11)))
            for (links in listOf(false, true)) {
                result = if (links) Either.forRight((ranges + ranges.first()).map {
                    LocationLink(target.url, Range(Position(0, 0), Position(2, 14)), it)
                }) else Either.forLeft((ranges + ranges.first()).map { Location(target.url, it) })
                val targets = requireNotNull(invoke(collectTargets = true))
                assertEquals(2, targets.size)
                assertEquals(listOf("Concrete", "Other"), targets.map { (it as com.intellij.psi.PsiNamedElement).name })
                assertEquals(listOf(16, 34), targets.map { it.textOffset })
                assertTrue(targets.all { it.containingFile.virtualFile == target })
                assertFalse(FileEditorManager.getInstance(project).isFileOpen(target))
                assertEmpty(hierarchy)
            }
        }

        fun navigate(text: String) {
            val caret = text.indexOf("<caret>")
            invoke(text)
            waitFor("Implementation navigated") { FileEditorManager.getInstance(project).selectedFiles.contains(target) }
            // The handler may query both its initial and deep-search phases; neither is a cached Dart request.
            assertTrue(requests.isNotEmpty())
            val prefix = text.substring(0, caret)
            requests.forEach { request ->
                assertEquals(source!!.url, request.textDocument.uri)
                assertEquals(Position(prefix.count { it == '\n' }, prefix.substringAfterLast('\n').length), request.position)
            }
            assertEquals("// target\nclass ".length,
                         FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.offset)
            assertEmpty("LSP must not request legacy hierarchy", hierarchy)
        }

        fun reject() {
            invoke()
            assertFalse(FileEditorManager.getInstance(project).isFileOpen(target))
            assertEquals(source, FileEditorManager.getInstance(project).selectedFiles.single())
            assertEquals("abstract class So".length, myFixture.editor.caretModel.offset)
            assertEquals("abstract class Source {}", myFixture.editor.document.text)
            assertEquals(1, requests.size)
            assertEmpty(hierarchy)
        }

        fun noInterception() {
            invoke()
            assertEmpty("Unavailable LSP implementation must not receive a request", requests)
            assertFalse(FileEditorManager.getInstance(project).isFileOpen(target))
            if (!DartConfigurable.isExperimentalLspFeaturesEnabled(project)) {
                assertTrue("OFF must still reach the legacy hierarchy", hierarchy.isNotEmpty())
            }
        }

        override fun close() {
            try {
                NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
                manager.stopServers(DartLspServerSupportProvider::class.java)
                waitFor("Stopped server") { manager.getServersForProvider(DartLspServerSupportProvider::class.java).none { it.state == LspServerState.Running } }
            } finally {
                das.setServer(null)
                socket?.close()
                listener.close()
                listening?.let { waitFor("Listener stopped") { it.isDone } }
                executor.shutdownNow()
                waitFor("Executor stopped") { executor.isTerminated }
            }
        }
    }

    private fun waitFor(description: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching(description, condition, 10)
}
