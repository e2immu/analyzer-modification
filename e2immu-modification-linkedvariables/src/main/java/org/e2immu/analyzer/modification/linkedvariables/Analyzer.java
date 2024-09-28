package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_PARAMETER;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;

public class Analyzer {
    private final Runtime runtime;
    private final ComputeLinkCompletion computeLinkCompletion;
    private final ExpressionAnalyzer expressionAnalyzer;
    
    public Analyzer(Runtime runtime) {
        this.runtime = runtime;
        expressionAnalyzer = new ExpressionAnalyzer(runtime);
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
                        pi.analysis().set(LINKED_VARIABLES_PARAMETER, filteredLvs);
                    }
                }
                if (!pi.analysis().haveAnalyzedValueFor(MODIFIED_PARAMETER)) {
                    boolean modified = vi.isModified();
                    pi.analysis().set(MODIFIED_PARAMETER, ValueImpl.BoolImpl.from(modified));
                }
            } else if (v instanceof ReturnVariable) {
                LinkedVariables linkedVariables = vi.linkedVariables();
                if (linkedVariables != null) {
                    LinkedVariables filteredLvs = linkedVariables.remove(vv -> vv instanceof LocalVariable);
                    if (filteredLvs != null) {
                        methodInfo.analysis().set(LINKED_VARIABLES_METHOD, filteredLvs);
                    }
                }
            }
        }
    }

    // NOTE: the doBlocks, doBlock methods, and parts of doStatement, have been copied from prepwork.MethodAnalyzer

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
        VariableData vd = statement.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;
        ComputeLinkCompletion.Builder clcBuilder = computeLinkCompletion.new Builder();

        if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                if (!lv.assignmentExpression().isEmpty()) {
                    LinkEvaluation linkEvaluation = expressionAnalyzer.linkEvaluation(methodInfo, lv.assignmentExpression());
                    VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(lv);
                    clcBuilder.addLink(linkEvaluation.linkedVariables(), vi);
                    clcBuilder.addLinkEvaluation(linkEvaluation, vd);
                }
            });
        } else if (statement instanceof ExpressionAsStatement
                   || statement instanceof ReturnStatement
                   || statement instanceof IfElseStatement) {
            LinkEvaluation linkEvaluation = expressionAnalyzer.linkEvaluation(methodInfo, statement.expression());

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
                    LinkedVariables reduced = linkedVariablesList.stream().reduce(EMPTY, LinkedVariables::merge);
                    VariableInfoImpl merge = (VariableInfoImpl) vic.best();
                    merge.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                    merge.setLinkedVariables(reduced);

                    if (!merge.analysis().haveAnalyzedValueFor(MODIFIED_VARIABLE)) {
                        boolean modified = lastOfEachSubBlock.values().stream()
                                .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                                .filter(Objects::nonNull)
                                .map(VariableInfoContainer::best)
                                .anyMatch(vi -> vi.analysis().getOrDefault(MODIFIED_VARIABLE, FALSE).isTrue());
                        merge.analysis().set(MODIFIED_VARIABLE, ValueImpl.BoolImpl.from(modified));
                    }
                }
            });
        }
        return vd;
    }

    public void doType(TypeInfo x) {
    }
}
