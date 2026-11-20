// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.jetbrains.dart.analysisServer;

import com.intellij.codeInsight.navigation.GotoTargetHandler;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.util.Ref;
import com.jetbrains.lang.dart.sdk.DartConfigurable;
import com.intellij.psi.PsiNamedElement;
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase;
import com.intellij.testFramework.fixtures.CodeInsightTestUtil;
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.util.DartTestUtils;

import java.util.List;

public class DartGotoImplementationTest extends CodeInsightFixtureTestCase {
    @Override
    public void setUp() throws Exception {
        super.setUp();
        DartTestUtils.configureDartSdk(myModule, myFixture.getTestRootDisposable(), true);
        myFixture.setTestDataPath(DartTestUtils.BASE_TEST_DATA_PATH + getBasePath());
        ((CodeInsightTestFixtureImpl) myFixture).canChangeDocumentDuringHighlighting(true);
    }

    @Override
    protected String getBasePath() {
        return FileUtil.toSystemDependentName("/analysisServer/gotoImplementation");
    }

    protected void doTest(int expectedLength) {
        final boolean previous = DartConfigurable.isExperimentalLspFeaturesEnabled(getProject());
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                DartConfigurable.setExperimentalLspFeaturesEnabled(getProject(), enabled);
                myFixture.configureByFile(getTestName(false) + ".dart");
                myFixture.doHighlighting();
                if (enabled) {
                    startBridge();
                }
                doTestInner(expectedLength);
            }
        } finally {
            DartConfigurable.setExperimentalLspFeaturesEnabled(getProject(), previous);
        }
    }

    private void startBridge() {
        // Production startup deliberately skips the bridge in unit-test mode.
        final var opened = new java.util.concurrent.atomic.AtomicBoolean();
        com.intellij.platform.dartlsp.api.LspServerManager.getInstance(getProject()).addLspServerManagerListener(
            new com.intellij.platform.dartlsp.api.LspServerManagerListener() {
                @Override
                public void fileOpened(com.intellij.platform.dartlsp.api.LspServer server,
                                       com.intellij.openapi.vfs.VirtualFile file) {
                    if (file.equals(myFixture.getFile().getVirtualFile())) opened.set(true);
                }
            }, myFixture.getTestRootDisposable(), true);
        getProject().getService(com.jetbrains.lang.dart.lsp.DartBridgeLspServerManager.class).startBridgeServer();
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Bridge opened source", opened::get, 10);
    }

    public void testResolvedReferenceUsesPlatformImplementation() {
        DartConfigurable.setExperimentalLspFeaturesEnabled(getProject(), true);
        myFixture.configureByText("reference.dart",
            "abstract class Base {}\nclass Concrete implements Base {}\nvoid main(Ba<caret>se value) {}\n");
        myFixture.doHighlighting();
        final var reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(reference);
        assertNotNull("The real Dart reference must resolve before the action", reference.resolve());
        startBridge();
        doTestInner(1);
    }

    private void doTestInner(int expectedLength) {
        final var action = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_IMPLEMENTATION);
        final var context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .add(CommonDataKeys.PSI_FILE, myFixture.getFile()).build();
        final var event = AnActionEvent.createFromAnAction(action, null, "test", context);
        final Ref<GotoTargetHandler.GotoData> result = Ref.create();
        ActionUtil.performDumbAwareWithCallbacks(action, event, () ->
            result.set(CodeInsightTestUtil.gotoImplementation(myFixture.getEditor(), myFixture.getFile())));
        final GotoTargetHandler.GotoData data = result.get();
        assertNotNull("LSP enabled=" + DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()) + ": " + myFixture.getFile(), data);
        assertEquals("LSP enabled=" + DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()), expectedLength, data.targets.length);
    }

    public void testGti1() {
        doTest(2);
    }

    public void testGti2() {
        doTest(1);
    }

    public void testGti3() {
        doTest(2);
    }

    public void testGti4() {
        doTest(1);
    }

    public void testMixin1() {
        doTest(1);
    }

    public void testOperator() {
        doTest(3);
    }

    // See: https://github.com/flutter/dart-intellij-third-party/issues/86
    public void ignore_testIterableSubclasses() throws Throwable {
        myFixture.configureByText("foo.dart", "Iterable i;");
        myFixture.doHighlighting();
        final DartSdk sdk = DartSdk.getDartSdk(getProject());
        assertNotNull(sdk);

        final GotoTargetHandler.GotoData data = CodeInsightTestUtil.gotoImplementation(myFixture.getEditor(), myFixture.getFile());
        final List<String> actual = ContainerUtil.map(data.targets,
                psiElement -> psiElement instanceof PsiNamedElement
                        ? ((PsiNamedElement) psiElement).getName()
                        : psiElement.toString());

        assertContainsElements(actual, "UnmodifiableListView", "ListBase", "Set", "_SplayTreeValueIterable", "LinkedHashSet", "Runes", "List");
    }
}
