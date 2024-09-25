package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.language.cst.api.expression.ConstructorCall;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.e2immu.language.inspection.impl.parser.GenericsHelperImpl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_METHOD;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_PARAMETER;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;

public class Analyzer {
    private final Runtime runtime;
    private final GenericsHelper genericsHelper;
    private final AnalysisHelper analysisHelper;

    public Analyzer(Runtime runtime) {
        this.runtime = runtime;
        this.genericsHelper = new GenericsHelperImpl(runtime);
        this.analysisHelper = new AnalysisHelper(); // in shallow-analyzer
    }

    public void doMethod(MethodInfo methodInfo) {
        VariableData variableData = doBlock(methodInfo, methodInfo.methodBody(), null);
        copyFromVariablesIntoMethod(methodInfo, variableData);
    }

    private void copyFromVariablesIntoMethod(MethodInfo methodInfo, VariableData variableData) {
        for (VariableInfo vi : variableData.variableInfoStream().toList()) {
            Variable v = vi.variable();
            if (v instanceof ParameterInfo pi && pi.methodInfo() == methodInfo) {
                LinkedVariables lv = vi.linkedVariables();
                if (lv != null) {
                    pi.analysis().set(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER, lv);
                }
            } else if (v instanceof ReturnVariable) {
                LinkedVariables lv = vi.linkedVariables();
                if (lv != null) {
                    methodInfo.analysis().set(LinkedVariablesImpl.LINKED_VARIABLES_METHOD, lv);
                }
            }
        }
    }

    // NOTE: the doBlocks, doBlock methods, and parts of doStatement, have been copied from prepwork.AnalyzeMethod

    private Map<String, VariableData> doBlocks(MethodInfo methodInfo,
                                               Statement parentStatement,
                                               VariableData vdOfParent) {
        Stream<Block> blockStream;
        if (parentStatement instanceof TryStatement ts && !(ts.resources().isEmpty())) {
            Block.Builder bb = runtime.newBlockBuilder();
            bb.addStatements(ts.resources());
            bb.addStatements(ts.block().statements());
            blockStream = Stream.concat(Stream.of(bb.build()), ts.otherBlocksStream());
        } else {
            blockStream = parentStatement.subBlockStream();
        }
        return blockStream
                .filter(b -> !b.isEmpty())
                .collect(Collectors.toUnmodifiableMap(
                        block -> block.statements().get(0).source().index(),
                        block -> doBlock(methodInfo, block, vdOfParent)));
    }

    private VariableData doBlock(MethodInfo methodInfo,
                                 Block block,
                                 VariableData vdOfParent) {
        VariableData previous = vdOfParent;
        boolean first = true;
        for (Statement statement : block.statements()) {
            previous = doStatement(methodInfo, statement, previous, first);
            if (first) first = false;
        }
        return previous;
    }

    private VariableData doStatement(MethodInfo methodInfo,
                                     Statement statement,
                                     VariableData previous,
                                     boolean first) {
        Stage stageOfPrevious = first ? Stage.EVALUATION : Stage.MERGE;
        VariableData vd = statement.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;
        if (statement instanceof ExpressionAsStatement || statement instanceof ReturnStatement) {
            LinkEvaluation linkEvaluation = linkEvaluation(methodInfo, statement.expression());

            if (statement instanceof ReturnStatement) {
                ReturnVariable rv = new ReturnVariableImpl(methodInfo);
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(rv);
                LinkedVariables mergedLvs;
                if (previous != null) {
                    throw new UnsupportedOperationException();
                } else {
                    mergedLvs = linkEvaluation.linkedVariables();
                }
                vi.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                vi.setLinkedVariables(mergedLvs);
            }

            for (Map.Entry<Variable, LinkedVariables> entry : linkEvaluation.links().entrySet()) {
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(entry.getKey());
                vi.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                vi.setLinkedVariables(entry.getValue());
            }
        }
        if (statement.hasSubBlocks()) {
            Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vd);

        }
        return vd;
    }


    private LinkEvaluation linkEvaluation(MethodInfo currentMethod, Expression expression) {
        if (expression instanceof VariableExpression ve) {
            LinkedVariables lvs = LinkedVariablesImpl.of(ve.variable(), LVImpl.LINK_ASSIGNED);
            return new LinkEvaluation.Builder().setLinkedVariables(lvs).build();
        }
        if (expression instanceof MethodCall mc) {
            LinkEvaluation.Builder builder = new LinkEvaluation.Builder();

            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    mc.methodInfo());
            ParameterizedType objectType = mc.object().parameterizedType();
            ParameterizedType concreteReturnType = mc.concreteReturnType();

            List<LinkEvaluation> linkEvaluations = mc.parameterExpressions().stream()
                    .map(e -> linkEvaluation(currentMethod, e)).toList();
            List<LinkedVariables> linkedVariablesOfParameters = linkEvaluations.stream()
                    .map(LinkEvaluation::linkedVariables).toList();
            LinkEvaluation objectResult = mc.methodInfo().isStatic()
                    ? LinkEvaluation.EMPTY : linkEvaluation(currentMethod, mc.object());

            // from parameters to object
            LinkHelper.FromParameters fp = linkHelper.linksInvolvingParameters(objectType, concreteReturnType,
                    mc.parameterExpressions(), linkEvaluations);
            LinkedVariables linkedVariablesOfObjectFromParams = fp.intoObject().linkedVariablesOfExpression();
            if (mc.object() instanceof VariableExpression ve) {
                builder.merge(ve.variable(), linkedVariablesOfObjectFromParams);
            }

            // from object to return value
            LinkedVariables lvsResult1 = objectType == null ? LinkedVariablesImpl.EMPTY
                    : linkHelper.linkedVariablesMethodCallObjectToReturnType(objectType, objectResult.linkedVariables(),
                    linkedVariablesOfParameters, concreteReturnType, Map.of());

            return builder.setLinkedVariables(lvsResult1).build();
        }
        if (expression instanceof ConstructorCall cc && cc.constructor() != null) {
            LinkEvaluation.Builder builder = new LinkEvaluation.Builder();
            List<LinkEvaluation> linkEvaluations = cc.parameterExpressions().stream()
                    .map(e -> linkEvaluation(currentMethod, e)).toList();
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    cc.constructor());
            LinkHelper.FromParameters from = linkHelper.linksInvolvingParameters(cc.parameterizedType(),
                    null, cc.parameterExpressions(), linkEvaluations);
            return builder.setLinkedVariables(from.intoObject().linkedVariablesOfExpression()).build();
        }
        return LinkEvaluation.EMPTY;
    }
}
