/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightControlFlowUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.controlFlow.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.IntArrayList;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * User: anna
 */
public class StreamApiMigrationInspection extends BaseJavaBatchLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance("#" + StreamApiMigrationInspection.class.getName());

  @Nls
  @NotNull
  @Override
  public String getGroupDisplayName() {
    return GroupNames.LANGUAGE_LEVEL_SPECIFIC_GROUP_NAME;
  }

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return "foreach loop can be collapsed with stream api";
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @NotNull
  @Override
  public String getShortName() {
    return "Convert2streamapi";
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitForeachStatement(PsiForeachStatement statement) {
        super.visitForeachStatement(statement);
        if (PsiUtil.getLanguageLevel(statement).isAtLeast(LanguageLevel.JDK_1_8)) {
          final PsiExpression iteratedValue = statement.getIteratedValue();
          final PsiStatement body = statement.getBody();
          if (iteratedValue != null && body != null) {
            final PsiType iteratedValueType = iteratedValue.getType();
            if (InheritanceUtil.isInheritor(iteratedValueType, CommonClassNames.JAVA_UTIL_COLLECTION)) {
              final PsiClass iteratorClass = PsiUtil.resolveClassInType(iteratedValueType);
              LOG.assertTrue(iteratorClass != null);
              try {
                final ControlFlow controlFlow = ControlFlowFactory.getInstance(holder.getProject())
                  .getControlFlow(body, LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance());
                int startOffset = controlFlow.getStartOffset(body);
                int endOffset = controlFlow.getEndOffset(body);
                final Collection<PsiStatement> exitPoints = ControlFlowUtil
                  .findExitPointsAndStatements(controlFlow, startOffset, endOffset, new IntArrayList(), PsiContinueStatement.class,
                                               PsiBreakStatement.class, PsiReturnStatement.class, PsiThrowStatement.class);
                if (exitPoints.isEmpty()) {
  
                  final List<PsiVariable> usedVariables = ControlFlowUtil.getUsedVariables(controlFlow, startOffset, endOffset);
                  for (PsiVariable variable : usedVariables) {
                    if (!HighlightControlFlowUtil.isEffectivelyFinal(variable, body, null)) {
                      return;
                    }
                  }

                  if (ExceptionUtil.getThrownCheckedExceptions(new PsiElement[] {body}).isEmpty()) {
                    if (isCollectCall(body)) {
                      holder.registerProblem(iteratedValue, "Can be replaced with collect call",
                                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ReplaceWithCollectCallFix());
                    } else if (!isTrivial(body, statement.getIterationParameter())) {
                      holder.registerProblem(iteratedValue, "Can be replaced with foreach call",
                                             ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ReplaceWithForeachCallFix());
                    }
                  }
                }
              }
              catch (AnalysisCanceledException ignored) {
              }
            }
          }
        }
      }
    };
  }

  private static boolean isCollectCall(PsiStatement body) {
    PsiIfStatement ifStatement = extractIfStatement(body);
    final PsiMethodCallExpression methodCallExpression = extractAddCall(body, ifStatement);
    if (methodCallExpression != null) {
      final PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
      final PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
      PsiClass qualifierClass = null;
      if (qualifierExpression instanceof PsiReferenceExpression) {
        qualifierClass = PsiUtil.resolveClassInType(qualifierExpression.getType());
      } else if (qualifierExpression == null) {
        final PsiClass enclosingClass = PsiTreeUtil.getParentOfType(body, PsiClass.class);
        if (PsiUtil.getEnclosingStaticElement(body, enclosingClass) == null) {
          qualifierClass = enclosingClass;
        }
      }

      if (qualifierClass != null && 
          InheritanceUtil.isInheritor(qualifierClass, false, CommonClassNames.JAVA_UTIL_COLLECTION)) {

        while (ifStatement != null && PsiTreeUtil.isAncestor(body, ifStatement, false)) {
          final PsiExpression condition = ifStatement.getCondition();
          if (condition != null && isConditionDependsOnUpdatedCollections(condition, qualifierExpression)) return false;
          ifStatement = PsiTreeUtil.getParentOfType(ifStatement, PsiIfStatement.class);
        }

        final PsiElement resolve = methodExpression.resolve();
        if (resolve instanceof PsiMethod &&
            "add".equals(((PsiMethod)resolve).getName()) &&
            ((PsiMethod)resolve).getParameterList().getParametersCount() == 1) {
          final PsiExpression[] args = methodCallExpression.getArgumentList().getExpressions();
          if (args.length == 1) {
            if (args[0] instanceof PsiCallExpression) {
              final PsiMethod method = ((PsiCallExpression)args[0]).resolveMethod();
              return method != null && !method.hasTypeParameters() && !isThrowsCompatible(method);
            }
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isConditionDependsOnUpdatedCollections(PsiExpression condition,
                                                                PsiExpression qualifierExpression) {
    final PsiElement collection = qualifierExpression instanceof PsiReferenceExpression
                                  ? ((PsiReferenceExpression)qualifierExpression).resolve()
                                  : null;
    if (collection != null) {
      return ReferencesSearch.search(collection, new LocalSearchScope(condition)).findFirst() != null;
    }

    final boolean[] dependsOnCollection = {false};
    condition.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression expression) {
        super.visitMethodCallExpression(expression);
        final PsiExpression callQualifier = expression.getMethodExpression().getQualifierExpression();
        if (callQualifier == null ||
            callQualifier instanceof PsiThisExpression && ((PsiThisExpression)callQualifier).getQualifier() == null ||
            callQualifier instanceof PsiSuperExpression && ((PsiSuperExpression)callQualifier).getQualifier() == null) {
          dependsOnCollection[0] = true;
        }
      }

      @Override
      public void visitThisExpression(PsiThisExpression expression) {
        super.visitThisExpression(expression);
        if (expression.getQualifier() == null && expression.getParent() instanceof PsiExpressionList) {
          dependsOnCollection[0] = true;
        }
      }

      @Override
      public void visitClass(PsiClass aClass) {}

      @Override
      public void visitLambdaExpression(PsiLambdaExpression expression) {}
    });

    return dependsOnCollection[0];
  }

  private static boolean isTrivial(PsiStatement body, PsiParameter parameter) {
    final PsiIfStatement ifStatement = extractIfStatement(body);
    //filter
    if (ifStatement != null) {
      return false;
    }
    //method reference 
    final PsiCallExpression callExpression = LambdaCanBeMethodReferenceInspection
      .canBeMethodReferenceProblem(body instanceof PsiBlockStatement ? ((PsiBlockStatement)body).getCodeBlock() : body,
                                   new PsiParameter[]{parameter}, 
                                   createDefaultConsumerType(parameter.getProject(), parameter));
    if (callExpression == null) {
      return true;
    }
    final PsiMethod method = callExpression.resolveMethod();
    return method != null && isThrowsCompatible(method);
  }

  private static boolean isThrowsCompatible(PsiMethod method) {
    return ContainerUtil.find(method.getThrowsList().getReferencedTypes(), new Condition<PsiClassType>() {
      @Override
      public boolean value(PsiClassType type) {
        return !ExceptionUtil.isUncheckedException(type);
      }
    }) != null;
  }

  private static class ReplaceWithForeachCallFix implements LocalQuickFix {
    @NotNull
    @Override
    public String getName() {
      return getFamilyName();
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with forEach";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiForeachStatement foreachStatement = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiForeachStatement.class);
      if (foreachStatement != null) {
        if (!FileModificationService.getInstance().preparePsiElementForWrite(foreachStatement)) return;
        PsiStatement body = foreachStatement.getBody();
        final PsiExpression iteratedValue = foreachStatement.getIteratedValue();
        if (body != null && iteratedValue != null) {
          restoreComments(foreachStatement, body);

          final PsiParameter parameter = foreachStatement.getIterationParameter();
          final PsiIfStatement ifStmt = extractIfStatement(body);

          StringBuilder buffer = new StringBuilder(getIteratedValueText(iteratedValue));
          if (ifStmt != null) {
            final PsiStatement thenBranch = ifStmt.getThenBranch();
            LOG.assertTrue(thenBranch != null);
            buffer.append(".stream()");
            buffer.append(createFiltersChainText(body, parameter, ifStmt));
            body = thenBranch;
          }

          buffer.append(".forEach(");

          final String functionalExpressionText = createForEachFunctionalExpressionText(project, body, parameter);
          final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
          PsiExpressionStatement callStatement = (PsiExpressionStatement)elementFactory.createStatementFromText(buffer.toString() + functionalExpressionText + ");", foreachStatement);
          callStatement = (PsiExpressionStatement)foreachStatement.replace(callStatement);

          final PsiExpressionList argumentList = ((PsiCallExpression)callStatement.getExpression()).getArgumentList();
          LOG.assertTrue(argumentList != null, callStatement.getText());
          final PsiExpression[] expressions = argumentList.getExpressions();
          LOG.assertTrue(expressions.length == 1);

          if (expressions[0] instanceof PsiFunctionalExpression && ((PsiFunctionalExpression)expressions[0]).getFunctionalInterfaceType() == null) {
            callStatement =
              (PsiExpressionStatement)callStatement.replace(elementFactory.createStatementFromText(buffer.toString() + "(" + parameter.getText() + ") -> " + wrapInBlock(body) + ");", callStatement));
          }

          CodeStyleManager.getInstance(project).reformat(callStatement);
        }
      }
    }

    private static String createForEachFunctionalExpressionText(Project project, PsiStatement body, PsiParameter parameter) {
      final PsiCallExpression callExpression = LambdaCanBeMethodReferenceInspection.extractMethodCallFromBlock(body);
      if (callExpression != null) {
        final PsiClassType functionalType = createDefaultConsumerType(project, parameter);
        final PsiParameter[] parameters = {parameter};
        final PsiElement bodyBlock = body instanceof PsiBlockStatement ? ((PsiBlockStatement)body).getCodeBlock() : body;
        final PsiCallExpression toConvertCall = LambdaCanBeMethodReferenceInspection.canBeMethodReferenceProblem(bodyBlock, parameters, functionalType);
        final String methodReferenceText = LambdaCanBeMethodReferenceInspection.createMethodReferenceText(toConvertCall, functionalType, parameters);
        if (methodReferenceText != null) {
          return methodReferenceText;
        }
      }
      return parameter.getName() + " -> " + wrapInBlock(body);
    }

    private static String wrapInBlock(PsiStatement body) {

      if (body instanceof PsiExpressionStatement) {
        return ((PsiExpressionStatement)body).getExpression().getText();
      }

      final String bodyText = body.getText();
      if (!(body instanceof PsiBlockStatement)) {
        return "{" + bodyText + "}";
      }
      return bodyText;
    }
  }

  private static PsiClassType createDefaultConsumerType(Project project, PsiParameter parameter) {
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiClass consumerClass = psiFacade.findClass("java.util.function.Consumer", GlobalSearchScope.allScope(project));
    return consumerClass != null ? psiFacade.getElementFactory().createType(consumerClass, parameter.getType()) : null;
  }

  private static class ReplaceWithCollectCallFix implements LocalQuickFix {
    @NotNull
    @Override
    public String getName() {
      return getFamilyName();
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with collect";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiForeachStatement foreachStatement = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiForeachStatement.class);
      if (foreachStatement != null) {
        if (!FileModificationService.getInstance().preparePsiElementForWrite(foreachStatement)) return;
        PsiStatement body = foreachStatement.getBody();
        final PsiExpression iteratedValue = foreachStatement.getIteratedValue();
        if (body != null && iteratedValue != null) {
          final PsiParameter parameter = foreachStatement.getIterationParameter();
          final StringBuilder builder = new StringBuilder(getIteratedValueText(iteratedValue) + ".stream()");

          final PsiIfStatement ifStatement = extractIfStatement(body);
          final PsiMethodCallExpression methodCallExpression = extractAddCall(body, ifStatement);

          builder.append(createFiltersChainText(body, parameter, ifStatement));
          builder.append(createMapperFunctionalExpressionText(project, parameter, methodCallExpression.getArgumentList().getExpressions()[0]));

          builder.append(".collect(java.util.stream.Collectors.");
          PsiElement result = null;
          try {
            final PsiExpression qualifierExpression = methodCallExpression.getMethodExpression().getQualifierExpression();
            final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
            if (qualifierExpression instanceof PsiReferenceExpression) {
              final PsiElement resolve = ((PsiReferenceExpression)qualifierExpression).resolve();
              if (resolve instanceof PsiVariable) {
                if (resolve instanceof PsiLocalVariable && foreachStatement.equals(PsiTreeUtil.skipSiblingsForward(resolve.getParent(), PsiWhiteSpace.class))) {
                  final PsiExpression initializer = ((PsiVariable)resolve).getInitializer();
                  if (initializer instanceof PsiNewExpression) {
                    final PsiExpressionList argumentList = ((PsiNewExpression)initializer).getArgumentList();
                    if (argumentList != null && argumentList.getExpressions().length == 0) {
                      restoreComments(foreachStatement, body);
                      final String callText = builder.toString() + createInitializerReplacementText(initializer) + ")";
                      result = initializer.replace(elementFactory.createExpressionFromText(callText, null));
                      foreachStatement.delete();
                      return;
                    }
                  }
                }
              }
            }
            restoreComments(foreachStatement, body);
            final String qualifierText = qualifierExpression != null ? qualifierExpression.getText() : "";
            final String callText = StringUtil.getQualifiedName(qualifierText, "addAll(" + builder.toString() + "toList()));");
            result = foreachStatement.replace(elementFactory.createStatementFromText(callText, foreachStatement));
          }
          finally {
            if (result != null) {
              CodeStyleManager.getInstance(project).reformat(JavaCodeStyleManager.getInstance(project).shortenClassReferences(result));
            }
          }
        }
      }
    }

    private static String createInitializerReplacementText(PsiExpression initializer) {
      final PsiType initializerType = initializer.getType();
      final PsiClassType rawType = initializerType instanceof PsiClassType ? ((PsiClassType)initializerType).rawType() : null;
      if (rawType != null && rawType.equalsToText(CommonClassNames.JAVA_UTIL_ARRAY_LIST)) {
        return "toList()";
      } else if (rawType != null && rawType.equalsToText(CommonClassNames.JAVA_UTIL_HASH_SET)) {
        return "toSet()";
      } else {
        return "toCollection(() -> " + initializer.getText() +")";
      }
    }

    private static String createMapperFunctionalExpressionText(Project project, PsiParameter parameter, PsiExpression expression) {
      String iteration = "";
      if (!isIdentityMapping(parameter, expression)) {
        iteration +=".map(";
        final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        final PsiClass functionClass = psiFacade.findClass("java.util.function.Function", GlobalSearchScope.allScope(project));
        final PsiClassType functionalInterfaceType = functionClass != null ? psiFacade.getElementFactory().createType(functionClass, parameter.getType(), expression.getType()) : null;
        final PsiCallExpression toConvertCall = LambdaCanBeMethodReferenceInspection
          .canBeMethodReferenceProblem(expression, new PsiParameter[]{parameter}, functionalInterfaceType);
        final String methodReferenceText = LambdaCanBeMethodReferenceInspection.createMethodReferenceText(toConvertCall, functionalInterfaceType, new PsiParameter[]{parameter});
        if (methodReferenceText != null) {
          iteration += methodReferenceText;
        } else {
          iteration += parameter.getName() + " -> " + expression.getText();
        }
        iteration += ")";
      }
      return iteration;
    }

    private static boolean isIdentityMapping(PsiParameter parameter, PsiExpression mapperCall) {
      return mapperCall instanceof PsiReferenceExpression && ((PsiReferenceExpression)mapperCall).resolve() == parameter;
    }
  }

  private static void restoreComments(PsiForeachStatement foreachStatement, PsiStatement body) {
    final PsiElement parent = foreachStatement.getParent();
    for (PsiElement comment : PsiTreeUtil.findChildrenOfType(body, PsiComment.class)) {
      parent.addBefore(comment, foreachStatement);
    }
  }

  private static String createFiltersChainText(PsiStatement body, PsiParameter parameter, PsiIfStatement ifStatement) {
    final List<String> filters = new ArrayList<String>();
    while (ifStatement != null && PsiTreeUtil.isAncestor(body, ifStatement, false)) {
      final PsiExpression condition = ifStatement.getCondition();
      if (condition != null) {
        filters.add(".filter(" + parameter.getName() + " -> " + condition.getText() +")");
      }
      ifStatement = PsiTreeUtil.getParentOfType(ifStatement, PsiIfStatement.class);
    }
    Collections.reverse(filters);
    return StringUtil.join(filters, "");
  }

  private static String getIteratedValueText(PsiExpression iteratedValue) {
    return iteratedValue instanceof PsiCallExpression ||
           iteratedValue instanceof PsiReferenceExpression ||
           iteratedValue instanceof PsiQualifiedExpression ||
           iteratedValue instanceof PsiParenthesizedExpression ? iteratedValue.getText() : "(" + iteratedValue.getText() + ")";
  }

  private static PsiIfStatement extractIfStatement(PsiStatement body) {
    PsiIfStatement ifStmt = null;
    if (body instanceof PsiIfStatement) {
      ifStmt = (PsiIfStatement)body;
    }
    else if (body instanceof PsiBlockStatement) {
      final PsiStatement[] statements = ((PsiBlockStatement)body).getCodeBlock().getStatements();
      if (statements.length == 1 && statements[0] instanceof PsiIfStatement) {
        ifStmt = (PsiIfStatement)statements[0];
      }
    }
    if (ifStmt != null && ifStmt.getElseBranch() == null && ifStmt.getCondition() != null) {
      final PsiStatement thenBranch = ifStmt.getThenBranch();
      if (thenBranch != null) {
        final PsiIfStatement deeperThen = extractIfStatement(thenBranch);
        return deeperThen != null ? deeperThen : ifStmt;
      }
    }
    return null;
  }
  
  private static PsiMethodCallExpression extractAddCall(PsiStatement body, PsiIfStatement ifStatement) {
    if (ifStatement != null) {
      final PsiStatement thenBranch = ifStatement.getThenBranch();
      return extractAddCall(thenBranch, null);
    }
    PsiExpressionStatement stmt = null;
    if (body instanceof PsiBlockStatement) {
      final PsiStatement[] statements = ((PsiBlockStatement)body).getCodeBlock().getStatements();
      if (statements.length == 1 && statements[0] instanceof PsiExpressionStatement) {
        stmt = (PsiExpressionStatement)statements[0];
      }
    }
    else if (body instanceof PsiExpressionStatement) {
      stmt = (PsiExpressionStatement)body;
    }

    if (stmt != null) {
      final PsiExpression expression = stmt.getExpression();
      if (expression instanceof PsiMethodCallExpression) {
        return (PsiMethodCallExpression)expression;
      }
    }
    return null;
  }
}
