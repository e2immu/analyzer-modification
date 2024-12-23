package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.*;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
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

class ExpressionAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExpressionAnalyzer.class);

    private final Runtime runtime;
    private final GenericsHelper genericsHelper;
    private final AnalysisHelper analysisHelper;
    private final LinkHelperFunctional linkHelperFunctional;

    public ExpressionAnalyzer(Runtime runtime) {
        this.runtime = runtime;
        this.genericsHelper = new GenericsHelperImpl(runtime);
        this.analysisHelper = new AnalysisHelper();
        this.linkHelperFunctional = new LinkHelperFunctional(runtime, analysisHelper);
    }

    public EvaluationResult linkEvaluation(MethodInfo currentMethod,
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

        EvaluationResult eval(Expression expression) {
            return eval(expression, null);
        }

        EvaluationResult eval(Expression expression, ParameterizedType forwardType) {
            if (expression instanceof VariableExpression ve) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                Variable v = ve.variable();
                LinkedVariables lvs = linkedVariablesOfVariableExpression(ve, v, forwardType, builder);
                // do we have a static value for 'v'? this static value can be in the current evaluation forward (NYI)
                // or in the previous statement
                Expression svExpression = inferStaticValues(ve);
                StaticValues svs = StaticValuesImpl.of(svExpression);
                StaticValues svsVar = StaticValuesImpl.from(variableDataPrevious, stageOfPrevious, v);
                recursivelyCollectLinksToScopeVariables(v, builder);
                return builder
                        .setStaticValues(svs.merge(svsVar))
                        .setLinkedVariables(lvs)
                        .build();
            }
            if (expression instanceof Assignment assignment) {
                EvaluationResult evalValue = eval(assignment.value());
                EvaluationResult evalTarget = eval(assignment.target());

                EvaluationResult.Builder builder = new EvaluationResult.Builder()
                        .merge(evalValue)
                        .merge(evalTarget)
                        .merge(assignment.variableTarget(), evalValue.linkedVariables())
                        .merge(assignment.variableTarget(), evalTarget.linkedVariables())
                        .setLinkedVariables(evalValue.linkedVariables())
                        .merge(assignment.variableTarget(), evalValue.staticValues())
                        .setStaticValues(evalValue.staticValues());
                setStaticValuesForVariableHierarchy(assignment, evalValue, builder);
                if (assignment.variableTarget() instanceof FieldReference fr && fr.scopeVariable() != null) {
                    markModified(fr.scopeVariable(), builder);
                } else if (assignment.variableTarget() instanceof DependentVariable dv && dv.arrayVariable() != null) {
                    markModified(dv.arrayVariable(), builder);
                }
                return builder.build();
            }
            if (expression instanceof MethodCall mc) {
                return linkEvaluationOfMethodCall(currentMethod, mc, forwardType);
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
                    EvaluationResult evalValue = eval(io.expression());
                    return new EvaluationResult.Builder()
                            .merge(evalValue)
                            .merge(io.patternVariable(), evalValue.linkedVariables())
                            .setLinkedVariables(evalValue.linkedVariables())
                            .build();
                }
                return EvaluationResult.EMPTY;
            }

            // pass-through, except when narrowing the cast on primitives >> result can be completely unrelated
            if (expression instanceof Cast c) {
                if (narrowingCast(c.expression().parameterizedType(), c.parameterizedType())) {
                    return EvaluationResult.EMPTY;
                }
                return eval(c.expression(), c.parameterizedType());
            }
            if (expression instanceof EnclosedExpression c) {
                return eval(c.expression());
            }

            // trivial aggregation
            if (expression instanceof ArrayInitializer ai) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                List<EvaluationResult> evaluationResults = ai.expressions().stream().map(this::eval).toList();
                evaluationResults.forEach(builder::merge);

                LinkedVariables reduced = evaluationResults.stream().map(EvaluationResult::linkedVariables)
                        .reduce(EMPTY, LinkedVariables::merge);
                return builder.setLinkedVariables(reduced).setStaticValues(NONE).build();
            }
            if (expression instanceof InlineConditional ic) {
                EvaluationResult leCondition = eval(ic.condition());
                EvaluationResult leIfTrue = eval(ic.ifTrue());
                EvaluationResult leIfFalse = eval(ic.ifFalse());
                EvaluationResult.Builder b = new EvaluationResult.Builder().merge(leCondition).merge(leIfTrue).merge(leIfFalse);
                LinkedVariables merge = leIfTrue.linkedVariables().merge(leIfFalse.linkedVariables());
                return b.setLinkedVariables(merge).build();
            }
            if (expression instanceof ConstantExpression<?> ce) {
                StaticValues sv = StaticValuesImpl.of(ce);
                return new EvaluationResult.Builder().setLinkedVariables(EMPTY).setStaticValues(sv).build();
            }
            if (expression instanceof BinaryOperator bo) {
                EvaluationResult lhs = eval(bo.lhs());
                EvaluationResult rhs = eval(bo.rhs());
                return new EvaluationResult.Builder().merge(lhs).merge(rhs)
                        .setStaticValues(NONE).setLinkedVariables(EMPTY).build();
            }
            if (expression instanceof UnaryOperator uo) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                builder.merge(eval(uo.expression())).setStaticValues(NONE).setLinkedVariables(EMPTY);
                return builder.build();
            }
            if (expression instanceof ArrayLength al) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                builder.merge(eval(al.scope())).setStaticValues(NONE).setLinkedVariables(EMPTY);
                return builder.build();
            }
            if (expression instanceof And and) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                and.expressions().forEach(e -> builder.merge(eval(e)));
                return builder.setStaticValues(NONE).setLinkedVariables(EMPTY).build();
            }
            if (expression instanceof Or or) {
                EvaluationResult.Builder builder = new EvaluationResult.Builder();
                or.expressions().forEach(e -> builder.merge(eval(e)));
                return builder.setStaticValues(NONE).setLinkedVariables(EMPTY).build();
            }
            if (expression == null
                || expression instanceof EmptyExpression
                || expression instanceof TypeExpression) {
                return EvaluationResult.EMPTY;
            }
            throw new UnsupportedOperationException("expression " + expression.getClass());
        }

        private boolean narrowingCast(ParameterizedType from, ParameterizedType to) {
            return from.isPrimitiveExcludingVoid()
                   && to.isPrimitiveExcludingVoid()
                   && !from.equals(to)
                   && runtime.widestType(from, to).equals(from);
        }

        private void recursivelyCollectLinksToScopeVariables(Variable v, EvaluationResult.Builder builder) {
            if (v instanceof FieldReference fr && fr.scope() instanceof VariableExpression ve && !(ve.variable() instanceof This)) {
                Value.Immutable immutable = analysisHelper.typeImmutable(v.parameterizedType());
                if (immutable.isImmutable()) return; // no point linking
                // we create a link from 'v' into its scope 've.variable()'
                Indices modificationArea = new IndicesImpl(fr.fieldInfo().indexInType());
                HiddenContentTypes hct = ve.variable().parameterizedType().bestTypeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                Integer index = hct.indexOf(fr.fieldInfo().type());
                Map<Indices, Link> map;
                if (index == null) {
                    map = Map.of();
                    assert !immutable.isAtLeastImmutableHC();
                } else {
                    Link link = new LinkImpl(new IndicesImpl(index), immutable.isMutable());
                    map = Map.of(IndicesImpl.ALL_INDICES, link);
                }
                Links links = new LinksImpl(map, IndicesImpl.ALL_INDICES, modificationArea);
                LV lv = immutable.isMutable() ? LVImpl.createDependent(links) : LVImpl.createHC(links);
                builder.merge(v, LinkedVariablesImpl.of(ve.variable(), lv));
                recursivelyCollectLinksToScopeVariables(ve.variable(), builder);
            }
        }

        private LinkedVariables linkedVariablesOfVariableExpression(VariableExpression ve, Variable v,
                                                                    ParameterizedType forwardType,
                                                                    EvaluationResult.Builder builder) {
            Map<Variable, LV> map = new HashMap<>();
            map.put(v, LVImpl.LINK_ASSIGNED);
            Variable dependentVariable;
            ParameterizedType fieldType;
            int fieldIndex;
            if (v instanceof FieldReference fr) {
                EvaluationResult scope = eval(fr.scope());
                if (fr.scope() instanceof VariableExpression sv
                    && !(sv.variable() instanceof This)) {
                    dependentVariable = fr.scopeVariable();
                    builder.merge(dependentVariable, scope.linkedVariables());
                    fieldIndex = fr.fieldInfo().indexInType();
                    fieldType = fr.fieldInfo().type();
                } else {
                    dependentVariable = null;
                    fieldIndex = -1; // irrelevant
                    fieldType = null;
                }
            } else if (v instanceof DependentVariable dv) {
                EvaluationResult array = eval(dv.arrayExpression());
                EvaluationResult index = eval(dv.indexExpression());
                builder.merge(array).merge(index);
                dependentVariable = dv.arrayVariable();
                if (dependentVariable != null) {
                    builder.merge(dv.arrayVariable(), array.linkedVariables());
                }
                if (dv.indexVariable() != null) {
                    builder.merge(dv.indexVariable(), index.linkedVariables());
                }
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
                    Indices targetModificationArea;
                    Indices sourceModificationArea;
                    if (isMutable) {
                        targetModificationArea = new IndicesImpl(fieldIndex);
                        sourceModificationArea = IndicesImpl.ALL_INDICES;
                    } else {
                        targetModificationArea = IndicesImpl.NO_MODIFICATION_INDICES;
                        sourceModificationArea = IndicesImpl.NO_MODIFICATION_INDICES;
                    }
                    if (v instanceof DependentVariable) {
                        targetIndices = new IndicesImpl(0);
                    } else {
                        TypeInfo bestType = dependentVariable.parameterizedType().bestTypeInfo();
                        assert bestType != null : "The unbound type parameter does not have any fields";
                        HiddenContentTypes hct = bestType.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                        Integer i = hct.indexOf(fieldType);
                        if (i != null) {
                            targetIndices = new IndicesImpl(i);
                        } else {
                            targetIndices = null;
                        }
                    }
                    Map<Indices, Link> linkMap;
                    if (targetIndices == null) {
                        linkMap = Map.of();
                        assert isMutable;
                    } else {
                        linkMap = Map.of(IndicesImpl.ALL_INDICES, new LinkImpl(targetIndices, isMutable));
                    }
                    Links links = new LinksImpl(linkMap, sourceModificationArea, targetModificationArea);
                    LV lv;
                    if (isMutable) {
                        lv = LVImpl.createDependent(links);
                    } else {
                        lv = LVImpl.createHC(links);
                    }
                    map.put(dependentVariable, lv);

                }
            }
            // adding existing linked variables is in general not necessary.
            // we do add them in case of functional interfaces, see TestLinkFunctional,5(m3)
            // because method LinkHelperFunctional expects them
            if (v.parameterizedType().isFunctionalInterface() &&
                variableDataPrevious != null && variableDataPrevious.isKnown(v.fullyQualifiedName())) {
                VariableInfo viPrev = variableDataPrevious.variableInfo(v, stageOfPrevious);
                viPrev.linkedVariables().stream()
                        .filter(e -> !(e.getKey() instanceof This))
                        .forEach(e -> map.put(e.getKey(), e.getValue()));
            }
            return LinkedVariablesImpl.of(map);
        }

        private void setStaticValuesForVariableHierarchy(Assignment assignment, EvaluationResult evalValue, EvaluationResult.Builder builder) {
            // push values up in the variable hierarchy
            Variable v = assignment.variableTarget();
            Expression value = evalValue.staticValues().expression();
            if (value != null) {
                Expression indexExpression = null;
                while (true) {
                    if (v instanceof DependentVariable dv) {
                        This thisVar = runtime.newThis(currentMethod.typeInfo().asParameterizedType()); // irrelevant which type
                        Variable base = dv.arrayVariableBase();
                        TranslationMap tm = runtime.newTranslationMapBuilder().put(base, thisVar).build();
                        Variable indexed = ((VariableExpression) assignment.target().translate(tm)).variable();
                        StaticValues newSv = new StaticValuesImpl(null, null, false, Map.of(indexed, value));
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
                        StaticValues newSv = new StaticValuesImpl(null, null, false, Map.of(variable, value));
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

        private EvaluationResult linkEvaluationOfAnonymousClass(MethodInfo currentMethod, ConstructorCall cc) {
            TypeInfo anonymousTypeInfo = cc.anonymousClass();

            MethodInfo sami = anonymousTypeImplementsFunctionalInterface(anonymousTypeInfo);
            if (sami == null) return EvaluationResult.EMPTY;

            ParameterizedType cft = anonymousTypeInfo.interfacesImplemented().get(0);
            Value.Independent indepOfMethod = sami.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT);
            HiddenContentSelector hcsMethod = sami.analysis().getOrNull(HCS_METHOD, HiddenContentSelector.class);
            EvaluationResult evaluationResultObject = eval(cc.object());
            LinkedVariables lvsObject = evaluationResultObject.linkedVariables();
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
            LinkedVariables lvs = linkHelperFunctional.functional(currentMethod.primaryType(), indepOfMethod, hcsMethod,
                    lvsObject, concreteReturnType, independentOfParameters, hcsParameters, parameterTypes, lvsParams, cft);
            return new EvaluationResult.Builder().setLinkedVariables(lvs).build();
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

        private EvaluationResult linkEvaluationOfLambda(Lambda lambda) {
            LinkHelperFunctional.LambdaResult lr = LinkHelperFunctional.lambdaLinking(runtime, lambda.methodInfo());
            LinkedVariables lvsBeforeRemove;
            if (lambda.methodInfo().isModifying()) {
                lvsBeforeRemove = lr.mergedLinkedToParameters();
            } else {
                lvsBeforeRemove = lr.linkedToReturnValue();
            }
            LinkedVariables lvs = lvsBeforeRemove == null ? EMPTY
                    : lvsBeforeRemove.remove(v -> removeFromLinkedVariables(lambda.methodInfo(), v));
            return new EvaluationResult.Builder().setLinkedVariables(lvs).build();
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

        private EvaluationResult linkEvaluationOfMethodReference(MethodInfo currentMethod, MethodReference mr) {
            EvaluationResult scopeResult = eval(mr.scope());
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
            LinkedVariables lvs = linkHelperFunctional.functional(currentMethod.primaryType(),
                    independentOfMethod, hcsMethod, linkedVariablesOfObject,
                    concreteTypeOfReturnValue, independentOfParameters, hcsParameters, concreteParameterTypes,
                    List.of(linkedVariablesOfObject), concreteTypeOfReturnValue);
            return new EvaluationResult.Builder().merge(scopeResult)
                    .setLinkedVariables(lvs)
                    .setStaticValues(StaticValuesImpl.of(mr))
                    .build();
        }

        private EvaluationResult linkEvaluationOfConstructorCall(MethodInfo currentMethod, ConstructorCall cc) {
            EvaluationResult.Builder builder = new EvaluationResult.Builder();
            List<EvaluationResult> evaluationResults = cc.parameterExpressions().stream().map(this::eval).toList();
            evaluationResults.forEach(builder::merge);

            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    cc.constructor());
            LinkHelper.FromParameters from = linkHelper.linksInvolvingParameters(cc.parameterizedType(),
                    null, cc.parameterExpressions(), evaluationResults);
            LOGGER.debug("links involving parameters: {}", from);

            Map<Variable, Expression> map = new HashMap<>();
            for (ParameterInfo pi : cc.constructor().parameters()) {
                StaticValues svPi = pi.analysis().getOrDefault(STATIC_VALUES_PARAMETER, NONE);
                if (svPi.expression() instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
                    map.put(fr, cc.parameterExpressions().get(pi.index()));
                }
            }
            StaticValues staticValues = new StaticValuesImpl(cc.parameterizedType(), cc, false, Map.copyOf(map));
            return builder
                    .setStaticValues(staticValues)
                    .setLinkedVariables(from.intoObject().linkedVariablesOfExpression())
                    .build();
        }

        private EvaluationResult linkEvaluationOfMethodCall(MethodInfo currentMethod, MethodCall mc, ParameterizedType forwardType) {
            EvaluationResult.Builder builder = new EvaluationResult.Builder();
            Expression object = recursivelyReplaceAccessorByFieldReference(runtime, mc.object());

            EvaluationResult leObject = eval(object);
            builder.merge(leObject);

            List<EvaluationResult> leParams = mc.parameterExpressions().stream().map(this::eval).toList();
            leParams.forEach(builder::merge);

            methodCallLinks(currentMethod, mc.withObject(object), builder, leObject, leParams, forwardType);
            methodCallModified(mc, object, builder);
            methodCallStaticValue(mc, builder, leObject, leParams);

            return builder.build();
        }

        private void methodCallStaticValue(MethodCall mc, EvaluationResult.Builder builder, EvaluationResult leObject,
                                           List<EvaluationResult> leParams) {
            StaticValues svm = mc.methodInfo().analysis().getOrDefault(STATIC_VALUES_METHOD, NONE);

            // identity method
            if (mc.methodInfo().isIdentity()) {
                builder.setStaticValues(leParams.get(0).staticValues());
                return;
            }

            // getter: return value becomes the field reference
            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            if (getSet.field() != null && mc.methodInfo().hasReturnValue() && !mc.methodInfo().isFluent()) {
                Variable variable = runtime.getterVariable(mc);
                VariableExpression ve = runtime.newVariableExpression(variable);
                Expression svExpression = inferStaticValues(ve);
                StaticValues svs = StaticValuesImpl.of(svExpression);
                FieldReference fr = variable.fieldReferenceScope();
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
            if (mc.methodInfo().hasReturnValue()) {
                StaticValues svObject = leObject.staticValues();
                Map<Variable, Expression> svObjectValues = checkCaseForBuilder(mc, svObject);
                StaticValues sv = new StaticValuesImpl(svm.type(), null, false, svObjectValues);
                builder.setStaticValues(sv);
            }
        }

        private Map<Variable, Expression> checkCaseForBuilder(MethodCall mc, StaticValues svObject) {

            // case for builder()
            Map<Variable, Expression> existingMap = svObject == null ? Map.of() : svObject.values();

            StaticValues sv = mc.methodInfo().analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
            LOGGER.debug("return value: {}", sv);
            if (sv != null && sv.expression() instanceof ConstructorCall cc && cc.constructor() != null) {
                // do a mapping of svObject.values() to the fields to which the parameters of the constructor call link
                return staticValuesInCaseOfABuilder(cc, existingMap);
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

        private StaticValues makeSvFromMethodCall(MethodCall mc, EvaluationResult leObject, StaticValues svm) {
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
                expression = null;
            }
            return new StaticValuesImpl(svm.type(), expression, false, Map.copyOf(map));
        }

        private interface PropagateData {
            void accept(Expression mapKey, boolean mapValue, Map<Variable, Expression> svMap);
        }

        private void methodCallModified(MethodCall mc, Expression object, EvaluationResult.Builder builder) {
            if (object instanceof VariableExpression ve) {
                boolean modifying = mc.methodInfo().analysis().getOrDefault(MODIFIED_METHOD, FALSE).isTrue();
                if (ve.variable().parameterizedType().isFunctionalInterface()
                    && ve.variable() instanceof FieldReference fr
                    && !fr.isStatic() && !(fr.scopeVariable() instanceof This)) {
                    builder.addModifiedFunctionalInterfaceComponent(fr, modifying);
                } else if (modifying) {
                    markModified(ve.variable(), builder);
                    propagateMethodComponents(mc, builder);
                }
            }
            for (ParameterInfo pi : mc.methodInfo().parameters()) {
                if (pi.analysis().getOrDefault(MODIFIED_PARAMETER, FALSE).isTrue()) {
                    if (pi.isVarArgs()) {
                        for (int i = mc.methodInfo().parameters().size() - 1; i < mc.parameterExpressions().size(); i++) {
                            Expression pe = mc.parameterExpressions().get(i);
                            handleModifiedParameter(builder, pe);
                        }
                    } else {
                        Expression pe = mc.parameterExpressions().get(pi.index());
                        handleModifiedParameter(builder, pe);
                    }
                }
                /*
                When a method has been passed on, and that method turns out to be modifying, we make the method
                reference scope modified as well. If that scope was 'this', the current method will become modifying too.
                On top of that, if the parameters of this method have modified components, we check if we have
                a value for these components, so that we can propagate their modification too.
                 */
                propagateComponents(MODIFIED_FI_COMPONENTS_PARAMETER, mc, pi, (e, mapValue, map) -> {
                    if (e instanceof MethodReference mr) {
                        if (mapValue) {
                            propagateModificationOfObject(mr, builder);
                            for (ParameterInfo mrPi : mr.methodInfo().parameters()) {
                                propagateModificationOfParameter(builder, pi, map, mrPi);
                            }
                        } else {
                            ensureNotModifying(mr, builder);
                        }
                    }
                });
                propagateComponents(MODIFIED_COMPONENTS_PARAMETER, mc, pi, (e, mapValue, map) -> {
                    if (e instanceof VariableExpression ve2 && mapValue) {
                        markModified(ve2.variable(), builder);
                    }
                });
            }
        }

        private void propagateModificationOfParameter(EvaluationResult.Builder builder,
                                                      ParameterInfo pi,
                                                      Map<Variable, Expression> map,
                                                      ParameterInfo mrPi) {
            VariableBooleanMap modComp = mrPi.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.EMPTY);
            if (!modComp.isEmpty()) {
                TranslationMap tm = runtime.newTranslationMapBuilder()
                        .put(mrPi, runtime.newThis(pi.parameterizedType()))
                        .build();
                for (Map.Entry<Variable, Boolean> entry : modComp.map().entrySet()) {
                    if (entry.getValue()) {
                        // modified component
                        Expression translatedVe = runtime.newVariableExpression(entry.getKey()).translate(tm);
                        Variable translated = ((VariableExpression) translatedVe).variable();
                        Expression value = map.get(translated);
                        if (value instanceof VariableExpression ve) {
                            markModified(ve.variable(), builder);
                        }
                        LOGGER.debug("Have translated variable {}", translated);
                    }
                }
            }
        }

        private void handleModifiedParameter(EvaluationResult.Builder builder, Expression pe) {
            if (pe instanceof VariableExpression ve) {
                markModified(ve.variable(), builder);
            } else if (pe instanceof MethodReference mr) {
                propagateModificationOfObject(mr, builder);
            }
        }

        private void propagateMethodComponents(MethodCall mc, EvaluationResult.Builder builder) {
            VariableBooleanMap modifiedComponents = mc.methodInfo().analysis().getOrNull(MODIFIED_COMPONENTS_METHOD,
                    ValueImpl.VariableBooleanMapImpl.class);
            if (modifiedComponents != null
                && mc.object() instanceof VariableExpression ve
                // we must check for 'this', to eliminate simple assignments
                && !(ve.variable() instanceof This)) {
                for (Map.Entry<Variable, Boolean> entry : modifiedComponents.map().entrySet()) {
                    This thisInSv = runtime.newThis(mc.object().parameterizedType().typeInfo().asParameterizedType());
                    TranslationMap tm = runtime.newTranslationMapBuilder().put(thisInSv, ve.variable()).build();
                    Expression translatedVe = runtime.newVariableExpression(entry.getKey()).translate(tm);
                    Variable v = ((VariableExpression) translatedVe).variable();
                    markModified(v, builder);
                }
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
                                         PropagateData consumer) {
            VariableBooleanMap modifiedComponents = pi.analysis().getOrNull(property,
                    ValueImpl.VariableBooleanMapImpl.class);
            if (modifiedComponents != null) {
                Expression pe = mc.parameterExpressions().get(pi.index());
                if (pe instanceof VariableExpression ve) {
                    StaticValues svParam = variableDataPrevious.variableInfo(ve.variable(), stageOfPrevious).staticValues();
                    for (Map.Entry<Variable, Boolean> entry : modifiedComponents.map().entrySet()) {
                        This thisInSv = runtime.newThis(pi.parameterizedType().typeInfo().asParameterizedType());
                        // go from r.function to this.function, which is what we have in the StaticValues.values() map
                        TranslationMap.Builder tmb = runtime.newTranslationMapBuilder()
                                .put(ve.variable(), thisInSv) // see TestStaticValuesOfLoopData.testSwap2
                                .put(pi, thisInSv); // see many others
                        for (ParameterInfo pi2 : mc.methodInfo().parameters()) {
                            if (pi != pi2) {
                                tmb.put(runtime.newVariableExpression(pi2), mc.parameterExpressions().get(pi2.index()));
                            }
                        }
                        TranslationMap tm = tmb.build();
                        Expression translatedVe = runtime.newVariableExpression(entry.getKey()).translate(tm);
                        Variable key = ((VariableExpression) translatedVe).variable();
                        Map<Variable, Expression> completedMap = completeMap(svParam, variableDataPrevious, stageOfPrevious);
                        Map<Variable, Expression> augmented = augmentWithImplementation(pi.parameterizedType(), svParam,
                                completedMap);
                        Expression e = augmented.get(key);
                        consumer.accept(e, entry.getValue(), augmented);
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
                if (fieldOfOverride != null) {
                    return fieldOfOverride;
                }
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

        private void ensureNotModifying(MethodReference mr, EvaluationResult.Builder builder) {
            // TODO
        }

        private void propagateModificationOfObject(MethodReference mr, EvaluationResult.Builder builder) {
            if (mr.methodInfo().isModifying() && mr.scope() instanceof VariableExpression ve) {
                markModified(ve.variable(), builder);
            }
        }

        private void markModified(Variable variable, EvaluationResult.Builder builder) {
            ParameterizedType type = variable.parameterizedType();
            TypeInfo typeInfo = type.typeInfo();
            boolean mutable = type.arrays() > 0 || typeInfo != null && typeInfo.analysis()
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
                                     EvaluationResult.Builder builder,
                                     EvaluationResult leObject,
                                     List<EvaluationResult> leParams,
                                     ParameterizedType forwardType) {
            LinkHelper linkHelper = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod,
                    mc.methodInfo());
            ParameterizedType objectType = mc.methodInfo().isStatic() ? null : mc.object().parameterizedType();
            ParameterizedType concreteReturnType = forwardType == null ? mc.concreteReturnType() : forwardType;

            List<LinkedVariables> linkedVariablesOfParameters = leParams.stream()
                    .map(EvaluationResult::linkedVariables).toList();

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
                    : linkHelper.linkedVariablesMethodCallObjectToReturnType(mc, objectType, leObject.linkedVariables(),
                    linkedVariablesOfParameters, concreteReturnType);

            // merge from param to object and from object to return value
            LinkedVariables lvsResult2 = fp.intoResult() == null ? lvsResult1
                    : lvsResult1.merge(fp.intoResult().linkedVariablesOfExpression());
            builder.setLinkedVariables(lvsResult2);
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
            if (getSet.field() != null && !getSet.setter()) {
                Expression replacedObject = recursivelyReplaceAccessorByFieldReference(runtime, mc.object());
                FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, mc.concreteReturnType());
                return runtime.newVariableExpression(fr);
            }
        }
        return expression;
    }
}
