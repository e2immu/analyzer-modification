package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.translate.TranslationMap;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.e2immu.language.inspection.impl.parser.GenericsHelperImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.EMPTY;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;

public class ExpressionAnalyzer {

    private final Runtime runtime;
    private final GenericsHelper genericsHelper;
    private final AnalysisHelper analysisHelper;

    public ExpressionAnalyzer(Runtime runtime) {
        this.runtime = runtime;
        this.genericsHelper = new GenericsHelperImpl(runtime);
        this.analysisHelper = new AnalysisHelper();
    }

    public LinkEvaluation linkEvaluation(MethodInfo currentMethod,
                                         VariableData variableDataPrevious,
                                         Stage stageOfPrevious,
                                         Expression expression) {
        return new Internal(currentMethod, variableDataPrevious, stageOfPrevious).eval(expression);
    }

    private class Internal {
        final MethodInfo currentMethod;
        final VariableData variableDataPrevious;
        final Stage stageOfPrevious;

        Internal(MethodInfo currentMethod, VariableData variableDataPrevious, Stage stageOfPrevious) {
            this.currentMethod = currentMethod;
            this.variableDataPrevious = variableDataPrevious;
            this.stageOfPrevious = stageOfPrevious;
        }

        LinkEvaluation eval(Expression expression) {
            if (expression instanceof VariableExpression ve) {
                // TODO add scope fields, arrays
                Variable v = ve.variable();
                LinkedVariables lvs = LinkedVariablesImpl.of(v, LVImpl.LINK_ASSIGNED);
                // do we have a static value for 'v'? this static value can be in the current evaluation forward (NYI)
                // or in the previous statement
                Expression svExpression = inferStaticValues(ve);
                StaticValues svs = StaticValuesImpl.of(svExpression);
                StaticValues svsVar = StaticValuesImpl.from(variableDataPrevious, stageOfPrevious, v);
                return new LinkEvaluation.Builder()
                        .setStaticValues(svs.merge(svsVar))
                        .setLinkedVariables(lvs)
                        .build();
            }
            if (expression instanceof Assignment assignment) {
                LinkEvaluation evalValue = eval(assignment.value());
                return new LinkEvaluation.Builder()
                        .merge(assignment.variableTarget(), evalValue.linkedVariables())
                        .setLinkedVariables(evalValue.linkedVariables())
                        .merge(assignment.variableTarget(), evalValue.staticValues())
                        .setStaticValues(evalValue.staticValues())
                        .build();
            }
            if (expression instanceof MethodCall mc) {
                return linkEvaluationOfMethodCall(currentMethod, mc);
            }
            if (expression instanceof ConstructorCall cc) {
                if (cc.constructor() != null) {
                    return linkEvaluationOfConstructorCall(currentMethod, cc);
                }
                if (cc.anonymousClass() != null) {
                    return linkEvaluationOfAnonymousClass(currentMethod, cc);
                }
            }
            if (expression instanceof MethodReference mr) {
                return linkEvaluationOfMethodReference(currentMethod, mr);
            }
            if (expression instanceof Lambda lambda) {
                return linkEvaluationOfLambda(lambda);
            }

            // direct assignment, if there is a pattern variable. empty otherwise
            if (expression instanceof InstanceOf io) {
                if (io.patternVariable() != null) {
                    LinkEvaluation evalValue = eval(io.expression());
                    return new LinkEvaluation.Builder()
                            .merge(io.patternVariable(), evalValue.linkedVariables())
                            .setLinkedVariables(evalValue.linkedVariables())
                            .build();
                }
                return LinkEvaluation.EMPTY;
            }

            // pass-through
            if (expression instanceof Cast c) {
                LinkEvaluation evalValue = eval(c.expression());
                return new LinkEvaluation.Builder().merge(evalValue).build();
            }
            if (expression instanceof EnclosedExpression c) {
                LinkEvaluation evalValue = eval(c.expression());
                return new LinkEvaluation.Builder().merge(evalValue).build();
            }

            // trivial aggregation
            if (expression instanceof ArrayInitializer ai) {
                List<LinkEvaluation> list = ai.expressions().stream().map(this::eval).toList();
                LinkEvaluation.Builder b = new LinkEvaluation.Builder();
                for (LinkEvaluation le : list) b.merge(le);
                LinkedVariables reduced = list.stream().map(LinkEvaluation::linkedVariables)
                        .reduce(EMPTY, LinkedVariables::merge);
                return b.setLinkedVariables(reduced).build();
            }
            if (expression instanceof InlineConditional ic) {
                LinkEvaluation leCondition = eval(ic.condition());
                LinkEvaluation leIfTrue = eval(ic.ifTrue());
                LinkEvaluation leIfFalse = eval(ic.ifFalse());
                LinkEvaluation.Builder b = new LinkEvaluation.Builder().merge(leCondition).merge(leIfTrue).merge(leIfFalse);
                LinkedVariables merge = leIfTrue.linkedVariables().merge(leIfFalse.linkedVariables());
                return b.setLinkedVariables(merge).build();
            }
            if (expression instanceof ConstantExpression<?> ce) {
                StaticValues sv = StaticValuesImpl.of(ce);
                return new LinkEvaluation.Builder().setLinkedVariables(EMPTY).setStaticValues(sv).build();
            }
            return LinkEvaluation.EMPTY;
        }

