// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.lang.dart.ide.runner;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.Problem;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.ide.errorTreeView.DartProblemsView;
import com.jetbrains.lang.dart.sdk.DartConfigurable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DartExecutionHelperTest extends BasePlatformTestCase {

  private TestWolfSolver myWolfSolver;

  private static class TestWolfSolver extends WolfTheProblemSolver {
    private final Set<VirtualFile> problemFiles = new HashSet<>();

    @Override
    public boolean isProblemFile(VirtualFile file) {
      return problemFiles.contains(file);
    }

    @Override
    public void weHaveGotProblems(VirtualFile virtualFile, List<? extends Problem> problems) {
      problemFiles.add(virtualFile);
    }

    @Override
    public void weHaveGotNonIgnorableProblems(VirtualFile virtualFile, List<? extends Problem> problems) {
      problemFiles.add(virtualFile);
    }

    @Override
    public boolean hasProblemFilesBeneath(Condition<? super VirtualFile> condition) {
      for (VirtualFile file : problemFiles) {
        if (condition.value(file)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean hasSyntaxErrors(VirtualFile file) {
      return false;
    }

    @Override
    public boolean hasProblemFilesBeneath(Module scope) {
      return false;
    }

    @Override
    public void clearProblems(VirtualFile file) {
      problemFiles.remove(file);
    }

    @Override
    public Problem convertToProblem(VirtualFile file, int line, int column, String[] message) {
      return null;
    }

    @Override
    public void reportProblems(VirtualFile file, Collection<? extends Problem> problems) {
      problemFiles.add(file);
    }

    @Override
    public void reportProblemsFromExternalSource(VirtualFile file, Object source) {
      problemFiles.add(file);
    }

    @Override
    public void clearProblemsFromExternalSource(VirtualFile file, Object source) {
      problemFiles.remove(file);
    }

    @Override
    public void queue(VirtualFile file) {}
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    myWolfSolver = new TestWolfSolver();
    ServiceContainerUtil.replaceService(getProject(), WolfTheProblemSolver.class, myWolfSolver, getTestRootDisposable());
  }

  @Override
  public void tearDown() throws Exception {
    try {
      PropertiesComponent.getInstance(getProject()).unsetValue("dart.lsp.experimental.enabled");
      DartProblemsView.getInstance(getProject()).clearNotifications();
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  public void testHasErrorsLspEnabledWithoutErrors() {
    assertTrue(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    VirtualFile file = myFixture.addFileToProject("lib/main.dart", "void main() {}\n").getVirtualFile();
    GlobalSearchScope scope = DartExecutionHelper.getScopeOfFilesThatMayAffectExecution(getProject(), file);
    assertNotNull(scope);

    assertFalse(DartExecutionHelper.hasErrors(getProject(), scope));
  }

  public void testHasErrorsLspEnabledWithWolfReportedError() {
    assertTrue(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    VirtualFile file = myFixture.addFileToProject("lib/main.dart", "void main() {}\n").getVirtualFile();
    GlobalSearchScope scope = DartExecutionHelper.getScopeOfFilesThatMayAffectExecution(getProject(), file);
    assertNotNull(scope);

    myWolfSolver.reportProblemsFromExternalSource(file, "LSP");
    assertTrue(DartExecutionHelper.hasErrors(getProject(), scope));
  }

  public void testHasErrorsLspDisabledDoesNotQueryWolf() {
    PropertiesComponent.getInstance(getProject()).setValue("dart.lsp.experimental.enabled", false, true);
    assertFalse(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    VirtualFile file = myFixture.addFileToProject("lib/main.dart", "void main() {}\n").getVirtualFile();
    GlobalSearchScope scope = DartExecutionHelper.getScopeOfFilesThatMayAffectExecution(getProject(), file);
    assertNotNull(scope);

    // Even if Wolf solver has problems, legacy DAS has none in this test setup
    myWolfSolver.reportProblemsFromExternalSource(file, "LSP");
    assertFalse(DartExecutionHelper.hasErrors(getProject(), scope));
  }

  public void testDisplayIssuesLspEnabledNoErrorsDoesNotNotify() {
    assertTrue(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    VirtualFile file = myFixture.addFileToProject("lib/main.dart", "void main() {}\n").getVirtualFile();
    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);

    assertNull(DartProblemsView.getInstance(getProject()).getNotification());
  }

  public void testDisplayIssuesLspEnabledWithErrorsShowsOpenProblemsNotification() {
    assertTrue(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    VirtualFile file = myFixture.addFileToProject("lib/main.dart", "void main() {}\n").getVirtualFile();
    myWolfSolver.reportProblemsFromExternalSource(file, "LSP");

    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);

    Notification notification = DartProblemsView.getInstance(getProject()).getNotification();
    assertNotNull(notification);

    String openProblemsText = DartBundle.message("notification.open.problems.action.text");
    String openDartAnalysisText = DartBundle.message("notification.open.dart.analysis.action.text");

    boolean hasOpenProblems = false;
    boolean hasOpenDartAnalysis = false;
    for (AnAction action : notification.getActions()) {
      if (openProblemsText.equals(action.getTemplateText())) {
        hasOpenProblems = true;
      }
      if (openDartAnalysisText.equals(action.getTemplateText())) {
        hasOpenDartAnalysis = true;
      }
    }

    assertTrue("Notification should contain 'Open Problems' action under LSP", hasOpenProblems);
    assertFalse("Notification should not contain 'Open Dart Analysis' action under LSP", hasOpenDartAnalysis);
  }

  public void testDisplayIssuesLspDisabledShowsOpenDartAnalysisNotification() {
    PropertiesComponent.getInstance(getProject()).setValue("dart.lsp.experimental.enabled", false, true);
    assertFalse(DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));

    DartProblemsView.getInstance(getProject()).showErrorNotification("Error Title", "Error Content", null, true);

    Notification notification = DartProblemsView.getInstance(getProject()).getNotification();
    assertNotNull(notification);

    String openProblemsText = DartBundle.message("notification.open.problems.action.text");
    String openDartAnalysisText = DartBundle.message("notification.open.dart.analysis.action.text");

    boolean hasOpenProblems = false;
    boolean hasOpenDartAnalysis = false;
    for (AnAction action : notification.getActions()) {
      if (openProblemsText.equals(action.getTemplateText())) {
        hasOpenProblems = true;
      }
      if (openDartAnalysisText.equals(action.getTemplateText())) {
        hasOpenDartAnalysis = true;
      }
    }

    assertFalse("Notification should not contain 'Open Problems' action when LSP is disabled", hasOpenProblems);
    assertTrue("Notification should contain 'Open Dart Analysis' action when LSP is disabled", hasOpenDartAnalysis);
  }

  public void testClearIssueNotifications() {
    DartProblemsView.getInstance(getProject()).showErrorNotification("Error Title", "Error Content", null, true);
    assertNotNull(DartProblemsView.getInstance(getProject()).getNotification());

    DartExecutionHelper.clearIssueNotifications(getProject());
    assertNull(DartProblemsView.getInstance(getProject()).getNotification());
  }

  public void testDisplayIssuesFlutterHtmlLinkSupport() {
    String htmlContent = "Some errors exist: <a href='open.dart.analysis'>Open Problems</a>";
    DartProblemsView.getInstance(getProject()).showErrorNotification("Error Title", htmlContent, null, false);

    Notification notification = DartProblemsView.getInstance(getProject()).getNotification();
    assertNotNull(notification);
    // Notification listener is attached to handle open.dart.analysis hyperlink
    assertNotNull(notification.getListener());
  }
}
