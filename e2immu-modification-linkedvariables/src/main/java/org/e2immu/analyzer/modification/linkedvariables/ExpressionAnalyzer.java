package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.*;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.FieldInfo;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.EMPTY;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;

public class ExpressionAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExpressionAnalyzer.class);

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
            return eval(expression, null);
        }

        LinkEvaluation eval(Expression expression, ParameterizedType forwardType) {
            if (expression instanceof VariableExpression ve) {
                Variable v = ve.variable();
                LinkedVariables lvs = linkedVariablesOfVariableExpression(ve, v, forwardType);
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
                LinkEvaluation.Builder builder = new LinkEvaluation.Builder()
                        .merge(assignment.variableTarget(), evalValue.linkedVariables())
                        .setLinkedVariables(evalValue.linkedVariables())
                        .merge(assignment.variableTarget(), evalValue.staticValues())
                        .setStaticValues(evalValue.staticValues());
                setStaticValuesForVariableHierarchy(assignment, evalValue, builder);
                return builder.build();
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
                LinkEvaluation evalValue = eval(c.expression(), c.parameterizedType());
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

        private LinkedVariables linkedVariablesOfVariableExpression(VariableExpression ve, Variable v, ParameterizedType forwardType) {
            Map<Variable, LV> map = new HashMap<>();
            map.put(v, LVImpl.LINK_ASSIGNED);
            Variable dependentVariable;
            ParameterizedType fieldType;
            int fieldIndex;
            if (v instanceof FieldReference fr && fr.scope() instanceof VariableExpression sv
                && !(sv.variable() instanceof This)) {
                dependentVariable = fr.scopeVariable();
                fieldIndex = fieldIndex(fr.fieldInfo());
                fieldType = fr.fieldInfo().type();
            } else if (v instanceof DependentVariable dv) {
                dependentVariable = dv.arrayVariable();
                fieldIndex = 0;
                fieldType = dv.arrayVariable().parameterizedType().copyWithOneFewerArrays();
            } else {
                dependentVariable = null;
                fieldIndex = -1; // irrelevant
                fieldType = null;
            }
            if (dependentVariable != null) {
                Immutable immutable = analysisHelper.typeImmutable(ve.parameterizedType());
                Immutable immutableForward = forwardType == null ? immutable : analysisHelper.typeImmutable(forwardType);
                if (!immutableForward.isImmutable()) {
                    boolean isMutable = immutableForward.isMutable();
                    Indices targetIndices;
                    Indices targetModificationArea ;
                    Indices sourceModificationArea;
                    if( immutable.isAtLeastImmutableHC()) {
                        targetModificationArea = IndicesImpl.NO_MODIFICATION_INDICES;
                        sourceModificationArea = IndicesImpl.NO_MODIFICATION_INDICES;
                    } else {
                        targetModificationArea = new IndicesImpl(fieldIndex);
                        sourceModificationArea = IndicesImpl.ALL_INDICES;
                    }
                    if (v instanceof DependentVariable) {
                        targetIndices = new IndicesImpl(0);
                    } else if (ve.parameterizedType().typeInfo() == null || ve.parameterizedType().typeInfo().isExtensible()) {
                        TypeInfo bestType = dependentVariable.parameterizedType().bestTypeInfo();
                        assert bestType != null : "The unbound type parameter does not have any fields";
                        HiddenContentTypes hct = bestType.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                        Integer i = hct.indexOf(fieldType);
                        if (i != null) {
                            targetIndices = new IndicesImpl(i);
                        } else {
                            targetIndices = null;
                        }
                    } else {
                        targetIndices = null;
                    }
                    Map<Indices, Link> linkMap;
                    if (targetIndices == null) {
                        linkMap = Map.of();
                    } else {
                        linkMap = Map.of(IndicesImpl.ALL_INDICES, new LinkImpl(targetIndices, isMutable));
                    }
                    Links links = new LinksImpl(linkMap, sourceModificationArea, targetModificationArea);
                    LV lv;
                    if (immutable.isAtLeastImmutableHC()) {
                        lv = LVImpl.createHC(links);
                    } else {
                        lv = LVImpl.createDependent(links);
                    }
                    map.put(dependentVariable, lv);

                }
            }
            return LinkedVariablesImpl.of(map);
        }

        private int fieldIndex(FieldInfo fieldInfo) {
            int count = 0;
            for (FieldInfo f : fieldInfo.owner().fields()) {
                if (f == fieldInfo) return count;
                ++count;
            }
            throw new UnsupportedOperationException();
        }

        private void setStaticValuesForVariableHierarchy(Assignment assignment, LinkEvaluation evalValue, LinkEvaluation.Builder builder) {
            // push values up in the variable hierarchy
            Variable v = assignment.variableTarget();
            Expression value = evalValue.staticValues().expression();
            if (value != null) {
                Expression indexExpression = null;
                while (true) {
                    if (v instanceof DependentVariable dv) {
                        This thisVar = runtime.newThis(currentMethod.typeInfo().asParameterizedType()); // irrelevant which type
                        Variable indexed = runtime.newDependentVariable(thisVar, value.parameterizedType(), dv.indexVariable());
                        StaticValues newSv = new StaticValuesImpl(null, null, Map.of(indexed, value));
                        builder.merge(dv.arrayVariable(), newSv);
                        v = dv.arrayVariable();
                        indexExpression = dv.indexExpression();
                    } else if (v instanceof FieldReference fr && fr.scope() instanceof VariableExpression) {
                        Variable variable;
                        if (indexExpression != null) {
                            variable = runtime.newDependentVariable(runtime.newVariableExpression(fr), indexExpression);
                        } else {
                            variable = fr;
                        }
                        StaticValues newSv = new StaticValuesImpl(null, null, Map.of(variable, value));
                        builder.merge(fr.scopeVariable(), newSv);
                        v = fr.scopeVariable();
                    } else break;
                }
            }
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
                if (variable instanceof DependentVariable dv
                    && dv.arrayExpression() instanceof VariableExpression av
                    && av.variable() instanceof FieldReference fr && fr.scopeVariable() != null) {
                    // r.variable[0]
                    VariableInfoContainer vicSv = variableDataPrevious.variableInfoContainerOrNull(fr.scopeVariable()
                            .fullyQualifiedName());
                    if (vicSv != null) {
                        VariableInfo viV = vicSv.best();
                        StaticValues svV = viV.staticValues();
                        if (svV != null) {
                            Map<Variable, Expression> valueMap = new HashMap<>(svV.values());
                            ParameterizedType targetType = fr.scope().parameterizedType();
                            Map<Variable, Expression> completed = augmentWithImplementation(targetType, svV, valueMap);
                            FieldReference newFr = runtime.newFieldReference(fr.fieldInfo());
                            DependentVariable newDv = runtime.newDependentVariable(runtime.newVariableExpression(newFr),
                                    dv.indexExpression());
                            Expression valueForField = completed.get(newDv);
                            if (valueForField != null) {
                                return valueForField;
                            }
                        }
                    }
                } else if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
                    // r.variable
                    VariableInfoContainer vicSv = variableDataPrevious.variableInfoContainerOrNull(fr.scopeVariable()
                            .fullyQualifiedName());
                    if (vicSv != null) {
                        VariableInfo viV = vicSv.best();
                        StaticValues svV = viV.staticValues();
                        if (svV != null) {
                            Map<Variable, Expression> valueMap = new HashMap<>(svV.values());
                            ParameterizedType targetType = fr.scope().parameterizedType();
                            Map<Variable, Expression> completed = augmentWithImplementation(targetType, svV, valueMap);
                            Expression valueForField = completed.get(runtime.newFieldReference(fr.fieldInfo()));
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

            Map<NamedType, ParameterizedType> map = mr.parameterizedType().initialTypeParameterMap();
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
            LinkEvaluation leObject = eval(mc.object());
            List<LinkEvaluation> leParams = mc.parameterExpressions().stream().map(this::eval).toList();
            LinkEvaluation.Builder builder = new LinkEvaluation.Builder();

            methodCallLinks(currentMethod, mc, builder, leObject, leParams);
            methodCallModified(mc, builder);
            methodCallStaticValue(mc, builder, leObject);

            return builder.build();
        }

        private void methodCallStaticValue(MethodCall mc, LinkEvaluation.Builder builder, LinkEvaluation leObject) {
            StaticValues svm = mc.methodInfo().analysis().getOrDefault(STATIC_VALUES_METHOD, NONE);

            // getter: return value becomes the field reference
            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            if (getSet.field() != null && mc.methodInfo().hasReturnValue() && !mc.methodInfo().isFluent()) {
                FieldReference fr = runtime.newFieldReference(getSet.field(), mc.object(), getSet.field().type());
                Variable variable;
                if (mc.parameterExpressions().isEmpty()) {
                    variable = fr;
                } else {
                    // indexing
                    variable = runtime.newDependentVariable(runtime.newVariableExpression(fr), mc.parameterExpressions().get(0));
                }
                VariableExpression ve = runtime.newVariableExpression(variable);
                Expression svExpression = inferStaticValues(ve);
                StaticValues svs = StaticValuesImpl.of(svExpression);
                StaticValues svsVar = StaticValuesImpl.from(variableDataPrevious, stageOfPrevious, fr);
                builder.setStaticValues(svs).merge(fr, svsVar);
                return;
            }

            // fluent setter, see TestStaticValuesAssignment,4,method and method2
            if (mc.methodInfo().hasReturnValue() && mc.methodInfo().isFluent()
                && svm.expression() instanceof VariableExpression ve && ve.variable() instanceof This) {
                StaticValues sv = makeSvFromMethodCall(mc, leObject, svm);
                builder.setStaticValues(sv);
                // we also add to the object, in case we're not picking up on the result (method2)
                if (mc.object() instanceof VariableExpression veObject && !(veObject.variable() instanceof This)) {
                    builder.merge(veObject.variable(), sv);
                }
                return;
            }

            // copy into the object, if it is a variable; serves non-fluent setters
            if (mc.object() instanceof VariableExpression ve
                && !mc.methodInfo().hasReturnValue()
                && !(ve.variable() instanceof This)) {
                StaticValues sv = makeSvFromMethodCall(mc, leObject, svm);
                builder.merge(ve.variable(), sv);
            }

            // and if we have a return value and our object is variable, copy from the object into the return value
            // adjusting for the object
            if (mc.methodInfo().hasReturnValue()
                && mc.object() instanceof VariableExpression veObject && variableDataPrevious != null) {
                VariableInfoContainer vicObject = variableDataPrevious.variableInfoContainerOrNull(veObject.variable().fullyQualifiedName());
                if (vicObject != null) {
                    VariableInfo viObject = vicObject.best(stageOfPrevious);
                    StaticValues svObject = viObject.staticValues();

                    Map<Variable, Expression> svObjectValues = checkCaseForBuilder(mc, svObject);
                    StaticValues sv = new StaticValuesImpl(svm.type(), null, svObjectValues);
                    builder.setStaticValues(sv);
                }
            }
        }

        private Map<Variable, Expression> checkCaseForBuilder(MethodCall mc, StaticValues svObject) {

            // case for builder()
            Map<Variable, Expression> existingMap = svObject == null ? Map.of() : svObject.values();

            if (!mc.methodInfo().methodBody().isEmpty()) {
                VariableData vd = VariableDataImpl.of(mc.methodInfo().methodBody().lastStatement());
                VariableInfo rv = vd.variableInfo(mc.methodInfo().fullyQualifiedName());
                StaticValues sv = rv.staticValues();
                LOGGER.debug("return value: {}", sv);
                if (sv.expression() instanceof ConstructorCall cc && cc.constructor() != null) {
                    // do a mapping of svObject.values() to the fields to which the parameters of the constructor call link
                    return staticValuesInCaseOfABuilder(cc, existingMap);
                }
            }
            return existingMap;
        }

        private Map<Variable, Expression> staticValuesInCaseOfABuilder(ConstructorCall cc, Map<Variable, Expression> existingMap) {
            Map<Variable, Expression> svObjectValues = new HashMap<>();
            for (ParameterInfo pi : cc.constructor().parameters()) {
                StaticValues svPi = pi.analysis().getOrDefault(STATIC_VALUES_PARAMETER, NONE);
                Expression arg = cc.parameterExpressions().get(pi.index());

                // builder situation
                if (arg instanceof VariableExpression veArg
                    && svPi.expression() instanceof VariableExpression ve
                    && ve.variable() instanceof FieldReference fr) {
                    // replace the value in svObject.values() to this one...

                    // array components, see TestStaticValuesRecord,6
                    if (fr.parameterizedType().arrays() > 0
                        && pi.parameterizedType().arrays() == fr.parameterizedType().arrays()) {
                        // can we add components of the array?
                        for (Map.Entry<Variable, Expression> entry : existingMap.entrySet()) {
                            if (entry.getKey() instanceof DependentVariable dv
                                && dv.arrayVariable().equals(veArg.variable())) {
                                DependentVariable newDv = runtime.newDependentVariable(ve, dv.indexExpression());
                                svObjectValues.put(newDv, entry.getValue());
                            }
                        }
                    } else {
                        // whole objects
                        Expression value = existingMap.get(veArg.variable());
                        if (value != null) {
                            svObjectValues.put(fr, value);
                        }
                    }
                }
            }
            return svObjectValues;
        }

        private StaticValues makeSvFromMethodCall(MethodCall mc, LinkEvaluation leObject, StaticValues svm) {
            Map<Variable, Expression> map = new HashMap<>();
            for (Map.Entry<Variable, Expression> entry : svm.values().entrySet()) {
                Variable variable;
                if (entry.getKey() instanceof DependentVariable dv && dv.indexVariable() instanceof ParameterInfo pi && pi.methodInfo() == mc.methodInfo()) {
                    //dv: this.objects[i]; mc arguments: 0, set -> objects[0]=set
                    Expression concreteIndex = mc.parameterExpressions().get(pi.index());
                    variable = runtime.newDependentVariable(dv.arrayExpression(), concreteIndex);
                } else {
                    variable = entry.getKey();
                }
                if (entry.getValue() instanceof VariableExpression vve
                    && vve.variable() instanceof ParameterInfo pi && pi.methodInfo() == mc.methodInfo()) {
                    map.put(variable, mc.parameterExpressions().get(pi.index()));
                }
            }
            Expression expression;
            if (mc.object() instanceof MethodCall mco && mco.methodInfo().isFluent()) {
                expression = leObject.staticValues().expression();
                map.putAll(leObject.staticValues().values());
            } else {
                expression = mc.object();
            }
            return new StaticValuesImpl(svm.type(), expression, Map.copyOf(map));
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
                    Value.VariableBooleanMap map = mc.methodInfo().analysis().getOrDefault(MODIFIED_COMPONENTS_METHOD,
                            ValueImpl.VariableBooleanMapImpl.EMPTY);
                    map.map().keySet().forEach(v -> {
                        if (v instanceof FieldReference fr) {
                            FieldReference newFr = runtime.newFieldReference(fr.fieldInfo(), mc.object(), fr.parameterizedType());
                            markModified(newFr, builder);
                        }
                    });
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
                propagateComponents(MODIFIED_FI_COMPONENTS_PARAMETER, mc, pi, (e, mapValue) -> {
                    if (e instanceof MethodReference mr) {
                        if (mapValue) {
                            propagateModification(mr, builder);
                        } else {
                            ensureNotModifying(mr, builder);
                        }
                    }
                });
                propagateComponents(MODIFIED_COMPONENTS_PARAMETER, mc, pi, (e, mapValue) -> {
                    if (e instanceof VariableExpression ve2 && mapValue) {
                        markModified(ve2.variable(), builder);
                    }
                });
            }
        }

        /*
        what if entry.getKey() == ri.objects[index]
        ri == pi, the parameter
        index is the second parameter

        we need to reach this.objects[0]
        for that reason, we add all the parameters to the translation map. See TestStaticValuesModification,4.
         */
        private void propagateComponents(Property property,
                                         MethodCall mc,
                                         ParameterInfo pi,
                                         BiConsumer<Expression, Boolean> consumer) {
            VariableBooleanMap modifiedComponents = pi.analysis().getOrNull(property,
                    ValueImpl.VariableBooleanMapImpl.class);
            if (modifiedComponents != null) {
                Expression pe = mc.parameterExpressions().get(pi.index());
                if (pe instanceof VariableExpression ve) {
                    StaticValues svParam = variableDataPrevious.variableInfo(ve.variable(), stageOfPrevious).staticValues();
                    for (Map.Entry<Variable, Boolean> entry : modifiedComponents.map().entrySet()) {
                        This thisInSv = runtime.newThis(pi.parameterizedType().typeInfo().asParameterizedType());
                        // go from r.function to this.function, which is what we have in the StaticValues.values() map
                        TranslationMap.Builder tmb = runtime.newTranslationMapBuilder().put(pi, thisInSv);
                        for (ParameterInfo pi2 : mc.methodInfo().parameters()) {
                            if (pi != pi2) {
                                tmb.put(runtime.newVariableExpression(pi2), mc.parameterExpressions().get(pi2.index()));
                            }
                        }
                        TranslationMap tm = tmb.build();
                        runtime.newTranslationMapBuilder().put(pi, thisInSv).build();
                        Variable key = tm.translateVariable(entry.getKey());
                        Map<Variable, Expression> completedMap = completeMap(svParam, variableDataPrevious, stageOfPrevious);
                        Map<Variable, Expression> augmented = augmentWithImplementation(pi.parameterizedType(), svParam,
                                completedMap);
                        Expression e = augmented.get(key);
                        consumer.accept(e, entry.getValue());
                    }
                }
            }
        }

        private Map<Variable, Expression> augmentWithImplementation(ParameterizedType targetType,
                                                                    StaticValues svParam,
                                                                    Map<Variable, Expression> completedMap) {
            Map<Variable, Expression> completedAndAugmentedWithImplementation;
            if (svParam.type() != null && !svParam.type().equals(targetType)) {
                assert targetType.isAssignableFrom(runtime, svParam.type());
                completedAndAugmentedWithImplementation = new HashMap<>(completedMap);
                completedMap.forEach((v, e) -> {
                    Variable tv = liftVariable(v);
                    if (tv != v && !completedAndAugmentedWithImplementation.containsKey(tv)) {
                        completedAndAugmentedWithImplementation.put(tv, e);
                    }
                });
            } else {
                completedAndAugmentedWithImplementation = completedMap;
            }
            return completedAndAugmentedWithImplementation;
        }

        /*
        See TestModificationFunctional,3. We want to replace ti.si.ri.i by t.s.r.i,
        where 'ti,si,ri,i' are variables/fields in the implementation types TImpl,SImpl,RImpl, and
        't.s.r.i' are synthetic fields corresponding to accessors in the interface types T, S, R.

        TODO: there could be multiple results. We'll first do the situation with one result.

        TODO also lift dependent variable's index?
         */
        private Variable liftVariable(Variable v) {
            if (v instanceof DependentVariable dv && dv.arrayExpression() instanceof VariableExpression av
                && av.variable() instanceof FieldReference fr && fr.scope() instanceof VariableExpression ve) {
                FieldInfo liftField = liftField(fr.fieldInfo());
                if (liftField != fr.fieldInfo()) {
                    // we can go up: there is (synthetic) field higher up.
                    Variable liftedScope = liftScope(ve.variable(), liftField.owner().asSimpleParameterizedType());
                    if (liftedScope != ve.variable()) {
                        // the scope can join us
                        FieldReference newFr = runtime.newFieldReference(liftField, runtime.newVariableExpression(liftedScope),
                                liftField.type());
                        return runtime.newDependentVariable(runtime.newVariableExpression(newFr),
                                dv.indexExpression());
                    }
                }
            }
            if (v instanceof FieldReference fr && fr.scope() instanceof VariableExpression ve) {
                FieldInfo liftField = liftField(fr.fieldInfo());
                if (liftField != fr.fieldInfo()) {
                    // we can go up: there is (synthetic) field higher up.
                    Variable liftedScope = liftScope(ve.variable(), liftField.owner().asSimpleParameterizedType());
                    if (liftedScope != ve.variable()) {
                        // the scope can join us
                        return runtime.newFieldReference(liftField, runtime.newVariableExpression(liftedScope),
                                liftField.type());
                    }
                }
            }
            return v;
        }

        /*
        given a field 'i' in RImpl, with accessor RImpl.i(), is there a (synthetic?) field higher up in the hierarchy?
        We go via the accessor.
        If that fails, we try to find an identically named field.
         */
        private FieldInfo liftField(FieldInfo fieldInfo) {
            MethodInfo accessor = fieldInfo.typeInfo().methodStream().filter(mi -> accessorOf(mi) == fieldInfo).findFirst().orElse(null);
            if (accessor != null && !accessor.overrides().isEmpty()) {
                MethodInfo override = accessor.overrides().stream().findFirst().orElseThrow();
                FieldInfo fieldOfOverride = accessorOf(override);
                if (fieldOfOverride != null) return fieldOfOverride;
            }
            String fieldName = fieldInfo.name();
            // see TestStaticValuesAssignment,3 for an example where this returns a field in a supertype (RI.set -> R.set)
            return fieldInfo.owner().recursiveSuperTypeStream()
                    .map(ti -> ti.getFieldByName(fieldName, false))
                    .filter(Objects::nonNull).findFirst().orElse(fieldInfo);
        }

        private static FieldInfo accessorOf(MethodInfo methodInfo) {
            Value.FieldValue fv = methodInfo.analysis().getOrNull(GET_SET_FIELD, ValueImpl.GetSetValueImpl.class);
            return fv == null ? null : fv.field();
        }

        private Variable liftScope(Variable variable, ParameterizedType requiredType) {
            if (variable instanceof FieldReference) {
                Variable lifted = liftVariable(variable);
                if (lifted.parameterizedType().equals(requiredType)) {
                    // success!
                    return lifted;
                }
            } else if (requiredType.isAssignableFrom(runtime, variable.parameterizedType())) {
                if (variable instanceof This) {
                    return runtime.newThis(requiredType.typeInfo().asParameterizedType());
                }
                throw new RuntimeException(); // ?? what to do?
            }
            return variable; // failure to lift
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
                                            if (!result.containsKey(newV)) {
                                                extra.put(newV, e.getValue());
                                            }
                                        });
                            }
                        }
                    }
                }
                if (extra.isEmpty()) break;
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
            if (mutable || isSyntheticObjectUsedInMethodComponentModification(variable)) {
                builder.addModified(variable);
                if (variable instanceof FieldReference fr && fr.scopeVariable() != null) {
                    markModified(fr.scopeVariable(), builder);
                } else if (variable instanceof DependentVariable dv && dv.arrayVariable() != null) {
                    markModified(dv.arrayVariable(), builder);
                }
            }
        }

        private static boolean isSyntheticObjectUsedInMethodComponentModification(Variable variable) {
            return variable.parameterizedType().isJavaLangObject()
                   && variable instanceof FieldReference fr
                   && fr.fieldInfo().isSynthetic();
        }

        private void methodCallLinks(MethodInfo currentMethod,
                                     MethodCall mc,
                                     LinkEvaluation.Builder builder,
                                     LinkEvaluation leObject,
                                     List<LinkEvaluation> leParams) {
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    mc.methodInfo());
            ParameterizedType objectType = mc.methodInfo().isStatic() ? null : mc.object().parameterizedType();
            ParameterizedType concreteReturnType = mc.concreteReturnType();

            List<LinkedVariables> linkedVariablesOfParameters = leParams.stream()
                    .map(LinkEvaluation::linkedVariables).toList();

            // from parameters to object
            LinkHelper.FromParameters fp = linkHelper.linksInvolvingParameters(objectType, concreteReturnType,
                    mc.parameterExpressions(), leParams);
            LinkedVariables linkedVariablesOfObjectFromParams = fp.intoObject().linkedVariablesOfExpression();
            if (mc.object() instanceof VariableExpression ve) {
                builder.merge(ve.variable(), linkedVariablesOfObjectFromParams);
            }
            builder.merge(fp.intoObject());

            // in between parameters (A)
            linkHelper.crossLink(leObject.linkedVariables(), linkedVariablesOfObjectFromParams, builder);

            // from object to return value
            LinkedVariables lvsResult1 = objectType == null ? EMPTY
                    : linkHelper.linkedVariablesMethodCallObjectToReturnType(objectType, leObject.linkedVariables(),
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
            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            if (getSet.field() != null) {
                Expression replacedObject = recursivelyReplaceAccessorByFieldReference(runtime, mc.object());
                FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, mc.concreteReturnType());
                return runtime.newVariableExpression(fr);
            }
        }
        return expression;
    }
}
