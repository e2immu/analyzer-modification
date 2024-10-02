package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.*;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

public class Analyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Analyzer.class);

    private final Runtime runtime;
    private final ComputeLinkCompletion computeLinkCompletion;
    private final ExpressionAnalyzer expressionAnalyzer;

    public Analyzer(Runtime runtime) {
        this.runtime = runtime;
        expressionAnalyzer = new ExpressionAnalyzer(runtime);
        computeLinkCompletion = new ComputeLinkCompletion(); // has a cache, we want this to be stable
    }

    public void doMethod(MethodInfo methodInfo) {
        LOGGER.info("Do method {}", methodInfo);

        doHiddenContentSelector(methodInfo);
        VariableData variableData = doBlock(methodInfo, methodInfo.methodBody(), null);
        if (variableData != null) {
            copyFromVariablesIntoMethod(methodInfo, variableData);
        } // else: can be null for empty synthetic constructors, for example
        doFluentIdentityAnalysis(methodInfo, variableData, PropertyImpl.IDENTITY_METHOD,
                e -> e instanceof VariableExpression ve
                     && ve.variable() instanceof ParameterInfo pi
                     && pi.methodInfo() == methodInfo
                     && pi.index() == 0);
        doFluentIdentityAnalysis(methodInfo, variableData, PropertyImpl.FLUENT_METHOD,
                e -> e instanceof VariableExpression ve
                     && ve.variable() instanceof This thisVar
                     && thisVar.typeInfo() == methodInfo.typeInfo());
        doIndependent(methodInfo, variableData);
    }

    private void doHiddenContentSelector(MethodInfo methodInfo) {
        MethodInfo overrideWithMostHiddenContent = overloadWithMostHiddenContent(methodInfo);
        ParameterizedType returnType = overrideWithMostHiddenContent.returnType();
        HiddenContentTypes hctOverride = overrideWithMostHiddenContent.analysis()
                .getOrNull(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
        assert hctOverride != null;
        if (!methodInfo.analysis().haveAnalyzedValueFor(HiddenContentSelector.HCS_METHOD)) {
            HiddenContentSelector hcs = HiddenContentSelector.selectAll(hctOverride, returnType);
            methodInfo.analysis().set(HiddenContentSelector.HCS_METHOD, hcs);
        }
        methodInfo.parameters().forEach(pi -> {
            if (!pi.analysis().haveAnalyzedValueFor(HiddenContentSelector.HCS_PARAMETER)) {
                ParameterizedType pt = overrideWithMostHiddenContent.parameters().get(pi.index()).parameterizedType();
                HiddenContentSelector hcsPa = HiddenContentSelector.selectAll(hctOverride, pt);
                pi.analysis().set(HiddenContentSelector.HCS_PARAMETER, hcsPa);
            }
        });
    }


    private MethodInfo overloadWithMostHiddenContent(MethodInfo methodInfo) {
        Set<MethodInfo> overrides = methodInfo.overrides();
        if (overrides.isEmpty()) return methodInfo;
        if (overrides.size() == 1) return overrides.stream().findFirst().orElseThrow();
        Map<MethodInfo, Integer> map = overrides.stream()
                .collect(Collectors.toUnmodifiableMap(m -> m, this::countHiddenContent));
        return overrides.stream().min((o1, o2) -> map.get(o2) - map.get(o1)).orElseThrow();
    }

    private int countHiddenContent(MethodInfo methodInfo) {
        HiddenContentTypes hct = methodInfo.analysis().getOrNull(HiddenContentTypes.HIDDEN_CONTENT_TYPES,
                HiddenContentTypes.class);
        assert hct != null;
        Stream<ParameterizedType> types = Stream.concat(Stream.of(methodInfo.returnType()),
                methodInfo.parameters().stream().map(Variable::parameterizedType));
        return (int) types.filter(t -> hct.indexOfOrNull(t) != null).count();
    }

    private void doFluentIdentityAnalysis(MethodInfo methodInfo, VariableData lastOfMainBlock,
                                          Property property, Predicate<Expression> predicate) {
        if (!methodInfo.analysis().haveAnalyzedValueFor(property)) {
            boolean identityFluent;
            if (lastOfMainBlock == null) {
                identityFluent = false;
            } else {
                VariableInfoContainer vicRv = lastOfMainBlock.variableInfoContainerOrNull(methodInfo.fullyQualifiedName());
                if (vicRv != null) {
                    VariableInfo viRv = vicRv.best();
                    StaticValues svRv = viRv.staticValues();
                    identityFluent = svRv != null && predicate.test(svRv.expression());
                } else {
                    identityFluent = false;
                }
            }
            methodInfo.analysis().set(property, ValueImpl.BoolImpl.from(identityFluent));
        }
    }

    /*
    constructors: independent
    void methods: independent
    fluent methods: because we return the same object that the caller already has, no more opportunity to make
        changes is leaked than what as already there. Independent!
    accessors: independent directly related to the immutability of the field being returned
    normal methods: does a modification to the return value imply any modification in the method's object?
        independent directly related to the immutability of the fields to which the return value links.
     */
    private void doIndependent(MethodInfo methodInfo, VariableData lastOfMainBlock) {
        if (!methodInfo.analysis().haveAnalyzedValueFor(PropertyImpl.INDEPENDENT_METHOD)) {
            Independent independent = doIndependentMethod(methodInfo, lastOfMainBlock);
            methodInfo.analysis().set(PropertyImpl.INDEPENDENT_METHOD, independent);
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            if (!methodInfo.analysis().haveAnalyzedValueFor(PropertyImpl.INDEPENDENT_PARAMETER)) {
                Independent independent = doIndependentParameter(pi, lastOfMainBlock);
                methodInfo.analysis().set(PropertyImpl.INDEPENDENT_PARAMETER, independent);
            }
        }
    }

    private Independent doIndependentParameter(ParameterInfo pi, VariableData lastOfMainBlock) {
        TypeInfo piTypeInfo = pi.parameterizedType().typeInfo();
        boolean typeIsImmutable = piTypeInfo != null && piTypeInfo.analysis()
                .getOrDefault(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        if (pi.methodInfo().isAbstract()) return DEPENDENT;
        return worstLinkToFields(lastOfMainBlock, pi.fullyQualifiedName());
    }

    private Independent doIndependentMethod(MethodInfo methodInfo, VariableData lastOfMainBlock) {
        if (methodInfo.isConstructor() || methodInfo.noReturnValue()) return INDEPENDENT;
        if (methodInfo.isAbstract()) {
            return DEPENDENT; // must be annotated otherwise
        }
        boolean fluent = methodInfo.analysis().getOrDefault(FLUENT_METHOD, FALSE).isTrue();
        if (fluent) return INDEPENDENT;
        TypeInfo returnTypeInfo = methodInfo.returnType().typeInfo();
        boolean typeIsImmutable = returnTypeInfo != null && returnTypeInfo.analysis()
                .getOrDefault(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        return worstLinkToFields(lastOfMainBlock, methodInfo.fullyQualifiedName());
    }

    private Independent worstLinkToFields(VariableData lastOfMainBlock, String variableFqn) {
        assert lastOfMainBlock != null : "We have a method with return value, so we must have a statement";

        VariableInfoContainer vicRv = lastOfMainBlock.variableInfoContainerOrNull(variableFqn);
        VariableInfo viRv = vicRv.best();
        LV worstLinkToFields = viRv.linkedVariables().stream()
                .filter(e -> e.getKey() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                .map(Map.Entry::getValue)
                .min(LV::compareTo).orElse(LVImpl.LINK_DEPENDENT);
        if (worstLinkToFields.equals(LVImpl.LINK_INDEPENDENT)) return INDEPENDENT;
        if (worstLinkToFields.isCommonHC()) return INDEPENDENT_HC;
        return DEPENDENT;
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
                Value.FieldBooleanMap mfi = vi.analysis().getOrNull(VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE,
                        ValueImpl.FieldBooleanMapImpl.class);
                if (mfi != null && !mfi.map().isEmpty()
                    && !pi.analysis().haveAnalyzedValueFor(MODIFIED_FI_COMPONENTS_PARAMETER)) {
                    pi.analysis().set(MODIFIED_FI_COMPONENTS_PARAMETER, mfi);
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
            } else if (v instanceof This || v instanceof FieldReference fr && fr.scopeIsRecursivelyThis()) {
                if (vi.analysis().getOrDefault(MODIFIED_VARIABLE, FALSE).isTrue()
                    && !methodInfo.analysis().haveAnalyzedValueFor(MODIFIED_METHOD)) {
                    methodInfo.analysis().set(MODIFIED_METHOD, TRUE);
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
            try {
                previous = doStatement(methodInfo, statement, previous, first);
            } catch (RuntimeException re) {
                LOGGER.error("Have error analyzing statement {}, {}, in method {}",
                        statement, statement.source(), methodInfo);
                throw re;
            }
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
                    LinkEvaluation linkEvaluation = expressionAnalyzer.linkEvaluation(methodInfo, previous,
                            stageOfPrevious, lv.assignmentExpression());
                    VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(lv);
                    clcBuilder.addLink(linkEvaluation.linkedVariables(), vi);
                    clcBuilder.addLinkEvaluation(linkEvaluation, vd);
                    clcBuilder.addAssignment(vi.variable(), linkEvaluation.staticValues());
                }
            });
        } else if (statement instanceof ExpressionAsStatement
                   || statement instanceof ReturnStatement
                   || statement instanceof IfElseStatement) {
            LinkEvaluation linkEvaluation = expressionAnalyzer.linkEvaluation(methodInfo, previous, stageOfPrevious,
                    statement.expression());

            if (statement instanceof ReturnStatement) {
                ReturnVariable rv = new ReturnVariableImpl(methodInfo);
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(rv);
                clcBuilder.addLink(linkEvaluation.linkedVariables(), vi);
                StaticValues sv = linkEvaluation.staticValues();
                // see TestStaticValuesAssignment: when returning this, we add the static values of the fields
                if (sv.expression() instanceof VariableExpression ve && ve.variable() instanceof This) {
                    Map<Variable, Expression> map =
                            previous.variableInfoStream(stageOfPrevious)
                                    .filter(vi2 -> vi2.variable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                                    .filter(vi2 -> vi2.staticValues() != null && vi2.staticValues().expression() != null)
                                    .collect(Collectors.toUnmodifiableMap(VariableInfo::variable, vi2 -> vi2.staticValues().expression()));
                    if (!map.isEmpty()) {
                        clcBuilder.addAssignment(rv, new StaticValuesImpl(null, sv.expression(), map));
                    } else {
                        clcBuilder.addAssignment(rv, sv);
                    }
                } else {
                    clcBuilder.addAssignment(rv, sv);
                }
            }
            clcBuilder.addLinkEvaluation(linkEvaluation, vd);
        }
        clcBuilder.write(vd, Stage.EVALUATION, previous, stageOfPrevious);

        if (statement.hasSubBlocks()) {
            Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vd);
            vd.variableInfoContainerStream().forEach(vic -> {
                if (vic.hasMerge()) {
                    Variable variable = vic.variable();
                    VariableInfoImpl merge = (VariableInfoImpl) vic.best();

                    LinkedVariables reducedLv = computeLinkedVariablesMerge(lastOfEachSubBlock, variable);
                    merge.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                    merge.setLinkedVariables(reducedLv);

                    StaticValues reducedSv = computeStaticValuesMerge(lastOfEachSubBlock, variable);
                    merge.staticValuesSet(reducedSv);

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

    private static StaticValues computeStaticValuesMerge(Map<String, VariableData> lastOfEachSubBlock, Variable variable) {
        List<StaticValues> staticValuesList = lastOfEachSubBlock.values().stream()
                .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                .filter(Objects::nonNull)
                .map(VariableInfoContainer::best)
                .map(VariableInfo::staticValues)
                .filter(Objects::nonNull)
                .toList();
        return staticValuesList.stream().reduce(NONE, StaticValues::merge);
    }

    private static LinkedVariables computeLinkedVariablesMerge(Map<String, VariableData> lastOfEachSubBlock, Variable variable) {
        List<LinkedVariables> linkedVariablesList = lastOfEachSubBlock.values().stream()
                .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                .filter(Objects::nonNull)
                .map(VariableInfoContainer::best)
                .map(VariableInfo::linkedVariables)
                .filter(Objects::nonNull)
                .toList();
        return linkedVariablesList.stream().reduce(EMPTY, LinkedVariables::merge);
    }

    /*
    the information flow is fixed, because it is a single pass system:

    - analyze the method's statements, and copy what is possible to the parameters and the method
    - analyze the fields and their initializers
    - copy from fields to parameters where relevant

     */
    public void doPrimaryType(TypeInfo primaryType, List<Info> analysisOrder) {
        LOGGER.info("Start primary type {}", primaryType);
        Map<FieldInfo, List<StaticValues>> svMap = new HashMap<>();
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo mi) {
                doMethod(mi);
                appendToFieldStaticValueMap(mi, svMap);
            } else if (info instanceof FieldInfo fi) {
                doField(fi, svMap.get(fi));
            } else if (info instanceof TypeInfo ti) {
                LOGGER.info("Do type {}", ti);
                fromParameterToField(ti);
            }
        }
    }

    private void fromParameterToField(TypeInfo typeInfo) {
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

    private void appendToFieldStaticValueMap(MethodInfo methodInfo, Map<FieldInfo, List<StaticValues>> svMap) {
        if (methodInfo.methodBody().isEmpty()) return;
        Statement lastStatement = methodInfo.methodBody().lastStatement();
        VariableData vd = lastStatement.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        vd.variableInfoStream()
                .filter(vi -> vi.variable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                .filter(vi -> vi.staticValues() != null)
                .forEach(vi -> {
                    FieldInfo fieldInfo = ((FieldReference) vi.variable()).fieldInfo();
                    svMap.computeIfAbsent(fieldInfo, l -> new ArrayList<>()).add(vi.staticValues());
                });
    }

    private void doField(FieldInfo fieldInfo, List<StaticValues> staticValuesList) {
        // initial field assignments
        StaticValues reduced = staticValuesList == null ? NONE
                : staticValuesList.stream().reduce(StaticValuesImpl.NONE, StaticValues::merge);
        if (!fieldInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_FIELD)) {
            fieldInfo.analysis().set(STATIC_VALUES_FIELD, reduced);
            LOGGER.info("Do field {}: set {}", fieldInfo, reduced);
        }
    }
}
