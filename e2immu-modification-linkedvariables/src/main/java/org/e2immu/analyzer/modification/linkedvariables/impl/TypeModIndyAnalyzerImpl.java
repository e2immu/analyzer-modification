package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.common.AnalyzerException;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.TypeModIndyAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;
import java.util.function.Predicate;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_FIELD;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.UNMODIFIED_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

/*
Phase 3.

Does modification and independence of parameters, and independence, fluent, identity of methods.
Also breaks internal cycles.

Modification of methods and linking of variables is done in Phase 1.
Linking, modification and independence of fields is done in Phase 2.
Immutable, independence of types is done in Phase 4.1.

Strategy:
method independence, parameter independence can directly be read from linked variables computed in field analyzer.
parameter modification is computed as the combination of links to fields and local modifications.

 */
public class TypeModIndyAnalyzerImpl extends CommonAnalyzerImpl implements TypeModIndyAnalyzer {
    private final AnalysisHelper analysisHelper = new AnalysisHelper();
    private final Runtime runtime;

    public TypeModIndyAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        super(configuration);
        this.runtime = runtime;
    }

    private record OutputImpl(List<AnalyzerException> analyzerExceptions, boolean resolvedInternalCycles,
                              Map<MethodInfo, Set<MethodInfo>> waitForMethodModifications,
                              Map<MethodInfo, Set<TypeInfo>> waitForTypeIndependence) implements Output {
    }

    @Override
    public Output go(TypeInfo typeInfo, Map<MethodInfo, Set<MethodInfo>> methodsWaitFor) {
        InternalAnalyzer ia = new InternalAnalyzer();
        try {
            ia.go(typeInfo);
        } catch (RuntimeException re) {
            if (configuration.storeErrors()) {
                if (!(re instanceof AnalyzerException)) {
                    ia.analyzerExceptions.add(new AnalyzerException(typeInfo, re));
                }
            } else throw re;
        }
        return new OutputImpl(ia.analyzerExceptions, false, ia.waitForMethodModifications,
                ia.waitForTypeIndependence);
    }

    class InternalAnalyzer {
        List<AnalyzerException> analyzerExceptions = new LinkedList<>();
        Map<MethodInfo, Set<MethodInfo>> waitForMethodModifications = new HashMap<>();
        Map<MethodInfo, Set<TypeInfo>> waitForTypeIndependence = new HashMap<>();
        Map<MethodInfo, Set<FieldInfo>> waitForField = new HashMap<>();

        private void go(TypeInfo typeInfo) {
            typeInfo.constructorAndMethodStream()
                    .filter(mi -> !mi.isAbstract())
                    .forEach(this::go);
            fromNonFinalFieldToParameter(typeInfo);
        }

        private void go(MethodInfo methodInfo) {
            FieldValue fieldValue = methodInfo.analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            if (fieldValue.field() != null) {
                handleGetterSetter(methodInfo, fieldValue);
            } else if (methodInfo.explicitlyEmptyMethod()) {
                handleExplicitlyEmptyMethod(methodInfo);
            } else if (!methodInfo.methodBody().isEmpty()) {
                handleNormalMethod(methodInfo);
            }
        }

        private void handleNormalMethod(MethodInfo methodInfo) {
            Statement lastStatement = methodInfo.methodBody().lastStatement();
            assert lastStatement != null;
            VariableData variableData = VariableDataImpl.of(lastStatement);
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

            for (ParameterInfo pi : methodInfo.parameters()) {
                handleParameter(methodInfo, pi, variableData);
            }
        }

        private void handleParameter(MethodInfo methodInfo, ParameterInfo pi, VariableData variableData) {
            VariableInfoContainer vic = variableData.variableInfoContainerOrNull(pi.fullyQualifiedName());
            if (vic == null) return;
            VariableInfo vi = vic.best();
            Bool unmodified = vi.analysis().getOrDefault(UNMODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE);
            if (vi.linkedVariables() != null && (unmodified == null || unmodified.isTrue())) {
                for (Map.Entry<Variable, LV> entry : vi.linkedVariables()) {
                    if (entry.getKey() instanceof FieldReference fr && fr.scopeIsRecursivelyThis()) {
                        Bool unmodifiedField = fr.fieldInfo().analysis().getOrNull(UNMODIFIED_FIELD,
                                ValueImpl.BoolImpl.class);
                        if (unmodifiedField == null) {
                            unmodified = null;
                            waitForField.computeIfAbsent(methodInfo, m -> new HashSet<>()).add(fr.fieldInfo());
                            break;
                        } else if (unmodifiedField.isFalse()) {
                            unmodified = FALSE;
                            break;
                        }
                    }
                }
            }
            if (unmodified != null) {
                if (pi.analysis().setAllowControlledOverwrite(UNMODIFIED_PARAMETER, unmodified)) {
                    DECIDE.debug("MI: unmodified of parameter {} = {}", pi, unmodified);
                }
            } else {
                UNDECIDED.debug("MI: Unmodified of parameter {} undecided, waitForField {}", pi, waitForField);
            }
        }

        private void handleExplicitlyEmptyMethod(MethodInfo methodInfo) {
            methodInfo.analysis().setAllowControlledOverwrite(PropertyImpl.NON_MODIFYING_METHOD, TRUE);
            methodInfo.analysis().setAllowControlledOverwrite(PropertyImpl.INDEPENDENT_METHOD, INDEPENDENT);
            DECIDE.debug("MI: Decide non-modifying of method {} = true", methodInfo);
            DECIDE.debug("MI: Decide independent of method {} = independent", methodInfo);
            for (ParameterInfo pi : methodInfo.parameters()) {
                pi.analysis().setAllowControlledOverwrite(PropertyImpl.UNMODIFIED_PARAMETER, TRUE);
                pi.analysis().setAllowControlledOverwrite(PropertyImpl.INDEPENDENT_PARAMETER, INDEPENDENT);
                DECIDE.debug("MI: Decide unmodified of parameter {} = true", pi);
                DECIDE.debug("MI: Decide independent of parameter {} = independent", pi);
            }
        }

        private void handleGetterSetter(MethodInfo methodInfo, FieldValue fieldValue) {
            assert !methodInfo.isConstructor();
            // getter, setter
            Bool nonModifying = ValueImpl.BoolImpl.from(!fieldValue.setter());
            methodInfo.analysis().setAllowControlledOverwrite(NON_MODIFYING_METHOD, nonModifying);
            Independent independentFromType = analysisHelper.typeIndependentFromImmutableOrNull(fieldValue.field().type());
            if (independentFromType == null) {
                waitForTypeIndependence.computeIfAbsent(methodInfo, m -> new HashSet<>())
                        .add(fieldValue.field().type().bestTypeInfo());
                UNDECIDED.debug("MI: Independent of method {} undecided, wait for type independence {}", methodInfo,
                        waitForTypeIndependence);
            } else if (fieldValue.setter()) {
                handleSetter(methodInfo, fieldValue, independentFromType);
            } else {
                handleGetter(methodInfo, independentFromType);
            }
        }

        private void handleGetter(MethodInfo methodInfo, Independent independentFromType) {
            methodInfo.analysis().setAllowControlledOverwrite(INDEPENDENT_METHOD, independentFromType);
            DECIDE.debug("MI: Decide independent of method {} = {}}", methodInfo, independentFromType);
            for (ParameterInfo pi : methodInfo.parameters()) {
                pi.analysis().setAllowControlledOverwrite(PropertyImpl.UNMODIFIED_PARAMETER, TRUE);
                DECIDE.debug("MI: Decide unmodified of getter parameter: {} = true", pi);
            }
        }

        private void handleSetter(MethodInfo methodInfo, FieldValue fieldValue, Independent independentFromType) {
            if (independentFromType.isIndependent()) {
                // must be unmodified, otherwise, we'll have to wait for a value to come from the field
                for (ParameterInfo pi : methodInfo.parameters()) {
                    pi.analysis().setAllowControlledOverwrite(PropertyImpl.UNMODIFIED_PARAMETER, TRUE);
                    DECIDE.debug("MI: Decide unmodified of parameter because independent: {} = true", pi);
                }
            } else {
                Bool unmodifiedField = fieldValue.field().analysis().getOrNull(UNMODIFIED_FIELD,
                        ValueImpl.BoolImpl.class);
                if (unmodifiedField == null) {
                    waitForField.computeIfAbsent(methodInfo, m -> new HashSet<>())
                            .add(fieldValue.field());
                    UNDECIDED.debug("MI: Unmodified of setter parameter of {} undecided: wait for field {}",
                            methodInfo, waitForField);
                } else {
                    for (ParameterInfo pi : methodInfo.parameters()) {
                        if (pi.index() == fieldValue.parameterIndexOfIndex()) {
                            pi.analysis().setAllowControlledOverwrite(PropertyImpl.UNMODIFIED_PARAMETER, TRUE);
                            DECIDE.debug("MI: Decide unmodified of setter index parameter: {} = true", pi);
                        } else {
                            pi.analysis().setAllowControlledOverwrite(PropertyImpl.UNMODIFIED_PARAMETER, unmodifiedField);
                            DECIDE.debug("MI: Decide unmodified of setter parameter: {} = {}", pi, unmodifiedField);
                        }
                    }
                }
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
                DECIDE.debug("MI: Decide {} of {} = {}", property, methodInfo, identityFluent);
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

            Value.Independent independentMethod = doIndependentMethod(methodInfo, lastOfMainBlock);
            if (independentMethod != null) {
                if (methodInfo.analysis().setAllowControlledOverwrite(PropertyImpl.INDEPENDENT_METHOD, independentMethod)) {
                    DECIDE.debug("MI: Decide independent of method {} = {}", methodInfo, independentMethod);
                }
            } else {
                UNDECIDED.debug("MI: Independent of method undecided: {}", methodInfo);
            }
            for (ParameterInfo pi : methodInfo.parameters()) {
                Value.Independent independent = doIndependentParameter(pi, lastOfMainBlock);
                if (independent != null) {
                    if (pi.analysis().setAllowControlledOverwrite(PropertyImpl.INDEPENDENT_PARAMETER, independent)) {
                        DECIDE.debug("MI: Decide independent of parameter {} = {}", pi, independent);
                    }
                } else {
                    UNDECIDED.debug("MI: Independent of parameter {} undecided", pi);
                }
            }
        }

        private Value.Independent doIndependentParameter(ParameterInfo pi, VariableData lastOfMainBlock) {
            boolean typeIsImmutable = analysisHelper.typeImmutable(pi.parameterizedType()).isImmutable();
            if (typeIsImmutable) return INDEPENDENT;
            if (pi.methodInfo().isAbstract() || pi.methodInfo().methodBody().isEmpty()) return DEPENDENT;
            return worstLinkToFields(lastOfMainBlock, pi.fullyQualifiedName());
        }

        private Value.Independent doIndependentMethod(MethodInfo methodInfo, VariableData lastOfMainBlock) {
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

        private Value.Independent worstLinkToFields(VariableData lastOfMainBlock, String variableFqn) {
            assert lastOfMainBlock != null;
            VariableInfoContainer vic = lastOfMainBlock.variableInfoContainerOrNull(variableFqn);
            if (vic == null) return INDEPENDENT; // variable does not occur.
            VariableInfo viRv = vic.best();
            if (viRv.linkedVariables() == null) {
                return null; // not yet
            }
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
                        } else {
                            // FIXME waitFor
                        }
                    });
            return svMapParameters;
        }
    }
}
