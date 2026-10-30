// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.lang.dart.ide.runner;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase;
import com.jetbrains.lang.dart.DartBundle;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import com.jetbrains.lang.dart.ide.errorTreeView.DartProblemsView;
import com.jetbrains.lang.dart.lsp.DartBridgeLspServerManager;
import com.jetbrains.lang.dart.sdk.DartConfigurable;
import org.dartlang.analysis.server.protocol.AnalysisError;
import org.dartlang.analysis.server.protocol.AnalysisErrorSeverity;
import org.dartlang.analysis.server.protocol.AnalysisErrorType;
import org.dartlang.analysis.server.protocol.Location;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Uses {@link CodeInsightFixtureTestCase} (real local file system) because {@code DartServerData.getErrors(scope)} only
 * considers {@code DartLocalFileInfo} entries, which are resolved through {@code LocalFileSystem}.
 */
public class DartExecutionHelperTest extends CodeInsightFixtureTestCase {

  private static final String LSP_EXPERIMENTAL_KEY = "dart.lsp.experimental.enabled";

  @Override
  public void tearDown() throws Exception {
    try {
      PropertiesComponent.getInstance(getProject()).unsetValue(LSP_EXPERIMENTAL_KEY);
      DartProblemsView.getInstance(getProject()).clearNotifications();
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  private void setLspEnabled(boolean enabled) {
    PropertiesComponent.getInstance(getProject()).setValue(LSP_EXPERIMENTAL_KEY, enabled, true);
    assertEquals(enabled, DartConfigurable.isExperimentalLspFeaturesEnabled(getProject()));
  }

  private @NotNull VirtualFile addMainFile() {
    return myFixture.addFileToProject("lib/main.dart", "void main() { brokenSyntax }\n").getVirtualFile();
  }

  /**
   * Simulates the analysis server reporting an error for {@code file}. In LSP mode this is exactly what
   * {@code DartBridgeLspServer.handlePublishDiagnostics()} does with every {@code textDocument/publishDiagnostics} notification.
   */
  private void reportError(@NotNull VirtualFile file) {
    Location location = new Location(file.getPath(), 14, 12, 1, 15, 1, 27);
    AnalysisError error = new AnalysisError(AnalysisErrorSeverity.ERROR, AnalysisErrorType.SYNTACTIC_ERROR, location,
                                            "Expected ';'", null, "expected_token", null, null, false);
    DartAnalysisServerService.getInstance(getProject()).onLspDiagnosticsUpdated(file.getPath(), List.of(error));
  }

  private void clearErrors(@NotNull VirtualFile file) {
    DartAnalysisServerService.getInstance(getProject()).onLspDiagnosticsUpdated(file.getPath(), Collections.emptyList());
  }

  private void assertNotificationShownWithAction(@NotNull String expectedActionText, @NotNull String unexpectedActionText) {
    Notification notification = DartProblemsView.getInstance(getProject()).getNotification();
    assertNotNull("Pre-run error notification should be shown", notification);

    boolean hasExpected = false;
    boolean hasUnexpected = false;
    for (AnAction action : notification.getActions()) {
      if (expectedActionText.equals(action.getTemplateText())) hasExpected = true;
      if (unexpectedActionText.equals(action.getTemplateText())) hasUnexpected = true;
    }

    assertTrue("Notification should contain '" + expectedActionText + "' action", hasExpected);
    assertFalse("Notification should not contain '" + unexpectedActionText + "' action", hasUnexpected);
  }

  private void assertOpenProblemsNotification() {
    assertNotificationShownWithAction(DartBundle.message("notification.open.problems.action.text"),
                                      DartBundle.message("notification.open.dart.analysis.action.text"));
  }

  private void assertOpenDartAnalysisNotification() {
    assertNotificationShownWithAction(DartBundle.message("notification.open.dart.analysis.action.text"),
                                      DartBundle.message("notification.open.problems.action.text"));
  }

  public void testHasErrorsWithoutErrors() {
    VirtualFile file = addMainFile();
    GlobalSearchScope scope = DartExecutionHelper.getScopeOfFilesThatMayAffectExecution(getProject(), file);
    assertNotNull(scope);

    setLspEnabled(true);
    assertFalse(DartExecutionHelper.hasErrors(getProject(), scope));

    setLspEnabled(false);
    assertFalse(DartExecutionHelper.hasErrors(getProject(), scope));
  }

  public void testHasErrorsWithReportedErrorInBothModes() {
    VirtualFile file = addMainFile();
    GlobalSearchScope scope = DartExecutionHelper.getScopeOfFilesThatMayAffectExecution(getProject(), file);
    assertNotNull(scope);
    reportError(file);

    setLspEnabled(true);
    assertTrue(DartExecutionHelper.hasErrors(getProject(), scope));

    setLspEnabled(false);
    assertTrue(DartExecutionHelper.hasErrors(getProject(), scope));

    clearErrors(file);
    assertFalse(DartExecutionHelper.hasErrors(getProject(), scope));
  }

  public void testDisplayIssuesNoErrorsDoesNotNotify() {
    VirtualFile file = addMainFile();
    setLspEnabled(true);

    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);

    assertNull(DartProblemsView.getInstance(getProject()).getNotification());
  }

  public void testDisplayIssuesLspEnabledShowsOpenProblemsNotification() {
    VirtualFile file = addMainFile();
    reportError(file);
    setLspEnabled(true);

    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);

    assertOpenProblemsNotification();
  }

