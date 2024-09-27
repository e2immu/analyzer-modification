package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.e2immu.language.inspection.impl.parser.GenericsHelperImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class Analyzer {
    private final Runtime runtime;
    private final GenericsHelper genericsHelper;
    private final AnalysisHelper analysisHelper;
    private final ComputeLinkCompletion computeLinkCompletion;

    public Analyzer(Runtime runtime) {
        this.runtime = runtime;
        this.genericsHelper = new GenericsHelperImpl(runtime);
        this.analysisHelper = new AnalysisHelper(); // in shallow-analyzer
        computeLinkCompletion = new ComputeLinkCompletion(); // has a cache, we want this to be stable
    }

    public void doMethod(MethodInfo methodInfo) {
        VariableData variableData = doBlock(methodInfo, methodInfo.methodBody(), null);
        copyFromVariablesIntoMethod(methodInfo, variableData);
    }

    private void copyFromVariablesIntoMethod(MethodInfo methodInfo, VariableData variableData) {
        for (VariableInfo vi : variableData.variableInfoStream().toList()) {
            Variable v = vi.variable();
            if (v instanceof ParameterInfo pi && pi.methodInfo() == methodInfo) {
                LinkedVariables linkedVariables = vi.linkedVariables();
                if (linkedVariables != null) {
                    LinkedVariables filteredLvs = linkedVariables.remove(vv -> vv instanceof LocalVariable);
                    if (filteredLvs != null) {
                        pi.analysis().set(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER, filteredLvs);
                    }
                }
            } else if (v instanceof ReturnVariable) {
                LinkedVariables linkedVariables = vi.linkedVariables();
                if (linkedVariables != null) {
                    LinkedVariables filteredLvs = linkedVariables.remove(vv -> vv instanceof LocalVariable);
                    if (filteredLvs != null) {
                        methodInfo.analysis().set(LinkedVariablesImpl.LINKED_VARIABLES_METHOD, filteredLvs);
                    }
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
        ComputeLinkCompletion.Builder clcBuilder = computeLinkCompletion.new Builder();

        if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                if (!lv.assignmentExpression().isEmpty()) {
                    LinkEvaluation linkEvaluation = linkEvaluation(methodInfo, lv.assignmentExpression());
                    VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(lv);
                    clcBuilder.addLink(linkEvaluation.linkedVariables(), vi);
                    clcBuilder.addLinkEvaluation(linkEvaluation, vd);
                }
            });
        } else if (statement instanceof ExpressionAsStatement
                   || statement instanceof ReturnStatement
                   || statement instanceof IfElseStatement) {
            LinkEvaluation linkEvaluation = linkEvaluation(methodInfo, statement.expression());

            if (statement instanceof ReturnStatement) {
                ReturnVariable rv = new ReturnVariableImpl(methodInfo);
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(rv);
                clcBuilder.addLink(linkEvaluation.linkedVariables(), vi);
            }

            clcBuilder.addLinkEvaluation(linkEvaluation, vd);
        }
        clcBuilder.write(vd, Stage.EVALUATION, previous, stageOfPrevious);

        if (statement.hasSubBlocks()) {
            Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vd);
            vd.variableInfoContainerStream().forEach(vic -> {
                if (vic.hasMerge()) {
                    Variable variable = vic.variable();
                    List<LinkedVariables> linkedVariablesList = lastOfEachSubBlock.values().stream()
                            .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                            .filter(Objects::nonNull)
                            .map(VariableInfoContainer::best)
                            .map(VariableInfo::linkedVariables)
                            .filter(Objects::nonNull)
                            .toList();
                    LinkedVariables reduced = linkedVariablesList.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge);
                    VariableInfoImpl merge = (VariableInfoImpl) vic.best();
                    merge.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                    merge.setLinkedVariables(reduced);
                }
            });
        }
        return vd;
    }

    private LinkEvaluation linkEvaluation(MethodInfo currentMethod, Expression expression) {
        if (expression instanceof VariableExpression ve) {
            // TODO add scope fields, arrays
            LinkedVariables lvs = LinkedVariablesImpl.of(ve.variable(), LVImpl.LINK_ASSIGNED);
            return new LinkEvaluation.Builder().setLinkedVariables(lvs).build();
        }
        if (expression instanceof Assignment assignment) {
            LinkEvaluation evalValue = linkEvaluation(currentMethod, assignment.value());
            return new LinkEvaluation.Builder()
                    .merge(assignment.variableTarget(), evalValue.linkedVariables())
                    .setLinkedVariables(evalValue.linkedVariables())
                    .build();
        }
        if (expression instanceof MethodCall mc) {
            return linkEvaluationOfMethodCall(currentMethod, mc);
        }
        if (expression instanceof ConstructorCall cc && cc.constructor() != null) {
            return linkEvaluationOfConstructorCall(currentMethod, cc);
        }
        if (expression instanceof InstanceOf io && io.patternVariable() != null) {
            LinkEvaluation evalValue = linkEvaluation(currentMethod, io.expression());
            return new LinkEvaluation.Builder()
                    .merge(io.patternVariable(), evalValue.linkedVariables())
                    .setLinkedVariables(evalValue.linkedVariables())
                    .build();
        }
        return LinkEvaluation.EMPTY;
    }

    private LinkEvaluation linkEvaluationOfConstructorCall(MethodInfo currentMethod, ConstructorCall cc) {
        LinkEvaluation.Builder builder = new LinkEvaluation.Builder();
        List<LinkEvaluation> linkEvaluations = cc.parameterExpressions().stream()
                .map(e -> linkEvaluation(currentMethod, e)).toList();
        LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                cc.constructor());
        LinkHelper.FromParameters from = linkHelper.linksInvolvingParameters(cc.parameterizedType(),
                null, cc.parameterExpressions(), linkEvaluations);
        return builder.setLinkedVariables(from.intoObject().linkedVariablesOfExpression()).build();
    }

    private LinkEvaluation linkEvaluationOfMethodCall(MethodInfo currentMethod, MethodCall mc) {
        LinkEvaluation.Builder builder = new LinkEvaluation.Builder();

        LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                mc.methodInfo());
        ParameterizedType objectType = mc.methodInfo().isStatic() ? null : mc.object().parameterizedType();
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
        builder.merge(fp.intoObject());

        // in between parameters (A)
        linkHelper.crossLink(objectResult.linkedVariables(), linkedVariablesOfObjectFromParams, builder);

        // from object to return value
        LinkedVariables lvsResult1 = objectType == null ? LinkedVariablesImpl.EMPTY
                : linkHelper.linkedVariablesMethodCallObjectToReturnType(objectType, objectResult.linkedVariables(),
                linkedVariablesOfParameters, concreteReturnType, Map.of());

        // merge from param to object and from object to return value
        LinkedVariables lvsResult2 = fp.intoResult() == null ? lvsResult1
                : lvsResult1.merge(fp.intoResult().linkedVariablesOfExpression());

        // copy in the results of in between parameters
        Map<Variable, LV> map = new HashMap<>();
        lvsResult2.stream().forEach(e -> {
            LinkedVariables lvs = builder.getLinkedVariablesOf(e.getKey());
            map.put(e.getKey(), e.getValue());
            if (lvs != null) {
                lvs.stream().forEach(e2 -> {
                    boolean skipAllToAll = e2.getValue().isCommonHC() && e.getValue().isCommonHC()
                                           && !e2.getKey().equals(e.getKey())
                                           && e.getValue().mineIsAll() && e2.getValue().theirsIsAll();
                    if (!skipAllToAll) {
                        LV follow = LinkHelper.follow(e2.getValue(), e.getValue());
                        if (follow != null) {
                            map.put(e2.getKey(), follow);
                        }
                    }
                });
            }
        });
        LinkedVariables lvsResult = LinkedVariablesImpl.of(map);
        return builder.setLinkedVariables(lvsResult).build();
    }
}