        private Expression inferStaticValues(VariableExpression ve) {
            if (variableDataPrevious != null) {
                Variable variable = ve.variable();
                VariableInfoContainer vicV = variableDataPrevious.variableInfoContainerOrNull(variable.fullyQualifiedName());
                if (vicV != null) {
                    VariableInfo viV = vicV.best();
                    StaticValues svV = viV.staticValues();
                    if (svV != null && svV.expression() != null) {
                        return svV.expression();
                    }
                }
                if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
                    VariableInfoContainer vicSv = variableDataPrevious.variableInfoContainerOrNull(fr.scopeVariable().fullyQualifiedName());
                    if (vicSv != null) {
                        VariableInfo viV = vicSv.best();
                        StaticValues svV = viV.staticValues();
                        if (svV != null) {
                            Expression valueForField = svV.values().get(runtime.newFieldReference(fr.fieldInfo()));
                            if (valueForField != null) {
                                return valueForField;
                            }
                        }
                    }
                }
            }
            return ve;
        }

        private LinkEvaluation linkEvaluationOfAnonymousClass(MethodInfo currentMethod, ConstructorCall cc) {
            TypeInfo anonymousTypeInfo = cc.anonymousClass();

            MethodInfo sami = anonymousTypeImplementsFunctionalInterface(anonymousTypeInfo);
            if (sami == null) return LinkEvaluation.EMPTY;

            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod, sami);
            ParameterizedType cft = anonymousTypeInfo.interfacesImplemented().get(0);
            Value.Independent indepOfMethod = sami.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT);
            HiddenContentSelector hcsMethod = sami.analysis().getOrNull(HCS_METHOD, HiddenContentSelector.class);
            LinkEvaluation linkEvaluationObject = eval(cc.object());
            LinkedVariables lvsObject = linkEvaluationObject.linkedVariables();
            ParameterizedType concreteReturnType = sami.returnType();
            List<Value.Independent> independentOfParameters = sami.parameters().stream()
                    .map(pi -> pi.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT))
                    .toList();
            List<HiddenContentSelector> hcsParameters = sami.parameters().stream()
                    .map(pi -> pi.analysis().getOrNull(HCS_PARAMETER, HiddenContentSelector.class))
                    .toList();
            List<LinkedVariables> lvsParams = sami.parameters().stream()
                    .map(pi -> pi.analysis().getOrDefault(LINKED_VARIABLES_PARAMETER, EMPTY))
                    .toList();
            List<ParameterizedType> parameterTypes = sami.parameters().stream()
                    .map(ParameterInfo::parameterizedType)
                    .toList();
            LinkedVariables lvs = linkHelper.functional(indepOfMethod, hcsMethod, lvsObject, concreteReturnType,
                    independentOfParameters, hcsParameters, parameterTypes, lvsParams, cft);
            return new LinkEvaluation.Builder().setLinkedVariables(lvs).build();
        }

        private MethodInfo anonymousTypeImplementsFunctionalInterface(TypeInfo typeInfo) {
            if (!typeInfo.parentClass().isJavaLangObject()) return null;
            if (!typeInfo.interfacesImplemented().isEmpty()) {
                if (typeInfo.interfacesImplemented().size() > 1) return null;
                if (!typeInfo.interfacesImplemented().get(0).isFunctionalInterface()) return null;
            }
            List<MethodInfo> methods = typeInfo.methods();
            if (methods.size() != 1) return null;
            return methods.get(0);
        }

        private LinkEvaluation linkEvaluationOfLambda(Lambda lambda) {
            LinkHelper.LambdaResult lr = LinkHelper.lambdaLinking(runtime, lambda.methodInfo());
            LinkedVariables lvsBeforeRemove;
            if (lambda.methodInfo().isModifying()) {
                lvsBeforeRemove = lr.mergedLinkedToParameters();
            } else {
                lvsBeforeRemove = lr.linkedToReturnValue();
            }
            LinkedVariables lvs = lvsBeforeRemove.remove(v -> removeFromLinkedVariables(lambda.methodInfo(), v));
            return new LinkEvaluation.Builder().setLinkedVariables(lvs).build();
        }

        private boolean removeFromLinkedVariables(MethodInfo lambdaMethod, Variable v) {
            return v instanceof ParameterInfo pi && lambdaMethod.equals(pi.methodInfo())
                   || v instanceof FieldReference fr && someScopeIsParameterOf(fr, lambdaMethod);
        }

        private static boolean someScopeIsParameterOf(FieldReference fr, MethodInfo methodInfo) {
            Variable sv = fr.scopeVariable();
            if (sv instanceof ParameterInfo pi && methodInfo.equals(pi.methodInfo())) return true;
            if (sv instanceof FieldReference fr2) return someScopeIsParameterOf(fr2, methodInfo);
            return false;
        }

        private LinkEvaluation linkEvaluationOfMethodReference(MethodInfo currentMethod, MethodReference mr) {
            LinkEvaluation scopeResult = eval(mr.scope());
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    mr.methodInfo());
            Value.Independent independentOfMethod = mr.methodInfo().analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT);
            HiddenContentSelector hcsMethod = mr.methodInfo().analysis().getOrNull(HCS_METHOD, HiddenContentSelector.class);
            assert hcsMethod != null : "Have no hidden content selector computed for " + mr.methodInfo();

            Map<NamedType, ParameterizedType> map = mr.parameterizedType().initialTypeParameterMap(runtime);
            ParameterizedType typeOfReturnValue = mr.methodInfo().returnType();
            ParameterizedType concreteTypeOfReturnValue = typeOfReturnValue.applyTranslation(runtime, map);
            List<ParameterizedType> concreteParameterTypes = mr.methodInfo().parameters().stream()
                    .map(pi -> pi.parameterizedType().applyTranslation(runtime, map)).toList();
            List<Value.Independent> independentOfParameters = mr.methodInfo().parameters().stream()
                    .map(pi -> pi.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT))
                    .toList();
            List<HiddenContentSelector> hcsParameters = mr.methodInfo().parameters().stream()
                    .map(pi -> pi.analysis().getOrNull(HCS_PARAMETER, HiddenContentSelector.class))
                    .toList();

            LinkedVariables linkedVariablesOfObject = scopeResult.linkedVariables();
            LinkedVariables lvs = linkHelper.functional(independentOfMethod, hcsMethod, linkedVariablesOfObject,
                    concreteTypeOfReturnValue, independentOfParameters, hcsParameters, concreteParameterTypes,
                    List.of(linkedVariablesOfObject), concreteTypeOfReturnValue);
            return new LinkEvaluation.Builder().merge(scopeResult).setLinkedVariables(lvs).build();
        }

        private LinkEvaluation linkEvaluationOfConstructorCall(MethodInfo currentMethod, ConstructorCall cc) {
            LinkEvaluation.Builder builder = new LinkEvaluation.Builder();
            List<LinkEvaluation> linkEvaluations = cc.parameterExpressions().stream().map(this::eval).toList();
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    cc.constructor());
            LinkHelper.FromParameters from = linkHelper.linksInvolvingParameters(cc.parameterizedType(),
                    null, cc.parameterExpressions(), linkEvaluations);

            Map<Variable, Expression> map = new HashMap<>();
            for (ParameterInfo pi : cc.constructor().parameters()) {
                StaticValues svPi = pi.analysis().getOrDefault(STATIC_VALUES_PARAMETER, NONE);
                if (svPi.expression() instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
                    map.put(fr, cc.parameterExpressions().get(pi.index()));
                }
            }
            StaticValues staticValues = new StaticValuesImpl(cc.parameterizedType(), cc, Map.copyOf(map));
            return builder
                    .setStaticValues(staticValues)
                    .setLinkedVariables(from.intoObject().linkedVariablesOfExpression())
                    .build();
        }

        private LinkEvaluation linkEvaluationOfMethodCall(MethodInfo currentMethod, MethodCall mc) {
            LinkEvaluation.Builder builder = new LinkEvaluation.Builder();
            methodCallLinks(currentMethod, mc, builder);
            methodCallModified(mc, builder);
            methodCallStaticValue(mc, builder);
            return builder.build();
        }

        private void methodCallStaticValue(MethodCall mc, LinkEvaluation.Builder builder) {
            if (mc.methodInfo().hasReturnValue()) {
                // test for fluent setter, see TestStaticValuesAssignment,method
                StaticValues svm = mc.methodInfo().analysis().getOrDefault(STATIC_VALUES_METHOD, NONE);
                if (svm.expression() instanceof VariableExpression ve && ve.variable() instanceof This) {
                    Map<Variable, Expression> map = new HashMap<>();
                    for (Map.Entry<Variable, Expression> entry : svm.values().entrySet()) {
                        if (entry.getValue() instanceof VariableExpression vve
                            && vve.variable() instanceof ParameterInfo pi && pi.methodInfo() == mc.methodInfo()) {
                            map.put(entry.getKey(), mc.parameterExpressions().get(pi.index()));
                        }
                    }
                    StaticValues sv = new StaticValuesImpl(svm.type(), mc.object(), Map.copyOf(map));
                    builder.setStaticValues(sv);
                    return;
                }

                Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.FieldValueImpl.EMPTY);
                if (getSet.field() != null) {
                    FieldReference fr = runtime.newFieldReference(getSet.field(), mc.object(), mc.concreteReturnType());
                    VariableExpression ve = runtime.newVariableExpression(fr);
                    Expression svExpression = inferStaticValues(ve);
                    StaticValues svs = StaticValuesImpl.of(svExpression);
                    StaticValues svsVar = StaticValuesImpl.from(variableDataPrevious, stageOfPrevious, fr);
                    builder.setStaticValues(svs).merge(fr, svsVar);
                    return;
                }

                // builder, build() method
                if (mc.object() instanceof VariableExpression veObject && variableDataPrevious != null) {
                    VariableInfoContainer vicObject = variableDataPrevious.variableInfoContainerOrNull(veObject.variable().fullyQualifiedName());
                    if (vicObject != null) {
                        VariableInfo viObject = vicObject.best(stageOfPrevious);
                        StaticValues svObject = viObject.staticValues();
                        if (svObject != null && svObject.expression() != null && svm.expression() != null) {
                            StaticValues sv = new StaticValuesImpl(svm.type(), null, svObject.values());
                            builder.setStaticValues(sv);
                        }
                    }
                }
            }
        }

        private void methodCallModified(MethodCall mc, LinkEvaluation.Builder builder) {
            Expression object = recursivelyReplaceAccessorByFieldReference(runtime, mc.object());
            if (object instanceof VariableExpression ve) {
                boolean modifying = mc.methodInfo().analysis().getOrDefault(MODIFIED_METHOD, FALSE).isTrue();
                if (ve.variable().parameterizedType().isFunctionalInterface()
                    && ve.variable() instanceof FieldReference fr
                    && !fr.isStatic() && !(fr.scopeVariable() instanceof This)) {
                    builder.addModifiedFunctionalInterfaceComponent(fr, modifying);
                } else if (modifying) {
                    markModified(ve.variable(), builder);
                }
            }
            for (ParameterInfo pi : mc.methodInfo().parameters()) {
                if (pi.analysis().getOrDefault(MODIFIED_PARAMETER, FALSE).isTrue()) {
                    // TODO deal with varargs
                    Expression pe = mc.parameterExpressions().get(pi.index());
                    if (pe instanceof VariableExpression ve) {
                        markModified(ve.variable(), builder);
                    } else if (pe instanceof MethodReference mr) {
                        propagateModification(mr, builder);
                    }
                }
                Value.VariableBooleanMap mfiComponents = pi.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER, ValueImpl.VariableBooleanMapImpl.class);
                if (mfiComponents != null) {
                    Expression pe = mc.parameterExpressions().get(pi.index());
                    if (pe instanceof VariableExpression ve) {
                        StaticValues svParam = variableDataPrevious.variableInfo(ve.variable(), stageOfPrevious).staticValues();
                        for (Map.Entry<Variable, Boolean> entry : mfiComponents.map().entrySet()) {
                            This thisInSv = runtime.newThis(pi.parameterizedType().typeInfo());
                            // go from r.function to this.function, which is what we have in the StaticValues.values() map
                            TranslationMap tm = runtime.newTranslationMapBuilder().put(pi, thisInSv).build();
                            Variable key = tm.translateVariable(entry.getKey());
                            Map<Variable, Expression> completedMap = completeMap(svParam, variableDataPrevious, stageOfPrevious);
                            Expression e = completedMap.get(key);
                            if (e instanceof MethodReference mr) {
                                if (entry.getValue()) {
                                    propagateModification(mr, builder);
                                } else {
                                    ensureNotModifying(mr, builder);
                                }
                            }
                        }
                    }
                }
            }
        }

        /*
        this.r=r in the svParam.values() map.
        the static values of r are: this.function=someMethodReference.
        We want to add: this.r.function=someMethodReference.
         */

        private Map<Variable, Expression> completeMap(StaticValues svParam,
                                                      VariableData variableDataPrevious,
                                                      Stage stageOfPrevious) {
            Map<Variable, Expression> result = new HashMap<>(svParam.values());
            while (true) {
                Map<Variable, Expression> extra = new HashMap<>();
                for (Map.Entry<Variable, Expression> entry : result.entrySet()) {
                    if (entry.getValue() instanceof VariableExpression ve && !result.containsKey(ve.variable())) {
                        VariableInfoContainer vic = variableDataPrevious.variableInfoContainerOrNull(ve.variable().fullyQualifiedName());
                        if (vic != null) {
                            VariableInfo vi = vic.best(stageOfPrevious);
                            if (vi.staticValues() != null) {
                                vi.staticValues().values()
                                        .entrySet().stream().filter(e -> e.getKey() instanceof FieldReference)
                                        .forEach(e -> {
                                            FieldReference fr = (FieldReference) e.getKey();
                                            Variable newV = runtime.newFieldReference(fr.fieldInfo(),
                                                    runtime.newVariableExpression(entry.getKey()), fr.parameterizedType());
                                            if(!result.containsKey(newV)) {
                                                extra.put(newV, e.getValue());
                                            }
                                        });
                            }
                        }
                    }
                }
                if(extra.isEmpty()) break;
                result.putAll(extra);
            }
            return result;
        }

        private void ensureNotModifying(MethodReference mr, LinkEvaluation.Builder builder) {
            // TODO
        }

        private void propagateModification(MethodReference mr, LinkEvaluation.Builder builder) {
            if (mr.scope() instanceof VariableExpression ve) {
                markModified(ve.variable(), builder);
            }
        }

        // NOTE: there is a semi-duplicate in MethodAnalyzer (modification-prepwork)
        private void markModified(Variable variable, LinkEvaluation.Builder builder) {
            TypeInfo typeInfo = variable.parameterizedType().typeInfo();
            boolean mutable = typeInfo != null && typeInfo.analysis()
                    .getOrDefault(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isMutable();
            if (mutable) {
                builder.addModified(variable);
                if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
                    markModified(fr.scopeVariable(), builder);
                } else if (variable instanceof DependentVariable dv && dv.arrayVariable() != null) {
                    markModified(dv.arrayVariable(), builder);
                }
            }
        }

        private void methodCallLinks(MethodInfo currentMethod, MethodCall mc, LinkEvaluation.Builder builder) {
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    mc.methodInfo());
            ParameterizedType objectType = mc.methodInfo().isStatic() ? null : mc.object().parameterizedType();
            ParameterizedType concreteReturnType = mc.concreteReturnType();

            List<LinkEvaluation> linkEvaluations = mc.parameterExpressions().stream().map(this::eval).toList();
            List<LinkedVariables> linkedVariablesOfParameters = linkEvaluations.stream()
                    .map(LinkEvaluation::linkedVariables).toList();
            LinkEvaluation objectResult = mc.methodInfo().isStatic()
                    ? LinkEvaluation.EMPTY : eval(mc.object());

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
            LinkedVariables lvsResult1 = objectType == null ? EMPTY
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
            builder.setLinkedVariables(lvsResult);
        }
    }

    public static Expression recursivelyReplaceAccessorByFieldReference(Runtime runtime, Expression expression) {
        if (expression instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
            Expression replacedScope = recursivelyReplaceAccessorByFieldReference(runtime, fr.scope());
            return replacedScope == fr.scope() ? expression
                    : runtime.newVariableExpression(runtime.newFieldReference(fr.fieldInfo(), replacedScope,
                    fr.parameterizedType()));
        }
        if (expression instanceof MethodCall mc) {
            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.FieldValueImpl.EMPTY);
            if (getSet.field() != null) {
                Expression replacedObject = recursivelyReplaceAccessorByFieldReference(runtime, mc.object());
                FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, mc.concreteReturnType());
                return runtime.newVariableExpression(fr);
            }
        }
        return expression;
    }
}
