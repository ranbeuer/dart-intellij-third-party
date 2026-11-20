/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package com.jetbrains.lang.dart.ide.findUsages;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.platform.dartlsp.impl.features.navigation.LspGotoImplementationTarget;
import com.intellij.pom.PomTargetPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.sdk.DartConfigurable;
import com.intellij.psi.PsiReference;
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

/**
 * Evaluates target elements for Dart references.
 *
 * When LSP references are enabled, this evaluator suppresses PSI target candidates
 * so that IntelliJ's find usages action does not produce duplicate/ambiguous search targets
 * (e.g. an LSP SearchTarget alongside a legacy PsiTargetVariant).
 *
 * NOTE: This is a transitional fix during the LSP migration. When Find Usages is invoked,
 * IntelliJ's SearchTargetVariantsDataRule queries both modern SEARCH_TARGETS (LSP) and
 * falls back to TargetElementUtil if USAGE_TARGETS_KEY is null. Since Dart files maintain
 * a PSI reference tree, TargetElementUtil resolves the reference through PSI, leading to
 * competing targets. This evaluator (or its legacy fallback) can be removed or simplified
 * once the plugin is fully migrated to LSP and legacy DAS / PSI reference resolution is
 * deprecated, or if the platform avoids the TargetElementUtil fallback when LSP targets exist.
 */
public class DartTargetElementEvaluator extends TargetElementEvaluatorEx2 {
  @Override
  public @Nullable PsiElement adjustReferenceOrReferencedElement(@NotNull PsiFile file,
                                                                 @NotNull Editor editor,
                                                                 int offset,
                                                                 int flags,
                                                                 @Nullable PsiElement referencedElement) {
    final var context = ActionUtil.getActionThreadContext();
    if (context != null && IdeActions.ACTION_GOTO_IMPLEMENTATION.equals(context.getActionId()) &&
        DartConfigurable.isExperimentalLspFeaturesEnabled(file.getProject())) {
      // Rich Dart references resolve before the platform's POM lookup (IJPL-255205).
      // Reuse that lookup, including its capability/customizer checks; do not manufacture a Dart target.
      final PsiElement leaf = file.findElementAt(TargetElementUtil.adjustOffset(file, editor.getDocument(), offset));
      if (leaf != null) {
        final PsiElement target = TargetElementUtil.getInstance().getNamedElement(leaf, offset - leaf.getTextRange().getStartOffset());
        if (target instanceof PomTargetPsiElement pom && pom.getTarget() instanceof LspGotoImplementationTarget) {
          return target;
        }
      }
    }
    return referencedElement;
  }

  @Override
  public @Nullable Collection<PsiElement> getTargetCandidates(@NotNull PsiReference reference) {
    final PsiElement element = reference.getElement();
    final Project project = element.getProject();
    if (DartAnalysisServerService.isLspReferencesEnabled(project)) {
      // Suppress PSI candidates when LSP handles references to prevent IntelliJ from showing
      // an ambiguous "Find Usages Of" chooser popup.
      return Collections.emptyList();
    }
    // Fall back to standard PSI / DAS reference resolution when LSP is disabled.
    return null;
  }
}
