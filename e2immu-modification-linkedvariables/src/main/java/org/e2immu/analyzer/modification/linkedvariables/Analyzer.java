package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.*;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
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
        if(variableData != null) {
            copyFromVariablesIntoMethod(methodInfo, variableData);
        } // else: can be null for empty synthetic constructors, for example
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
                StaticValues staticValues = vi.staticValues();
                if (staticValues != null) {
                    StaticValues filtered = staticValues.remove(vv -> vv instanceof LocalVariable);
                    methodInfo.analysis().set(STATIC_VALUES_METHOD, filtered);
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
        assert vd != null : "No variable data in " + statement + " source " + statement.source();
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
                clcBuilder.addAssignment(rv, linkEvaluation.staticValues());
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

    /*
    the information flow is fixed, because it is a single pass system:

    - analyze the method's statements, and copy what is possible to the parameters and the method
    - analyze the fields and their initializers
    - copy from fields to parameters where relevant

     */
    public void doType(TypeInfo typeInfo) {
        typeInfo.subTypes().forEach(this::doType);
        typeInfo.constructorAndMethodStream().forEach(this::doMethod);
        Map<FieldInfo, List<StaticValues>> svMap = collectStaticValuesOfFieldsAcrossConstructorsAndMethods(typeInfo);
        typeInfo.fields().forEach(f -> doField(f, svMap.get(f)));
        Map<ParameterInfo, StaticValues> svMapParameters = collectReverseFromFieldsToParameters(typeInfo);
        svMapParameters.forEach((pi, sv) -> {
            if (!pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                pi.analysis().set(STATIC_VALUES_PARAMETER, sv);
            }
        });
    }

    private Map<ParameterInfo, StaticValues> collectReverseFromFieldsToParameters(TypeInfo typeInfo) {
        Map<ParameterInfo, StaticValues> svMapParameters = new HashMap<>();
        typeInfo.fields().forEach(fieldInfo -> {
            StaticValues sv = fieldInfo.analysis().getOrNull(STATIC_VALUES_FIELD, StaticValuesImpl.class);
            if (sv != null
                && sv.expression() instanceof VariableExpression ve
                && ve.variable() instanceof ParameterInfo pi) {
                VariableExpression reverseVe = runtime.newVariableExpression(runtime.newFieldReference(fieldInfo));
                StaticValues reverse = StaticValuesImpl.of(reverseVe);
                StaticValues prev = svMapParameters.put(pi, reverse);
                if (prev != null && !prev.equals(sv)) throw new UnsupportedOperationException("TODO");
            }
        });
        return svMapParameters;
    }

    private Map<FieldInfo, List<StaticValues>> collectStaticValuesOfFieldsAcrossConstructorsAndMethods(TypeInfo typeInfo) {
        Map<FieldInfo, List<StaticValues>> map = new HashMap<>();
        typeInfo.constructorAndMethodStream().filter(mi -> !mi.methodBody().isEmpty()).forEach(mi -> {
            Statement lastStatement = mi.methodBody().lastStatement();
            VariableData vd = lastStatement.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            vd.variableInfoStream()
                    .filter(vi -> vi.variable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                    .filter(vi -> vi.staticValues() != null)
                    .forEach(vi -> {
                        FieldInfo fieldInfo = ((FieldReference) vi.variable()).fieldInfo();
                        map.computeIfAbsent(fieldInfo, l -> new ArrayList<>()).add(vi.staticValues());
                    });
        });
        return map;
    }

    private void doField(FieldInfo fieldInfo, List<StaticValues> staticValuesList) {
        // initial field assignments
        StaticValues reduced = staticValuesList.stream().reduce(StaticValuesImpl.NONE, StaticValues::merge);
        if (!fieldInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_FIELD)) {
            fieldInfo.analysis().set(STATIC_VALUES_FIELD, reduced);
        }
    }
}
