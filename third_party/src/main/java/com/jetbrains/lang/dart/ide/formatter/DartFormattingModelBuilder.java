// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.lang.dart.ide.formatter;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.FormattingModel;
import com.intellij.formatting.FormattingModelBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.DocumentBasedFormattingModel;
import com.jetbrains.lang.dart.psi.DartFile;
import com.jetbrains.lang.dart.sdk.DartConfigurable;
import org.jetbrains.annotations.NotNull;

public final class DartFormattingModelBuilder implements FormattingModelBuilder {

  @Override
  public @NotNull FormattingModel createModel(@NotNull FormattingContext formattingContext) {
    // element can be DartFile, DartEmbeddedContent, DartExpressionCodeFragment
    final PsiFile psiFile = formattingContext.getContainingFile();
    CodeStyleSettings settings = formattingContext.getCodeStyleSettings();
    final ASTNode rootNode = psiFile instanceof DartFile ? psiFile.getNode() : formattingContext.getNode();

    if (DartConfigurable.isExperimentalLspFeaturesEnabled(formattingContext.getProject())) {
      return new DocumentBasedFormattingModel(new DummyBlock(rootNode), formattingContext.getProject(), settings, psiFile.getFileType(), psiFile);
    }

    final DartBlockContext context = new DartBlockContext(settings, formattingContext.getFormattingMode());
    final DartBlock rootBlock = new DartBlock(rootNode, null, null, settings, context);
    return new DocumentBasedFormattingModel(rootBlock, formattingContext.getProject(), settings, psiFile.getFileType(), psiFile);
  }

  /**
   * Lightweight placeholder block used to satisfy the non-null {@link FormattingModel} contract
   * when LSP formatting is enabled, avoiding the construction of the heavy AST {@link DartBlock} tree.
   */
  private static class DummyBlock extends com.intellij.psi.formatter.common.AbstractBlock {
    DummyBlock(@NotNull ASTNode node) {
      super(node, null, null);
    }
    @Override
    protected java.util.List<com.intellij.formatting.Block> buildChildren() {
      return java.util.Collections.emptyList();
    }
    @Override
    public com.intellij.formatting.Spacing getSpacing(com.intellij.formatting.Block child1, @NotNull com.intellij.formatting.Block child2) {
      return null;
    }
    @Override
    public boolean isLeaf() {
      return true;
    }
    @Override
    public @NotNull com.intellij.openapi.util.TextRange getTextRange() {
      return new com.intellij.openapi.util.TextRange(0, 0);
    }
  }
}
