package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.ComputeHCS;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hcs.IndexImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.*;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.*;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.language.cst.api.analysis.Value.Immutable;
import static org.e2immu.language.cst.api.analysis.Value.Independent;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

class LinkHelperMethod extends CommonLinkHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkHelperMethod.class);

    private final MethodInfo methodInfo;
    private final HiddenContentSelector hcsObject;
    private final LinkHelperParameter linkHelperParameter;
    private final LinkHelperCore linkHelperCore;
    private final LinkHelperBetweenParameters linkHelperBetweenParameters;

    public LinkHelperMethod(Runtime runtime,
                            GenericsHelper genericsHelper,
                            AnalysisHelper analysisHelper,
                            MethodInfo currentMethod,
                            MethodInfo methodInfo) {
        super(currentMethod.primaryType(), runtime, analysisHelper, genericsHelper);
        HiddenContentTypes hiddenContentTypes = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        this.methodInfo = methodInfo;
        ParameterizedType formalObject = this.methodInfo.typeInfo().asParameterizedType();
        this.hcsObject = HiddenContentSelector.selectAll(hiddenContentTypes, formalObject);
        this.linkHelperParameter = new LinkHelperParameter(currentMethod.primaryType(), runtime, analysisHelper,
                genericsHelper);
        this.linkHelperCore = new LinkHelperCore(methodInfo, runtime, analysisHelper,
                genericsHelper);
        this.linkHelperBetweenParameters = new LinkHelperBetweenParameters(runtime, linkHelperCore,
                linkHelperParameter);
    }

    public LinkHelperBetweenParameters linkHelperBetweenParameters() {
        return linkHelperBetweenParameters;
    }

    public record FromParameters(EvaluationResult intoObject, EvaluationResult intoResult) {
    }

    /*
    Add all necessary links from parameters into scope, and in-between parameters
     */
    public FromParameters linksInvolvingParameters(ParameterizedType objectPt,
                                                   ParameterizedType resultPt,
                                                   List<Expression> parameterExpressions,
                                                   List<EvaluationResult> linkedVariables) {
        EvaluationResult.Builder intoObjectBuilder = new EvaluationResult.Builder();
        EvaluationResult.Builder intoResultBuilder = resultPt == null || resultPt.isVoid() ? null
                : new EvaluationResult.Builder();
        if (!methodInfo.parameters().isEmpty()) {
            // links between object/return value and parameters
            int i = 0;
            int nMinusOne = methodInfo.parameters().size() - 1;
            for (Expression parameterExpression : parameterExpressions) {
                ParameterInfo pi = methodInfo.parameters().get(Math.min(nMinusOne, i));
                EvaluationResult evaluationResult = linkedVariables.get(i);
                if (!evaluationResult.linkedVariables().isEmpty()) {
                    linkParameterToObjectOrResult(pi, objectPt, resultPt, parameterExpression, evaluationResult,
                            intoResultBuilder, intoObjectBuilder);
                } // else: see e.g. link.TestArrayInitializer
                ++i;
            }
            linksBetweenParameters(intoObjectBuilder, methodInfo, parameterExpressions, linkedVariables);
        }
        return new FromParameters(intoObjectBuilder.build(), intoResultBuilder == null ? null : intoResultBuilder.build());
    }

    private void linkParameterToObjectOrResult(ParameterInfo pi,
                                               ParameterizedType objectPt,
                                               ParameterizedType resultPt,
                                               Expression parameterExpression,
                                               EvaluationResult evaluationResultOfParameter,
                                               EvaluationResult.Builder intoResultBuilder,
                                               EvaluationResult.Builder intoObjectBuilder) {
        Independent formalParameterIndependent = pi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER,
                DEPENDENT);

        Independent parameterToResult = independentParameterToResult(pi, formalParameterIndependent);
        boolean inResult = intoResultBuilder != null && !parameterToResult.isIndependent();
        if (!inResult && objectPt == null) return; // no chance
        if (formalParameterIndependent.isIndependent() && !inResult) {
            // formal parameter independent -> not linked to object
            // but if we're only interested in the object, there's nothing we can do
            return;
        }
        LinkedVariables lvsArgument = evaluationResultOfParameter.linkedVariables();
        if (inResult && pi.index() == 0 && methodInfo.isIdentity()) {
            intoResultBuilder.setLinkedVariables(lvsArgument);
            return;
        }
        ParameterizedType concreteArgumentType = parameterExpression.parameterizedType();
        HiddenContentSelector hcsParameter = pi.analysis().getOrCreate(HCS_PARAMETER,
                () -> new ComputeHCS(runtime).doHiddenContentSelector(methodInfo));
        if (hcsParameter.isNone()) return;
        LinkedVariables lvsArgumentCorrectedToHcsParameter = linkHelperParameter.linkedVariablesOfParameter(pi.parameterizedType(),
                concreteArgumentType, lvsArgument, hcsParameter,
                pi.isVarArgs());
        assert lvsArgumentCorrectedToHcsParameter.compatibleWith(hcsParameter);

        Independent independentOfObjectOrReturnValue;
        EvaluationResult.Builder builder;
        ParameterizedType concreteObjectOrReturnType;
        ParameterizedType formalObjectOrReturnType;
        if (inResult) {
            independentOfObjectOrReturnValue = parameterToResult;
            builder = intoResultBuilder;
            concreteObjectOrReturnType = resultPt;
            formalObjectOrReturnType = methodInfo.returnType();
        } else {
            independentOfObjectOrReturnValue = formalParameterIndependent;
            builder = intoObjectBuilder;
            concreteObjectOrReturnType = objectPt;
            formalObjectOrReturnType = methodInfo.typeInfo().asParameterizedType();
        }
        copyAdditionalLinksIntoBuilder(evaluationResultOfParameter, builder);

        LinkedVariables lv = computeLvForParameter(pi, inResult, concreteArgumentType, hcsParameter,
                lvsArgumentCorrectedToHcsParameter,
                independentOfObjectOrReturnValue, concreteObjectOrReturnType, formalObjectOrReturnType);
        LOGGER.debug("LV for parameter {}; {}: {}", pi, lvsArgumentCorrectedToHcsParameter, lv);
        if (lv != null) {
            builder.mergeLinkedVariablesOfExpression(lv);
        }
    }

    private static void copyAdditionalLinksIntoBuilder(EvaluationResult evaluationResultOfParameter,
                                                       EvaluationResult.Builder builder) {
        evaluationResultOfParameter.links().forEach((v, lvs1) ->
                lvs1.forEach(e -> {
                    if (!e.getValue().isStaticallyAssignedOrAssigned()) {
                        builder.merge(v, LinkedVariablesImpl.of(e.getKey(), e.getValue()));
                    } // IMPROVE this is not the best way to approach copying, what with -1- links??
                }));
    }

    private LinkedVariables computeLvForParameter(ParameterInfo pi,
                                                  boolean toReturnVariable,
                                                  ParameterizedType concreteTypeOfArgument,
                                                  HiddenContentSelector hcsParameter,
                                                  LinkedVariables lvsArgumentInFunctionOfMethod,
                                                  Independent formalParameterIndependent,
                                                  ParameterizedType concreteTypeOfObjectOrReturnVariable,
                                                  ParameterizedType formalTypeOfObjectOrReturnVariable) {
        Integer indexToDirectlyLinkedField = computeIndexToDirectlyLinkedField(pi);
        HiddenContentSelector hcsTarget;
        if (toReturnVariable) {
            // parameter -> return variable
            hcsTarget = methodInfo.analysis().getOrDefault(HCS_METHOD, NONE);
        } else {
            // parameter -> object
            hcsTarget = this.hcsObject;
            Immutable mutable = analysisHelper.typeImmutable(currentPrimaryType, pi.parameterizedType());
            if (pi.parameterizedType().isTypeParameter() && !concreteTypeOfArgument.parameters().isEmpty()) {
                if (mutable.isMutable()) {
                    return lvsArgumentInFunctionOfMethod;
                }
                return lvsArgumentInFunctionOfMethod.map(LV::changeToHc);
            }
            if (mutable.isImmutable()) return LinkedVariablesImpl.EMPTY;
        }
        return linkHelperCore.linkedVariables(concreteTypeOfArgument, pi.parameterizedType(),
                hcsParameter, lvsArgumentInFunctionOfMethod, pi.isVarArgs(),
                formalParameterIndependent,
                concreteTypeOfObjectOrReturnVariable,
                formalTypeOfObjectOrReturnVariable, hcsTarget,
                indexToDirectlyLinkedField);
    }

    private static Integer computeIndexToDirectlyLinkedField(ParameterInfo pi) {
        // this block of code may have to be moved up for linkedVariablesOfParameter to use it
        // see TestLinkConstructorInMethodCall,2 for an example
        Integer indexToDirectlyLinkedField;
        StaticValues svPi = pi.analysis().getOrDefault(StaticValuesImpl.STATIC_VALUES_PARAMETER, StaticValuesImpl.NONE);
        if (svPi.expression() instanceof VariableExpression ve
            && ve.variable() instanceof FieldReference fr
            && fr.scopeIsRecursivelyThis()) {
            indexToDirectlyLinkedField = fr.fieldInfo().indexInType();
            // IMPROVE only works for first order at the moment
            // IMPROVE only works for @Final fields in the same type, because SV_PARAM is computed afterwards for
            // non-final fields
        } else {
            indexToDirectlyLinkedField = null;
        }
        return indexToDirectlyLinkedField;
    }

    public void linksBetweenParameters(EvaluationResult.Builder builder,
                                       MethodInfo methodInfo,
                                       List<Expression> parameterExpressions,
                                       List<EvaluationResult> linkedVariables) {
        Map<ParameterInfo, LinkedVariables> crossLinks = linkHelperBetweenParameters.translateLinksToParameters(methodInfo);
        if (crossLinks.isEmpty()) return;
        crossLinks.forEach((pi, lv) ->
                linkHelperBetweenParameters.doCrossLinkOfParameter(builder, methodInfo, parameterExpressions,
                        linkedVariables, pi, lv));
    }

    private Independent independentParameterToResult(ParameterInfo pi, Independent formalParameterIndependent) {
        Integer lvToResult = formalParameterIndependent.linkToParametersReturnValue() == null ? null :
                formalParameterIndependent.linkToParametersReturnValue().get(-1);
        if (lvToResult != null) {
            // we know that there is linking
            if (lvToResult == 0) return DEPENDENT;
            if (lvToResult == 1) return INDEPENDENT_HC;
            throw new UnsupportedOperationException("Only accept 0, 1 a t m");
        }
        boolean isFactoryMethod = pi.methodInfo().isFactoryMethod();
        LV lv;
        if (isFactoryMethod) {
            LinkedVariables lvsFactory = pi.analysis().getOrDefault(LINKED_VARIABLES_PARAMETER, LinkedVariablesImpl.EMPTY);
            lv = lvsFactory.value(new ReturnVariableImpl(pi.methodInfo()));
        } else {
            LinkedVariables lvMethod = pi.methodInfo().analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                    LinkedVariablesImpl.EMPTY);
            lv = lvMethod.stream().filter(e -> e.getKey() == pi)
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (lv != null) {
            assert lv != LINK_INDEPENDENT;
            if (lv.isCommonHC()) return INDEPENDENT_HC;
            return DEPENDENT;
        }
        return INDEPENDENT;
    }

    /*
   In general, the method result 'a', in 'a = b.method(c, d)', can link to 'b', 'c' and/or 'd'.
   Independence and immutability restrict the ability to link.

   The current implementation is heavily focused on understanding links towards the fields of a type,
   i.e., in sub = list.subList(0, 10), we want to link sub to list.

   Links from the parameters to the result (from 'c' to 'a', from 'd' to 'a') have currently only
   been implemented for @Identity methods (i.e., between 'a' and 'c').

   So we implement
   1/ void methods cannot link
   2/ if the method is @Identity, the result is linked to the 1st parameter 'c'
   3/ if the method is a factory method, the result is linked to the parameter values

   all other rules now determine whether we return an empty set, or the set {'a'}.

   4/ independence is determined by the independence value of the method, and the independence value of the object 'a'
    */

    public LinkedVariables linkedVariablesMethodCallObjectToReturnType(MethodCall methodCall,
                                                                       ParameterizedType objectType,
                                                                       LinkedVariables linkedVariablesOfObjectIn,
                                                                       List<LinkedVariables> linkedVariables,
                                                                       ParameterizedType returnType) {
        // RULE 1: void methods cannot link
        if (methodInfo.noReturnValue()) return LinkedVariablesImpl.EMPTY;

        // RULE 2: @Identity links to the 1st parameter
        if (methodInfo.isIdentity()) {
            return linkedVariables.getFirst().maximum(LINK_ASSIGNED);
        }
        LinkedVariables linkedVariablesOfObject = linkedVariablesOfObjectIn.maximum(LINK_ASSIGNED);

        // RULE 3: @Fluent simply returns the same object, hence, the same linked variables
        Value.Bool fluent = methodInfo.analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, ValueImpl.BoolImpl.FALSE);
        if (fluent.isTrue()) {
            return linkedVariablesOfObject;
        }

        Value.Independent independent = methodInfo.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD,
                DEPENDENT);
        Value.Immutable immutable = methodInfo.analysis().getOrDefault(PropertyImpl.IMMUTABLE_METHOD,
                ValueImpl.ImmutableImpl.MUTABLE);
        assert !immutable.isImmutable() || independent.isIndependent();
        assert !immutable.isImmutableHC() || independent.isAtLeastIndependentHc();

        ParameterizedType methodType = methodInfo.typeInfo().asParameterizedType();
        ParameterizedType methodReturnType = methodInfo.returnType();

        HiddenContentSelector hcsTarget = methodInfo.analysis().getOrDefault(HCS_METHOD, NONE);
        if (hcsTarget.isNone()) return LinkedVariablesImpl.EMPTY;
        Value.FieldValue fieldValue = methodInfo.getSetField();
        Integer indexOfDirectlyLinkedField = fieldValue.field() != null && !fieldValue.setter()
                ? fieldValue.field().indexInType() : null;

        LinkedVariables lvs = linkHelperCore.linkedVariables(objectType,
                methodType, hcsObject, linkedVariablesOfObject,
                false,
                independent,
                returnType, methodReturnType, hcsTarget,
                indexOfDirectlyLinkedField);

        Value.FieldValue getSetField = methodInfo.getSetField();
        if (getSetField.field() != null && !getSetField.setter()) {
            return handleGetter(methodCall, returnType, getSetField, lvs, linkedVariablesOfObject);
        }
        return lvs;
    }

    private LinkedVariables handleGetter(MethodCall methodCall,
                                         ParameterizedType returnType,
                                         Value.FieldValue getSetField,
                                         LinkedVariables lvs,
                                         LinkedVariables linkedVariablesOfObject) {
        Value.Immutable immutable = analysisHelper.typeImmutable(getSetField.field().type());
        if (immutable.isImmutable()) return lvs;
        Map<Variable, LV> lvMap = new HashMap<>();
        linkedVariablesOfObject.variablesAssigned().forEach(v -> {
            // NOTE: pretty similar code in Factory.getterVariable(MethodCall)
            Variable variable;
            if (methodCall.parameterExpressions().isEmpty()) {
                variable = runtime.newFieldReference(getSetField.field(), runtime.newVariableExpression(v), returnType);
            } else {
                // indexing
                FieldReference fr = runtime.newFieldReference(getSetField.field(), runtime.newVariableExpression(v),
                        returnType.copyWithArrays(returnType.arrays() + 1));
                Expression array = runtime.newVariableExpressionBuilder()
                        .setVariable(fr).setSource(methodCall.object().source())
                        .build();
                Expression index = methodCall.parameterExpressions().getFirst();
                assert index.parameterizedType().isMathematicallyInteger();
                variable = runtime.newDependentVariable(array, index, returnType);
                Value.Immutable immutableMethodReturnType = analysisHelper.typeImmutable(returnType);
                if (!immutableMethodReturnType.isImmutable()) {
                    LV lv;
                    if (immutableMethodReturnType.isImmutableHC()) {
                        lv = createHC(new LinksImpl(IndexImpl.ALL, 0, false));
                    } else {
                        lv = createDependent(new LinksImpl(IndexImpl.ALL, 0, false));
                    }
                    LV prev = lvMap.put(fr, lv);
                    assert prev == null;
                }
            }
            LV prev = lvMap.put(variable, LINK_ASSIGNED);
            assert prev == null;
        });
        return LinkedVariablesImpl.of(lvMap).merge(lvs);
    }
}

