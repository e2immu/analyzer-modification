package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.linkedvariables.staticvalues.StaticValuesHelper;
import org.e2immu.analyzer.modification.prepwork.hcs.ComputeHCS;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.common.defaults.ShallowMethodAnalyzer;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.element.Source;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.translate.TranslationMap;
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

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.Independent;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.SetOfInfo;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.VariableBooleanMap;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.*;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

public class ModAnalyzerImpl implements ModAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModAnalyzerImpl.class);
    private static final Logger LOGGER_GRAPH = LoggerFactory.getLogger("graph-algorithm");

    private final Runtime runtime;
    private final ComputeLinkCompletion computeLinkCompletion;
    private final ExpressionAnalyzer expressionAnalyzer;
    private final ShallowMethodAnalyzer shallowMethodAnalyzer;
    private final AnalysisHelper analysisHelper;
    private final GetSetHelper getSetHelper;
    private final ComputeImmutable computeImmutable;

    private final List<Throwable> problemsRaised = new LinkedList<>();
    private final Map<String, Integer> histogram = new HashMap<>();
    private final boolean storeErrorsInPVMap;

    public ModAnalyzerImpl(Runtime runtime, boolean storeErrorsInPVMap) {
        this.runtime = runtime;
        StaticValuesHelper staticValuesHelper = new StaticValuesHelper(runtime);
        expressionAnalyzer = new ExpressionAnalyzer(runtime, this, staticValuesHelper);
        shallowMethodAnalyzer = new ShallowMethodAnalyzer(runtime, Element::annotations);
        this.analysisHelper = new AnalysisHelper();
        computeLinkCompletion = new ComputeLinkCompletion(analysisHelper, staticValuesHelper); // has a cache, we want this to be stable
        this.getSetHelper = new GetSetHelper(runtime);
        computeImmutable = new ComputeImmutable();
        this.storeErrorsInPVMap = storeErrorsInPVMap;
    }

    @Override
    public void doMethod(MethodInfo methodInfo) {
        LOGGER.debug("Do method {}", methodInfo);
        ComputeHCS.safeHcsMethod(runtime, methodInfo);
        assert methodInfo.parameters().stream().allMatch(pi -> pi.analysis().haveAnalyzedValueFor(HCS_PARAMETER))
                : "Method with a parameter without HCS: " + methodInfo;

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
        // TODO this is a temporary fail-safe, to avoid problems
        //  in case of a synthetic method without variables, INDEPENDENT would be correct.
        //  in case of a synthetic method without code, DEPENDENT may be the best choice
        if (lastOfMainBlock == null) return DEPENDENT; // happens in some synthetic cases
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
                        Value.VariableBooleanMap vbm = translateVariableBooleanMapToThisScope(pi, modifiedComponents);
                        pi.analysis().set(MODIFIED_COMPONENTS_PARAMETER, vbm);
                    }
                }
                Value.VariableBooleanMap mfi = vi.analysis().getOrNull(VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE,
                        ValueImpl.VariableBooleanMapImpl.class);
                if (mfi != null && !mfi.map().isEmpty()
                    && !pi.analysis().haveAnalyzedValueFor(MODIFIED_FI_COMPONENTS_PARAMETER)) {
                    VariableBooleanMap thisScope = translateVariableBooleanMapToThisScope(pi, mfi.map());
                    pi.analysis().set(MODIFIED_FI_COMPONENTS_PARAMETER, thisScope);
                }
            } else if (v instanceof ReturnVariable && methodInfo.hasReturnValue()) {
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
            } else if (v instanceof This
                       || (v instanceof FieldReference fr && (fr.scopeIsRecursivelyThis() || fr.isStatic()))
                          && fr.fieldInfo().analysis().getOrDefault(IGNORE_MODIFICATIONS_FIELD, FALSE).isFalse()
                       || vi.isVariableInClosure()) {
                boolean modification = vi.analysis().getOrDefault(MODIFIED_VARIABLE, FALSE).isTrue();
                boolean assignment = !vi.assignments().isEmpty();
                if ((modification || assignment) && !methodInfo.isConstructor()) {
                    if (!methodInfo.analysis().haveAnalyzedValueFor(MODIFIED_METHOD)) {
                        methodInfo.analysis().set(MODIFIED_METHOD, TRUE);
                    }
                    if (v instanceof FieldReference || vi.isVariableInClosure()) {
                        modifiedComponentsMethod.put(v, true);
                    }
                }
                if (modification && v instanceof FieldReference fr
                    && !fr.fieldInfo().analysis().haveAnalyzedValueFor(MODIFIED_FIELD)) {
                    fr.fieldInfo().analysis().set(MODIFIED_FIELD, TRUE);
                }
                if (modification && vi.isVariableInClosure()) {
                    VariableData vd = vi.variableInfoInClosure();
                    VariableInfo outerVi = vd.variableInfo(vi.variable().fullyQualifiedName());
                    if (!outerVi.analysis().haveAnalyzedValueFor(MODIFIED_VARIABLE)) {
                        outerVi.analysis().set(MODIFIED_VARIABLE, TRUE);
                    }
                }
            }
            if (methodInfo.isConstructor()
                && v instanceof FieldReference fr && fr.scopeIsThis() && fr.fieldInfo().isPropertyFinal()) {
                StaticValues sv = vi.staticValues();
                if (sv != null && sv.expression() instanceof VariableExpression ve
                    && ve.variable() instanceof ParameterInfo pi) {
                    if (!pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                        VariableExpression veFr = runtime.newVariableExpressionBuilder()
                                .setVariable(fr).setSource(ve.source())
                                .build();
                        StaticValues newSv = new StaticValuesImpl(null, veFr, false, Map.of());
                        pi.analysis().set(STATIC_VALUES_PARAMETER, newSv);
                    }
                    if (!pi.analysis().haveAnalyzedValueFor(PARAMETER_ASSIGNED_TO_FIELD)) {
                        pi.analysis().set(PARAMETER_ASSIGNED_TO_FIELD, new ValueImpl.AssignedToFieldImpl(Set.of(fr.fieldInfo())));
                    }
                }
            }
            if (v instanceof This && !methodInfo.hasReturnValue()) {
                StaticValues staticValues = vi.staticValues();
                if (staticValues != null && !methodInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_METHOD)) {
                    StaticValues filtered = staticValues.remove(vv -> vv instanceof LocalVariable);
                    methodInfo.analysis().set(STATIC_VALUES_METHOD, filtered);
                }
            }
        }
        if (!methodInfo.analysis().haveAnalyzedValueFor(MODIFIED_COMPONENTS_METHOD) && !modifiedComponentsMethod.isEmpty()) {
            methodInfo.analysis().set(MODIFIED_COMPONENTS_METHOD, new ValueImpl.VariableBooleanMapImpl(modifiedComponentsMethod));
        }
    }

    private VariableBooleanMap translateVariableBooleanMapToThisScope(ParameterInfo pi, Map<Variable, Boolean> vbm) {
        This thisVar = runtime.newThis(pi.parameterizedType().typeInfo().asParameterizedType());
        Map<Variable, Boolean> map = vbm.entrySet().stream().collect(Collectors
                .toUnmodifiableMap(e -> replaceScope(e.getKey(), thisVar), Map.Entry::getValue));
        return new ValueImpl.VariableBooleanMapImpl(map);
    }

    private Variable replaceScope(Variable v, Variable newScope) {
        Variable frScope = v.fieldReferenceBase();
        if (frScope != null) {
            TranslationMap tm = runtime.newTranslationMapBuilder().put(frScope, newScope).build();
            return tm.translateVariableRecursively(v);
        }
        return v;
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

    @Override
    public VariableData doBlock(MethodInfo methodInfo,
                                Block block,
                                VariableData vdOfParent) {
        VariableData previous = vdOfParent;
        boolean first = true;
        for (Statement statement : block.statements()) {
            try {
                previous = doStatement(methodInfo, statement, previous, first);
            } catch (Throwable re) {
                LOGGER.error("Have error analyzing statement {}, {}, in method {}",
                        statement, statement.source(), methodInfo);
                throw re;
            }
            if (first) first = false;
        }
        return previous;
    }

    @Override
    public VariableData doStatement(MethodInfo methodInfo,
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
                    StaticValues staticValues = evaluationResult.gatherAllStaticValues(runtime);
                    clcBuilder.addAssignment(vi.variable(), staticValues);
                }
            });
        } else if (statement instanceof ExpressionAsStatement
                   || statement instanceof ReturnStatement
                   || statement instanceof YieldStatement
                   || statement instanceof IfElseStatement
                   || statement instanceof LoopStatement
                   || statement instanceof SynchronizedStatement
                   || statement instanceof ThrowStatement
                   || statement instanceof SwitchStatementOldStyle
                   || statement instanceof SwitchStatementNewStyle
        ) {
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
                            previous == null ? Map.of() :
                                    previous.variableInfoStream(stageOfPrevious)
                                            .filter(vi2 -> vi2.variable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis())
                                            .filter(vi2 -> vi2.staticValues() != null && vi2.staticValues().expression() != null)
                                            .collect(Collectors.toUnmodifiableMap(VariableInfo::variable, vi2 -> vi2.staticValues().expression()));
                    if (!map.isEmpty()) {
                        clcBuilder.addAssignment(rv, new StaticValuesImpl(null, sv.expression(), false, map));
                    } else {
                        clcBuilder.addAssignment(rv, sv);
                    }
                } else {
                    clcBuilder.addAssignment(rv, sv);
                }
            } else if (statement instanceof ForEachStatement forEach) {
                LinkedVariables lvs;
                VariableInfoImpl vi = (VariableInfoImpl) vd.variableInfo(forEach.initializer().localVariable());
                lvs = computeForEachLinkedVariables(methodInfo, previous, forEach, stageOfPrevious);
                clcBuilder.addLink(lvs, vi);
            }
            clcBuilder.addLinkEvaluation(evaluationResult, vd);
        } else if (statement instanceof ExplicitConstructorInvocation eci) {
            eci.parameterExpressions().forEach(expression -> {
                EvaluationResult er = expressionAnalyzer.linkEvaluation(methodInfo, previous, stageOfPrevious, expression);
                clcBuilder.addLinkEvaluation(er, vd);
            });
        } // else: resources of Try statement are handled in doBlocks
        clcBuilder.write(vd, Stage.EVALUATION, previous, stageOfPrevious, statement.source().index(), statement.source());

        if (statement.hasSubBlocks()) {
            Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vd);
            if (!lastOfEachSubBlock.isEmpty()) {
                vd.variableInfoContainerStream().forEach(vic -> {
                    if (vic.hasMerge()) {
                        mergeBlocks(vic, lastOfEachSubBlock, vd);
                    }
                });
            }
        }
        return vd;
    }

    private LinkedVariables computeForEachLinkedVariables(MethodInfo methodInfo,
                                                          VariableData previous,
                                                          ForEachStatement forEach,
                                                          Stage stageOfPrevious) {
        LinkedVariables lvs;
        TypeInfo iterator = runtime.getFullyQualified(Iterator.class, false);
        TypeInfo iterableType = runtime.getFullyQualified(Iterable.class, false);
        if (iterator == null || iterableType == null) {
            lvs = EMPTY;
        } else {
            Source source = forEach.expression().source();
            ParameterizedType initType = forEach.initializer().localVariable().parameterizedType();
            MethodInfo iterableIterator = iterableType.findUniqueMethod("iterator", 0);
            ParameterizedType concreteIteratorType = runtime.newParameterizedType(iterator,
                    List.of(initType.ensureBoxed(runtime)));
            MethodCall mcIterator = runtime.newMethodCallBuilder()
                    .setSource(source)
                    .setObject(forEach.expression())
                    .setMethodInfo(iterableIterator)
                    .setParameterExpressions(List.of())
                    .setConcreteReturnType(concreteIteratorType)
                    .build();
            MethodInfo iteratorNext = iterator.findUniqueMethod("next", 0);
            MethodCall mc = runtime.newMethodCallBuilder()
                    .setSource(source)
                    .setObject(mcIterator)
                    .setMethodInfo(iteratorNext)
                    .setParameterExpressions(List.of(runtime.newInt(List.of(), source, 0)))
                    .setConcreteReturnType(initType)
                    .build();
            EvaluationResult ev2 = expressionAnalyzer.linkEvaluation(methodInfo, previous, stageOfPrevious, mc);
            lvs = ev2.linkedVariables();
        }
        return lvs;
    }

    private static void mergeBlocks(VariableInfoContainer vic, Map<String, VariableData> lastOfEachSubBlock, VariableData vd) {
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
    @Override
    public void doPrimaryType(TypeInfo primaryType, List<Info> analysisOrder) {
        LOGGER.debug("Start primary type {}", primaryType);
        go(analysisOrder);
    }

    /*
    there is no real need to analyze per primary type
     */

    @Override
    public void go(List<Info> analysisOrder) {
        Map<FieldInfo, List<StaticValues>> svMap = new HashMap<>();
        for (Info info : analysisOrder) {
            try {
                if (info instanceof MethodInfo mi) {
                    doMethod(mi);
                    appendToFieldStaticValueMap(mi, svMap);
                } else if (info instanceof FieldInfo fi) {
                    TypeInfo primaryType = fi.owner().primaryType();
                    Value.SetOfInfo partOfConstruction = primaryType.analysis().getOrNull(PART_OF_CONSTRUCTION,
                            ValueImpl.SetOfInfoImpl.class);
                    doField(fi, svMap.get(fi), partOfConstruction);
                } else if (info instanceof TypeInfo ti) {
                    LOGGER.debug("Do type {}", ti);
                    fromNonFinalFieldToParameter(ti);
                    computeImmutable.go(ti);
                }
                histogram.merge(info.info(), 1, Integer::sum);
            } catch (Exception | AssertionError problem) {
                LOGGER.error("Caught exception/error analyzing {}: {}", info, problem.getMessage());
                if (storeErrorsInPVMap) {
                    problemsRaised.add(problem);
                    String errorMessage = Objects.requireNonNullElse(problem.getMessage(), "<no message>");
                    String fullMessage = "ANALYZER ERROR: " + errorMessage;
                    info.analysis().set(ANALYZER_ERROR, new ValueImpl.MessageImpl(fullMessage));
                } else {
                    throw problem;
                }
            }
        }
    }

    private void fromNonFinalFieldToParameter(TypeInfo typeInfo) {
        Map<ParameterInfo, StaticValues> svMapParameters = collectReverseFromNonFinalFieldsToParameters(typeInfo);
        svMapParameters.forEach((pi, sv) -> {
            if (!pi.analysis().haveAnalyzedValueFor(STATIC_VALUES_PARAMETER)) {
                pi.analysis().set(STATIC_VALUES_PARAMETER, sv);
            }
            if (sv.expression() instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr && fr.scopeIsThis()) {
                if (!pi.analysis().haveAnalyzedValueFor(PARAMETER_ASSIGNED_TO_FIELD)) {
                    pi.analysis().set(PARAMETER_ASSIGNED_TO_FIELD, new ValueImpl.AssignedToFieldImpl(Set.of(fr.fieldInfo())));
                }
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
                        VariableExpression reverseVe = runtime.newVariableExpressionBuilder()
                                .setVariable(runtime.newFieldReference(fieldInfo))
                                .setSource(pi.source())
                                .build();
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
            LOGGER.debug("Do field {}: set {}", fieldInfo, reduced);
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

    @Override
    public List<Throwable> getProblemsRaised() {
        return List.copyOf(problemsRaised);
    }

    @Override
    public Map<String, Integer> getHistogram() {
        return Map.copyOf(histogram);
    }
}
