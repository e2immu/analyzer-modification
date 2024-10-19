package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.analyzer.shallow.analyzer.ShallowMethodAnalyzer;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.*;
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
import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

public class Analyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Analyzer.class);
    private static final Logger LOGGER_GRAPH = LoggerFactory.getLogger("graph-algorithm");

    private final Runtime runtime;
    private final ComputeLinkCompletion computeLinkCompletion;
    private final ExpressionAnalyzer expressionAnalyzer;
    private final ShallowMethodAnalyzer shallowMethodAnalyzer;
    private final AnalysisHelper analysisHelper;
    private final GetSetHelper getSetHelper;
    private final ComputeImmutable computeImmutable;

    public Analyzer(Runtime runtime) {
        this.runtime = runtime;
        expressionAnalyzer = new ExpressionAnalyzer(runtime);
        computeLinkCompletion = new ComputeLinkCompletion(runtime); // has a cache, we want this to be stable
        shallowMethodAnalyzer = new ShallowMethodAnalyzer(Element::annotations);
        this.analysisHelper = new AnalysisHelper();
        this.getSetHelper = new GetSetHelper(runtime);
        computeImmutable = new ComputeImmutable();
    }

    public void doMethod(MethodInfo methodInfo) {
        LOGGER.info("Do method {}", methodInfo);

        assert methodInfo.isConstructor() || methodInfo.analysis().haveAnalyzedValueFor(HCS_METHOD);
        assert methodInfo.parameters().stream().allMatch(pi -> pi.analysis().haveAnalyzedValueFor(HCS_PARAMETER));

        if (methodInfo.isAbstract()) {
            shallowMethodAnalyzer.analyze(methodInfo);
            // TODO consider moving this into the shallow analyzer!
            getSetHelper.copyStaticValuesForGetSet(methodInfo);
        } else {
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
                pi.analysis().set(PropertyImpl.INDEPENDENT_PARAMETER, independent);
            }
        }
    }

    private Independent doIndependentParameter(ParameterInfo pi, VariableData lastOfMainBlock) {
        boolean typeIsImmutable = analysisHelper.typeImmutable(pi.parameterizedType()).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        if (pi.methodInfo().isAbstract() || pi.methodInfo().methodBody().isEmpty()) return DEPENDENT;
        return worstLinkToFields(lastOfMainBlock, pi.fullyQualifiedName());
    }

    private Independent doIndependentMethod(MethodInfo methodInfo, VariableData lastOfMainBlock) {
        if (methodInfo.isConstructor() || methodInfo.noReturnValue()) return INDEPENDENT;
        if (methodInfo.isAbstract()) {
            return DEPENDENT; // must be annotated otherwise
        }
        boolean fluent = methodInfo.analysis().getOrDefault(FLUENT_METHOD, FALSE).isTrue();
        if (fluent) return INDEPENDENT;
        boolean typeIsImmutable = analysisHelper.typeImmutable(methodInfo.returnType()).isImmutable();
        if (typeIsImmutable) return INDEPENDENT;
        return worstLinkToFields(lastOfMainBlock, methodInfo.fullyQualifiedName());
    }

    private Independent worstLinkToFields(VariableData lastOfMainBlock, String variableFqn) {
        assert lastOfMainBlock != null;
        VariableInfoContainer vic = lastOfMainBlock.variableInfoContainerOrNull(variableFqn);
        if (vic == null) return INDEPENDENT; // variable does not occur.
        VariableInfo viRv = vic.best();
        LV worstLinkToFields = viRv.linkedVariables().stream()
                .filter(e -> e.getKey() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                .map(Map.Entry::getValue)
                .min(LV::compareTo).orElse(LVImpl.LINK_INDEPENDENT);
        if (worstLinkToFields.isStaticallyAssignedOrAssigned()) {
            Value.Immutable immField = viRv.linkedVariables().stream()
                    .filter(e -> e.getValue().isStaticallyAssignedOrAssigned())
                    .map(Map.Entry::getKey)
                    .filter(v -> v instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                    .map(v -> analysisHelper.typeImmutable(v.parameterizedType()))
                    .findFirst().orElseThrow();
            return immField.toCorrespondingIndependent();
        }
        if (worstLinkToFields.equals(LVImpl.LINK_INDEPENDENT)) return INDEPENDENT;
        if (worstLinkToFields.isCommonHC()) return INDEPENDENT_HC;

        return DEPENDENT;
    }

    private void copyFromVariablesIntoMethod(MethodInfo methodInfo, VariableData variableData) {
        Map<Variable, Boolean> modifiedComponentsMethod = new HashMap<>();
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
                // IMPORTANT: we also store this in case of !modified; see TestLinkCast,2
                // a parameter can be of type Object, not modified, even though, via casting, its hidden content is modified
                if (!pi.analysis().haveAnalyzedValueFor(MODIFIED_COMPONENTS_PARAMETER)) {
                    Map<Variable, Boolean> modifiedComponents = computeModifiedComponents(variableData, pi);
                    if (!modifiedComponents.isEmpty()) {
                        Value.VariableBooleanMap vbm = new ValueImpl.VariableBooleanMapImpl(modifiedComponents);
                        pi.analysis().set(MODIFIED_COMPONENTS_PARAMETER, vbm);
                    }
                }
                Value.VariableBooleanMap mfi = vi.analysis().getOrNull(VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE,
                        ValueImpl.VariableBooleanMapImpl.class);
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
                Value.SetOfInfo set = methodInfo.typeInfo().analysis().getOrDefault(PART_OF_CONSTRUCTION,
                        EMPTY_PART_OF_CONSTRUCTION);
                boolean modification = vi.analysis().getOrDefault(MODIFIED_VARIABLE, FALSE).isTrue();
                boolean assignment = vi.assignments().size() > 0;
                if ((modification || assignment)
                    && !methodInfo.analysis().haveAnalyzedValueFor(MODIFIED_METHOD)
                    && !set.infoSet().contains(methodInfo)) {
                    methodInfo.analysis().set(MODIFIED_METHOD, TRUE);
                    if (v instanceof FieldReference) {
                        modifiedComponentsMethod.put(v, true);
                    }
                }
            }
            if (methodInfo.isConstructor()
                && v instanceof FieldReference fr && fr.scopeIsThis() && fr.fieldInfo().isPropertyFinal()) {
                StaticValues sv = vi.staticValues();
                if (sv != null && sv.expression() instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi
                    && !pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                    StaticValues newSv = new StaticValuesImpl(null, runtime.newVariableExpression(fr), Map.of());
                    pi.analysis().set(STATIC_VALUES_PARAMETER, newSv);
                }
            }
            if (v instanceof This && !methodInfo.hasReturnValue()) {
                StaticValues staticValues = vi.staticValues();
                if (staticValues != null) {
                    StaticValues filtered = staticValues.remove(vv -> vv instanceof LocalVariable);
                    methodInfo.analysis().set(STATIC_VALUES_METHOD, filtered);
                }
            }
        }
        if (!methodInfo.analysis().haveAnalyzedValueFor(MODIFIED_COMPONENTS_METHOD) && !modifiedComponentsMethod.isEmpty()) {
            methodInfo.analysis().set(MODIFIED_COMPONENTS_METHOD, new ValueImpl.VariableBooleanMapImpl(modifiedComponentsMethod));
        }
    }

    // step 1: modification check on fields of parameter
    // step 2: modification check on variables who have been assigned to a field of a parameter
    private Map<Variable, Boolean> computeModifiedComponents(VariableData variableData, ParameterInfo pi) {
        if (pi.parameterizedType().isTypeParameter()) return Map.of();
        Stream<Variable> step1 = variableData.variableInfoStream()
                .filter(vi -> vi.variable() instanceof FieldReference fr && fr.scopeIsRecursively(pi))
                .filter(VariableInfo::isModified)
                .map(VariableInfo::variable);
        Stream<Variable> step2 = variableData.variableInfoStream()
                .filter(VariableInfo::isModified)
                .map(vi -> {
                    StaticValues sv = vi.staticValues();
                    if (sv != null && sv.expression() instanceof VariableExpression ve && ve.variable().scopeIsRecursively(pi)) {
                        // this catches fields of a parameter, and array indexing in that parameter
                        return ve.variable();
                    }
                    return null;
                })
                .filter(Objects::nonNull);
        return Stream.concat(step1, step2)
                .distinct() // we could be in both!
                .collect(Collectors.toUnmodifiableMap(v -> v, v -> true));
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
        LOGGER_GRAPH.debug("Statement {}", statement.source());
        Stage stageOfPrevious = first ? Stage.EVALUATION : Stage.MERGE;
        VariableData vd = VariableDataImpl.of(statement);
        assert vd != null : "No variable data in " + statement + " source " + statement.source();
        ComputeLinkCompletion.Builder clcBuilder = computeLinkCompletion.new Builder();

        if (statement instanceof LocalVariableCreation lvc) {
            lvc.localVariableStream().forEach(lv -> {
                if (!lv.assignmentExpression().isEmpty()) {
                    EvaluationResult evaluationResult = expressionAnalyzer.linkEvaluation(methodInfo, previous,
                            stageOfPrevious, lv.assignmentExpression());
                    VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(lv);
                    clcBuilder.addLink(evaluationResult.linkedVariables(), vi);
                    clcBuilder.addLinkEvaluation(evaluationResult, vd);
                    clcBuilder.addAssignment(vi.variable(), evaluationResult.staticValues());
                }
            });
        } else if (statement instanceof ExpressionAsStatement
                   || statement instanceof ReturnStatement
                   || statement instanceof IfElseStatement) {
            EvaluationResult evaluationResult = expressionAnalyzer.linkEvaluation(methodInfo, previous, stageOfPrevious,
                    statement.expression());

            if (statement instanceof ReturnStatement) {
                ReturnVariable rv = new ReturnVariableImpl(methodInfo);
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(rv);
                clcBuilder.addLink(evaluationResult.linkedVariables(), vi);
                StaticValues sv = evaluationResult.staticValues();
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
            clcBuilder.addLinkEvaluation(evaluationResult, vd);
        }
        clcBuilder.write(vd, Stage.EVALUATION, previous, stageOfPrevious, statement.source().index());

        if (statement.hasSubBlocks()) {
            Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vd);
            vd.variableInfoContainerStream().forEach(vic -> {
                if (vic.hasMerge()) {
                    Variable variable = vic.variable();
                    VariableInfoImpl merge = (VariableInfoImpl) vic.best();

                    LinkedVariables reducedLv = computeLinkedVariablesMerge(lastOfEachSubBlock, variable, vd);
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
                    if (!merge.analysis().haveAnalyzedValueFor(MODIFIED_FI_COMPONENTS_VARIABLE)) {
                        Map<Variable, Boolean> map = lastOfEachSubBlock.values().stream()
                                .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                                .filter(Objects::nonNull)
                                .map(VariableInfoContainer::best)
                                .flatMap(vi -> vi.analysis().getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE, ValueImpl.VariableBooleanMapImpl.EMPTY).map().entrySet().stream())
                                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue,
                                        (b1, b2) -> b1 || b2)); // TODO is this the correct merge function?
                        merge.analysis().set(MODIFIED_FI_COMPONENTS_VARIABLE, new ValueImpl.VariableBooleanMapImpl(map));
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

    private static LinkedVariables computeLinkedVariablesMerge(Map<String, VariableData> lastOfEachSubBlock,
                                                               Variable variable,
                                                               VariableData vdMerge) {
        List<LinkedVariables> linkedVariablesList = lastOfEachSubBlock.values().stream()
                .map(lastVd -> lastVd.variableInfoContainerOrNull(variable.fullyQualifiedName()))
                .filter(Objects::nonNull)
                .map(VariableInfoContainer::best)
                .map(VariableInfo::linkedVariables)
                .filter(Objects::nonNull)
                .toList();
        return linkedVariablesList.stream().reduce(EMPTY, LinkedVariables::merge).remove(v -> !vdMerge.isKnown(v.fullyQualifiedName()));
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
        Value.SetOfInfo partOfConstruction = primaryType.analysis().getOrNull(PART_OF_CONSTRUCTION, ValueImpl.SetOfInfoImpl.class);
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo mi) {
                doMethod(mi);
                appendToFieldStaticValueMap(mi, svMap);
            } else if (info instanceof FieldInfo fi) {
                doField(fi, svMap.get(fi), partOfConstruction);
            } else if (info instanceof TypeInfo ti) {
                LOGGER.info("Do type {}", ti);
                fromNonFinalFieldToParameter(ti);
                computeImmutable.go(ti);
            }
        }
    }

    private void fromNonFinalFieldToParameter(TypeInfo typeInfo) {
        Map<ParameterInfo, StaticValues> svMapParameters = collectReverseFromNonFinalFieldsToParameters(typeInfo);
        svMapParameters.forEach((pi, sv) -> {
            if (!pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                pi.analysis().set(STATIC_VALUES_PARAMETER, sv);
            }
        });
    }

    private Map<ParameterInfo, StaticValues> collectReverseFromNonFinalFieldsToParameters(TypeInfo typeInfo) {
        Map<ParameterInfo, StaticValues> svMapParameters = new HashMap<>();
        typeInfo.fields()
                .stream()
                .filter(f -> !f.isPropertyFinal())
                .forEach(fieldInfo -> {
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
        VariableData vd = VariableDataImpl.of(lastStatement);
        vd.variableInfoStream()
                .filter(vi -> vi.variable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                .filter(vi -> vi.staticValues() != null)
                .forEach(vi -> {
                    FieldInfo fieldInfo = ((FieldReference) vi.variable()).fieldInfo();
                    svMap.computeIfAbsent(fieldInfo, l -> new ArrayList<>()).add(vi.staticValues());
                });
    }

    private void doField(FieldInfo fieldInfo, List<StaticValues> staticValuesList, SetOfInfo partOfConstruction) {
        // initial field assignments
        StaticValues reduced = staticValuesList == null ? NONE
                : staticValuesList.stream().reduce(StaticValuesImpl.NONE, StaticValues::merge);
        if (!fieldInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_FIELD)) {
            fieldInfo.analysis().set(STATIC_VALUES_FIELD, reduced);
            LOGGER.info("Do field {}: set {}", fieldInfo, reduced);
        }
        if (!fieldInfo.analysis().haveAnalyzedValueFor(MODIFIED_FIELD)) {
            boolean modified = fieldInfo.owner().primaryType().recursiveMethodStream()
                    .filter(mi -> !partOfConstruction.infoSet().contains(mi))
                    .filter(mi -> !mi.methodBody().isEmpty())
                    .anyMatch(mi -> {
                        Statement lastStatement = mi.methodBody().lastStatement();
                        VariableData vd = VariableDataImpl.of(lastStatement);
                        return vd.variableInfoStream().anyMatch(vi -> vi.variable() instanceof FieldReference fr
                                                                      && fr.fieldInfo() == fieldInfo
                                                                      && vi.isModified());
                    });
            if (modified) {
                fieldInfo.analysis().set(MODIFIED_FIELD, TRUE);
            }
        }
    }
}