  public void testDisplayIssuesLspDisabledShowsOpenDartAnalysisNotification() {
    VirtualFile file = addMainFile();
    reportError(file);
    setLspEnabled(false);

    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);

    assertOpenDartAnalysisNotification();
  }

  /**
   * Regression test for the scenario: enable LSP and run, disable LSP and run, re-enable LSP and run.
   * The notification must be shown every time, with the action matching the current setting.
   */
  public void testDisplayIssuesAfterTogglingLspSetting() {
    VirtualFile file = addMainFile();
    reportError(file);

    setLspEnabled(true);
    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);
    assertOpenProblemsNotification();

    setLspEnabled(false);
    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);
    assertOpenDartAnalysisNotification();

    setLspEnabled(true);
    DartExecutionHelper.displayIssues(getProject(), file, "Execution Message", null);
    assertOpenProblemsNotification();
  }

  /**
   * After the experimental LSP setting is toggled, the server re-sends identical errors through the newly active path.
   * They must not be dropped as duplicates, otherwise editor highlighting and the Problems tool window go stale.
   */
  public void testIdenticalErrorsAreReprocessedAfterLspSettingChanged() {
    VirtualFile file = addMainFile();
    DartAnalysisServerService das = DartAnalysisServerService.getInstance(getProject());

    reportError(file);
    assertTrue(das.hasErrorsHashForFile(file.getPath()));

    das.onExperimentalLspSettingChanged();
    assertFalse("Error hashes must be forgotten so identical errors are re-processed", das.hasErrorsHashForFile(file.getPath()));

    reportError(file);
    assertTrue(das.hasErrorsHashForFile(file.getPath()));
  }

  /**
   * When LSP is re-enabled the bridge is restarted and diagnostics published while it was down are lost, so the next LSP
   * client `initialize` must trigger a DAS re-analysis exactly once.
   */
  public void testReanalyzeRequestIsConsumedOnce() {
    DartBridgeLspServerManager manager = getProject().getService(DartBridgeLspServerManager.class);
    assertFalse(manager.consumeReanalyzeOnNextClientInitialize());

    manager.requestReanalyzeOnNextClientInitialize();
    assertTrue(manager.consumeReanalyzeOnNextClientInitialize());
    assertFalse("Re-analysis must only be triggered once per request", manager.consumeReanalyzeOnNextClientInitialize());
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
