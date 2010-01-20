/*
 *  Copyright 2005 Pythonid Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS"; BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.jetbrains.python.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.ResolveState;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.tree.TokenSet;
import com.jetbrains.python.PyElementTypes;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.ResolveImportUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 02.06.2005
 * Time: 22:31:35
 * To change this template use File | Settings | File Templates.
 */
public class PyFromImportStatementImpl extends PyElementImpl implements PyFromImportStatement {
  public PyFromImportStatementImpl(ASTNode astNode) {
    super(astNode);
  }

  @Override
  protected void acceptPyVisitor(PyElementVisitor pyVisitor) {
    pyVisitor.visitPyFromImportStatement(this);
  }

  public boolean isStarImport() {
    return getStarImportElement() != null;
  }

  @Nullable
  public PyReferenceExpression getImportSource() {
    return childToPsi(TokenSet.create(PyElementTypes.REFERENCE_EXPRESSION), 0);
  }

  public PyImportElement[] getImportElements() {
    List<PyImportElement> result = new ArrayList<PyImportElement>();
    final ASTNode importKeyword = getNode().findChildByType(PyTokenTypes.IMPORT_KEYWORD);
    if (importKeyword != null) {
      for(ASTNode node = importKeyword.getTreeNext(); node != null; node = node.getTreeNext()) {
        if (node.getElementType() == PyElementTypes.IMPORT_ELEMENT) {
          result.add((PyImportElement) node.getPsi());
        }
      }
    }
    return result.toArray(new PyImportElement[result.size()]);
  }

  public PyStarImportElement getStarImportElement() {
    return findChildByClass(PyStarImportElement.class);
  }

  public int getRelativeLevel() {
    // crude but OK for reasonable cases, and in unreasonably complex error cases this number is moot anyway. 
    int result = 0;
    PsiElement import_kwd = findChildByType(PyTokenTypes.IMPORT_KEYWORD);
    if (import_kwd != null) {
      ASTNode seeker = import_kwd.getNode();
      while (seeker != null) {
        if (seeker.getElementType() == PyTokenTypes.DOT) result += 1;
        seeker = seeker.getTreePrev();
      }
    }
    return result;
  }

  public boolean isFromFuture() {
    PyReferenceExpression source = getImportSource();
    return (source != null && PyNames.FUTURE_MODULE.equals(source.getReferencedName()));
  }

  public boolean processDeclarations(@NotNull final PsiScopeProcessor processor, @NotNull final ResolveState state, final PsiElement lastParent,
                                     @NotNull final PsiElement place) {
    // import is per-file
    if (place.getContainingFile() != getContainingFile()) {
      return true;
    }
    if (isStarImport()) {
      PyReferenceExpression expr = getImportSource();
      if (expr != null) {
        final PsiElement importedFile = ResolveImportUtil.resolveImportReference(expr);
        if (importedFile != null) {
          return importedFile.processDeclarations(processor, state, null, place);
        }
      }
    }
    else {
      PyImportElement[] importElements = getImportElements();
      for(PyImportElement element: importElements) {
        if (!element.processDeclarations(processor, state, lastParent, place)) {
          return false;
        }
      }
    }
    return true;
  }
}
