package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.linkedvariables.hcs.IndexImpl;
import org.e2immu.analyzer.modification.linkedvariables.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.callgraph.PartOfCallCycle;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.shallow.analyzer.AnalysisHelper;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector.*;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.*;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;

public class LinkHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkHelper.class);

    private final Runtime runtime;
    private final GenericsHelper genericsHelper;
    private final AnalysisHelper analysisHelper;

    private final MethodInfo currentMethod;
    private final TypeInfo currentPrimaryType;
    private final MethodInfo methodInfo;
    private final HiddenContentTypes hiddenContentTypes;
    private final HiddenContentSelector hcsSource;

    public LinkHelper(Runtime runtime,
                      GenericsHelper genericsHelper,
                      AnalysisHelper analysisHelper,
                      MethodInfo currentMethod,
                      MethodInfo methodInfoIn) {
        this.runtime = runtime;
        this.analysisHelper = analysisHelper;
        this.genericsHelper = genericsHelper;
        this.currentMethod = currentMethod;
        this.currentPrimaryType = currentMethod.primaryType();
        hiddenContentTypes = bestHiddenContentTypes(methodInfoIn);
        this.methodInfo = Objects.requireNonNullElse(hiddenContentTypes.getMethodInfo(), methodInfoIn);
        ParameterizedType formalObject = this.methodInfo.typeInfo().asParameterizedType(runtime);
        hcsSource = HiddenContentSelector.selectAll(hiddenContentTypes, formalObject);
    }

    // IMPROVE consider storing this information in methodAnalysis; it is often needed
    private static HiddenContentTypes bestHiddenContentTypes(MethodInfo methodInfo) {
        HiddenContentSelector methodHcs = methodInfo.analysis().getOrDefault(HCS_METHOD, NONE);
        if (methodHcs != null && !methodHcs.isNone()) return methodHcs.hiddenContentTypes();
        for (ParameterInfo pi : methodInfo.parameters()) {
            HiddenContentSelector paramHcs = pi.analysis().getOrDefault(HCS_PARAMETER, NONE);
            if (paramHcs != null && !paramHcs.isNone()) {
                return paramHcs.hiddenContentTypes();
            }
        }
        // fall back
        return methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
    }

    private LinkHelper(Runtime runtime,
                       GenericsHelper genericsHelper,
                       AnalysisHelper analysisHelper,
                       MethodInfo currentMethod,
                       HiddenContentTypes hctMethod, HiddenContentSelector hcsSource) {
        this.runtime = runtime;
        this.methodInfo = null;
        this.hiddenContentTypes = hctMethod;
        this.hcsSource = hcsSource;
        this.genericsHelper = genericsHelper;
        this.analysisHelper = analysisHelper;
        this.currentMethod = currentMethod;
        this.currentPrimaryType = currentMethod.primaryType();
    }

    /*
    Linked variables of parameter.
    There are 2 types involved:
      1. type in declaration = parameterMethodType
      2. type in method call = parameterExpression.returnType() == parameterType

    This is a prep method, where we re-compute the base of the parameter's link to the hidden content types of the method.
    The minimum link level is LINK_DEPENDENT.
    The direction of the links is from the method to the variables linked to the parameter, correcting for the concrete parameter type.
    All Indices on the 'from'-side are single HCT indices.

    If the parameterMethodType is a type parameter, we'll have an index of hc wrt the method:
    If the argument is a variable, the link is typically v:0; we'll link 'index of hc' -2-> ALL if the concrete type
      allows so.
    If the argument is dependently linked to a variable, e.g. v.subList(..), then we'll still link DEPENDENT, and
      add the 'index of hc' -2-> ALL.
    If the argument is HC linked to a variable, e.g. new ArrayList<>(..), we'll link 'index of hc' -4-> ALL.

    If parameterMethodType is not a type parameter, we'll compute a translation map which expresses
    the hidden content of the method, expressed in parameterMethodType, to indices in the parameter type.

    IMPORTANT: these links are meant to be combined with either links to object, or links to other parameters.
    This code is different from the normal linkedVariables(...) method.

    In the case of links between parameters, the "source" becomes the object.
     */
    private LinkedVariables linkedVariablesOfParameter(ParameterizedType parameterMethodType,
                                                       ParameterizedType parameterType,
                                                       LinkedVariables linkedVariablesOfParameter,
                                                       HiddenContentSelector hcsSource) {
        return linkedVariablesOfParameter(runtime, hiddenContentTypes,
                parameterMethodType, parameterType, linkedVariablesOfParameter, hcsSource);

    }

    private LinkedVariables linkedVariablesOfParameter(Runtime runtime,
                                                       HiddenContentTypes hiddenContentTypes,
                                                       ParameterizedType parameterMethodType,
                                                       ParameterizedType parameterType,
                                                       LinkedVariables linkedVariablesOfParameter,
                                                       HiddenContentSelector hcsSource) {
        Map<Variable, LV> map = new HashMap<>();
        AtomicBoolean isDelayed = new AtomicBoolean();

        Integer index = hiddenContentTypes.indexOfOrNull(parameterMethodType);
        if (index != null && parameterMethodType.parameters().isEmpty()) {
            if (parameterType.parameters().isEmpty()) {
                linkedVariablesOfParameter.stream().forEach(e -> {
                    Variable variable = e.getKey();
                    LV lv = e.getValue();
                    if (lv.isDelayed()) {
                        isDelayed.set(true);
                    }
                    Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, parameterType);
                    if (immutable == null) {
                        isDelayed.set(true);
                        immutable = ValueImpl.ImmutableImpl.MUTABLE;
                    }
                    if (!immutable.isImmutable()) {
                        boolean m = immutable.isMutable();
                        Indices indices = new IndicesImpl(Set.of(new IndexImpl(List.of(index))));
                        Links links = new LinksImpl(Map.of(indices, new LinkImpl(ALL_INDICES, m)));
                        boolean independentHc = lv.isCommonHC();
                        LV newLv = independentHc ? LVImpl.createHC(links) : LVImpl.createDependent(links);
                        map.put(variable, newLv);
                    }
                });
            } else {
                // we have type parameters in the concrete type --- must link into those
                HiddenContentTypes newHiddenContentTypes = parameterType.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                ParameterizedType newParameterMethodType = parameterType.typeInfo().asParameterizedType(runtime);
                HiddenContentSelector newHcsSource = HiddenContentSelector.selectAll(newHiddenContentTypes, newParameterMethodType);
                LinkedVariables recursive = linkedVariablesOfParameter(runtime, newHiddenContentTypes,
                        newParameterMethodType, parameterType, linkedVariablesOfParameter, newHcsSource);
                return recursive.map(lv -> lv.prefixMine(index));
            }
        } else {
            Map<Indices, HiddenContentSelector.IndicesAndType> targetData = HiddenContentSelector
                    .translateHcs(runtime, genericsHelper, hcsSource, parameterMethodType, parameterType);
            linkedVariablesOfParameter.stream().forEach(e -> {
                LV newLv;
                LV lv = e.getValue();
                if (lv.isDelayed()) {
                    isDelayed.set(true);
                }
                if (targetData != null && !targetData.isEmpty()) {
                    Map<Indices, Link> linkMap = new HashMap<>();
                    Collection<Indices> targetDataKeys;
                    // NOTE: this type of filter occurs in 'continueLinkedVariables' as well
                    if (lv.haveLinks()) {
                        targetDataKeys = lv.links().map().keySet().stream().filter(targetData::containsKey).toList();
                    } else {
                        targetDataKeys = targetData.keySet();
                    }
                    for (Indices iInHctSource : targetDataKeys) {
                        HiddenContentSelector.IndicesAndType value = targetData.get(iInHctSource);
                        Indices iInHctTarget = lv.haveLinks() && lv.theirsIsAll() ? ALL_INDICES : value.indices();
                        ParameterizedType type = value.type();
                        assert type != null;
                        Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, type);
                        if (immutable == null) {
                            isDelayed.set(true);
                            immutable = ValueImpl.ImmutableImpl.MUTABLE;
                        } else if (immutable.isImmutable()) {
                            continue;
                        }
                        boolean mutable = immutable.isMutable();
                        linkMap.put(iInHctSource, new LinkImpl(iInHctTarget, mutable));
                    }
                    if (linkMap.isEmpty()) {
                        newLv = createDependent(linkAllSameType(parameterType));
                    } else {
                        Links links = new LinksImpl(Map.copyOf(linkMap));
                        boolean independentHc = lv.isCommonHC();
                        newLv = independentHc ? LVImpl.createHC(links) : LVImpl.createDependent(links);
                    }
                } else {
                    newLv = createDependent(linkAllSameType(parameterType));
                }
                Variable variable = e.getKey();
                map.put(variable, newLv);
            });
        }
        LinkedVariables lvs = LinkedVariablesImpl.of(map);
        if (isDelayed.get()) {
            return lvs.changeToDelay();
        }
        return lvs;
    }

    public LinkedVariables functional(Value.Independent independentOfMethod,
                                      HiddenContentSelector hcsMethod,
                                      LinkedVariables linkedVariablesOfObject,
                                      ParameterizedType concreteReturnType,
                                      List<Value.Independent> independentOfParameter,
                                      List<HiddenContentSelector> hcsParameters,
                                      List<ParameterizedType> expressionTypes,
                                      List<LinkedVariables> linkedVariablesOfParameters,
                                      ParameterizedType concreteFunctionalType) {
        LinkedVariables lvs = functional(independentOfMethod, hcsMethod, linkedVariablesOfObject, concreteReturnType,
                concreteFunctionalType);
        int i = 0;
        for (ParameterizedType expressionType : expressionTypes) {
            int index = Math.min(hcsParameters.size() - 1, i);
            Value.Independent independent = independentOfParameter.get(index);
            HiddenContentSelector hcs = hcsParameters.get(index);
            LinkedVariables linkedVariables = linkedVariablesOfParameters.get(index);
            LinkedVariables lvsParameter;
            if (linkedVariables == LinkedVariablesImpl.NOT_YET_SET) {
                lvsParameter = linkedVariables;
            } else {
                lvsParameter = functional(independent, hcs, linkedVariables, expressionType,
                        concreteFunctionalType);
            }
            lvs = lvs.merge(lvsParameter);
            i++;
        }
        return lvs;
    }

    private LinkedVariables functional(Value.Independent independent,
                                       HiddenContentSelector hcs,
                                       LinkedVariables linkedVariables,
                                       ParameterizedType type,
                                       ParameterizedType concreteFunctionalType) {
        if (independent.isIndependent()) return LinkedVariablesImpl.EMPTY;
        boolean independentHC = independent.isAtLeastIndependentHc();
        Map<Variable, LV> map = new HashMap<>();
        AtomicBoolean isDelayed = new AtomicBoolean();
        linkedVariables.forEach(e -> {
            Value.Immutable mutable = analysisHelper.typeImmutable(currentPrimaryType, type);
            if (mutable == null) {
                isDelayed.set(true);
                mutable = ValueImpl.ImmutableImpl.MUTABLE;
            }
            if (!mutable.isImmutable()) {
                Map<Indices, Link> correctedMap = new HashMap<>();
                for (int hctIndex : hcs.set()) {
                    Indices indices = new IndicesImpl(hctIndex);
                    // see e.g. Linking_1A,f9m(): we correct 0 to 0;1, and 1 to 0;1
                    Indices corrected = indices.allOccurrencesOf(runtime, concreteFunctionalType);
                    Link link = new LinkImpl(indices, mutable.isMutable());
                    correctedMap.put(corrected, link);
                }
                Links links = new LinksImpl(correctedMap);
                LV lv = independentHC ? LVImpl.createHC(links) : LVImpl.createDependent(links);
                map.put(e.getKey(), lv);
            }
            if (e.getValue().isDelayed()) isDelayed.set(true);
        });
        if (map.isEmpty()) return LinkedVariablesImpl.EMPTY;
        if (isDelayed.get()) {
            return LinkedVariablesImpl.of(map).changeToDelay();
        }
        return LinkedVariablesImpl.of(map);
    }

    public record LambdaResult(List<LinkedVariables> linkedToParameters, LinkedVariables linkedToReturnValue) {
        public LinkedVariables delay() {
            return linkedToParameters.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge)
                    .merge(linkedToReturnValue.changeToDelay());
        }

        public LinkedVariables mergedLinkedToParameters() {
            return linkedToParameters.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge);
        }
    }

    /*
    SITUATION 0: both return value and all parameters @Independent
    no linking

    predicate is the typical example

    SITUATION 1: interesting return value, no parameters, or all parameters @Independent

    rv = supplier.get()             rv *-4-0 supplier       non-modifying, object to return value
    s = () -> supplier.get()        s 0-4-0 supplier
    s = supplier::get               s 0-4-0 supplier

    rv = iterator.next()            rv *-4-0 iterator (it is not relevant if the method is modifying, or not)
    s = () -> iterator.next()       s 0-4-0 iterator
    s = iterator::next              s 0-4-0 iterator
    t = s.get()                     t *-4-0 s, *-0-4 iterator

    rv = biSupplier.get()           rv *-4-0.0;0.1
    s = () -> biSupplier.get()      s 0.0;0.1-4-0.0;0.1
    pair = s.get()                  pair 0.0;0.1-4-0.0;0.1 biSupplier,s
    x = pair.x                      x *-4-0.0 pair, *-4-0.0 biSupplier

    -4- links depending on the HCS of the method (@Independent(hc), @Dependent?)
    -4-M links depending on the concrete type of rv when computing
    -2- links are also possible, e.g. subList(0, 2)

    sub = list.subList(0, 3)        sub 0-2-0 list
    s = i -> list.subList(0, i)     s 0-2-0 list

    conclusion:
     - irrespective of method modification.
     - @Independent of method is primary selector (-2-,-4-,no link)
     - * gets upgraded to the value in method HCS

    SITUATION 2: no return value, or an @Independent return value; at least one interesting parameter

    consumer.accept(t)              t *-4-0 consumer        modifying, parameter to object
    s = (t -> consumer.accept(t))   s 0-4-0 consumer
    sx.foreach(consumer)            sx 0-4-0 consumer

    list.add(t)                     t *-4-0 list
    s = t -> list.add(t)            s 0-4-0 list

    conclusion:
    - identical to situation 1, where the parameter(s) takes the role of the return value; each independently of the other

    SITUATION 3: neither return value, nor at least one parameter is @Independent
    (the return value links to the object, and at least one parameter links to the object)

    do both of 1 and 2, and take union. 0.0-4-0.1 and 0.0-4-0.0 may result in 0.0;0.1-4-0.0;0.1
    example??

    FIXME split method so that it can be called from MR as well
    */
    public static LambdaResult lambdaLinking(Runtime runtime, MethodInfo concreteMethod) {
        if (concreteMethod.methodBody().isEmpty()) {
            return new LambdaResult(List.of(), LinkedVariablesImpl.EMPTY);
        }
        List<Statement> statements = concreteMethod.methodBody().statements();
        Statement lastStatement = statements.get(statements.size() - 1);
        List<LinkedVariables> result = new ArrayList<>(concreteMethod.parameters().size() + 1);

        for (ParameterInfo pi : concreteMethod.parameters()) {
            VariableInfo vi = lastStatement.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class)
                    .variableInfo(pi.fullyQualifiedName());
            LinkedVariables lv = vi.linkedVariables();
            //.remove(v -> FIXME not yet implemented
            // !evaluationContext.acceptForVariableAccessReport(v, concreteMethod.typeInfo()));
            result.add(lv);
        }
        if (concreteMethod.hasReturnValue()) {
            ReturnVariable returnVariable = new ReturnVariableImpl(concreteMethod);
            VariableInfo vi = lastStatement.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class)
                    .variableInfo(returnVariable.fullyQualifiedName());
            if (concreteMethod.parameters().isEmpty()) {
                return new LambdaResult(result, vi.linkedVariables());
            }
            // link to the input types rather than the output type, see also HCT.mapMethodToTypeIndices
            Map<Indices, Indices> correctionMap = new HashMap<>();
            // FIXME 1??
            correctionMap.put(new IndicesImpl(1), new IndicesImpl(0));
            LinkedVariables corrected = vi.linkedVariables().map(lv -> lv.correctTo(correctionMap));
            return new LambdaResult(result, corrected);
        }
        return new LambdaResult(result, LinkedVariablesImpl.EMPTY);
    }


    public record FromParameters(LinkEvaluation.Builder intoObject,
                                 LinkEvaluation.Builder intoResult,
                                 Map<Integer, Integer> correctionMap) {
    }

    /*
    Add all necessary links from parameters into scope, and in-between parameters
     */
    public FromParameters linksInvolvingParameters(ParameterizedType objectPt,
                                                   ParameterizedType resultPt,
                                                   List<Expression> parameterExpressions,
                                                   List<LinkEvaluation> linkedVariables) {
        LinkEvaluation.Builder intoObjectBuilder = new LinkEvaluation.Builder();
        LinkEvaluation.Builder intoResultBuilder = resultPt == null || resultPt.isVoid() ? null
                : new LinkEvaluation.Builder();
        Map<Integer, Integer> correctionMap = new HashMap<>();
        if (!methodInfo.parameters().isEmpty()) {
            boolean isFactoryMethod = methodInfo.isFactoryMethod();
            // links between object/return value and parameters
            for (ParameterInfo pi : methodInfo.parameters()) {
                Value.Independent formalParameterIndependent = pi.analysis()
                        .getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.DEPENDENT);
                LinkedVariables lvsToResult = isFactoryMethod
                        ? pi.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER,
                        LinkedVariablesImpl.EMPTY)
                        : linkedVariablesToResult(pi);
                boolean inResult = intoResultBuilder != null && !lvsToResult.isEmpty();
                if (!formalParameterIndependent.isIndependent() || inResult) {
                    ParameterizedType parameterType = parameterExpressions.get(pi.index()).parameterizedType();
                    LinkedVariables parameterLvs;
                    LinkEvaluation linkEvaluation = linkedVariables.get(pi.index());
                    LinkedVariables lvs = linkEvaluation.linkedVariables();
                    if (inResult) {
                        /*
                        change the links of the parameter to the value of the return variable (see also MethodReference,
                        computation of links when modified is true)
                         */
                        LinkedVariables returnValueLvs = linkedVariablesOfParameter(pi.parameterizedType(),
                                parameterExpressions.get(pi.index()).parameterizedType(), lvs, hcsSource);
                        LV valueOfReturnValue = lvsToResult.stream().filter(e -> e.getKey() instanceof ReturnVariable)
                                .map(Map.Entry::getValue).findFirst().orElseThrow();
                        Map<Variable, LV> map = returnValueLvs.stream().collect(Collectors.toMap(Map.Entry::getKey,
                                e -> Objects.requireNonNull(follow(valueOfReturnValue, e.getValue()))));
                        parameterLvs = LinkedVariablesImpl.of(map);
                        formalParameterIndependent = valueOfReturnValue.isCommonHC() ? ValueImpl.IndependentImpl.INDEPENDENT_HC :
                                ValueImpl.IndependentImpl.DEPENDENT;
                    } else {
                        parameterLvs = linkedVariablesOfParameter(pi.parameterizedType(),
                                parameterExpressions.get(pi.index()).parameterizedType(), lvs, hcsSource);
                    }
                    LinkEvaluation.Builder builder = inResult ? intoResultBuilder : intoObjectBuilder;
                    linkEvaluation.links().forEach((v, lvs1) -> {
                        lvs1.forEach(e -> {
                            if (!e.getValue().isStaticallyAssignedOrAssigned()) {
                                builder.merge(v, LinkedVariablesImpl.of(e.getKey(), e.getValue()));
                            } // FIXME this is not the best way to approach copying, what with -1- links??
                        });
                    });
                    ParameterizedType pt = inResult ? resultPt : objectPt;
                    ParameterizedType methodPt;
                    if (inResult) {
                        methodPt = methodInfo.returnType();
                    } else {
                        methodPt = methodInfo.typeInfo().asParameterizedType(runtime);
                    }
                    HiddenContentTypes methodHct = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                    Map<Integer, Integer> mapMethodHCTIndexToTypeHCTIndex = methodHct
                            .mapMethodToTypeIndices(inResult ? methodInfo.returnType() : pi.parameterizedType());
                    correctionMap.putAll(mapMethodHCTIndexToTypeHCTIndex);
                    HiddenContentSelector hcsTarget = pi.analysis().getOrDefault(HCS_PARAMETER, NONE).correct(mapMethodHCTIndexToTypeHCTIndex);
                    if (pt != null) {
                        LinkedVariables lv;
                        if (inResult) {
                            // parameter -> result

                            HiddenContentSelector methodHcs = methodInfo.analysis().getOrDefault(HCS_METHOD, NONE);
                            HiddenContentSelector hcsSource = methodHcs.correct(mapMethodHCTIndexToTypeHCTIndex);
                            lv = linkedVariables(this.hcsSource, parameterType, pi.parameterizedType(), hcsTarget,
                                    parameterLvs, false, formalParameterIndependent, pt, methodPt,
                                    hcsSource, false);
                        } else {
                            if (pi.parameterizedType().isTypeParameter() && !parameterType.parameters().isEmpty()) {
                                Value.Immutable mutable = analysisHelper.typeImmutable(currentPrimaryType, pi.parameterizedType());
                                if (mutable == null) {
                                    lv = parameterLvs.changeToDelay();
                                } else if (mutable.isMutable()) {
                                    lv = parameterLvs;
                                } else {
                                    lv = parameterLvs.map(LV::changeToHc);
                                }
                            } else {
                                // object -> parameter (rather than the other way around)
                                lv = linkedVariables(this.hcsSource, pt, methodPt, this.hcsSource, parameterLvs, false,
                                        formalParameterIndependent, parameterType, pi.parameterizedType(), hcsTarget,
                                        true);
                            }
                        }
                        builder.mergeLinkedVariablesOfExpression(lv);
                    }
                }
            }

            linksBetweenParameters(intoObjectBuilder, methodInfo, parameterExpressions, linkedVariables);
        }
        return new FromParameters(intoObjectBuilder, intoResultBuilder, Map.copyOf(correctionMap));
    }

    public void linksBetweenParameters(LinkEvaluation.Builder builder,
                                       MethodInfo methodInfo,
                                       List<Expression> parameterExpressions,
                                       List<LinkEvaluation> linkedVariables) {
        Map<ParameterInfo, LinkedVariables> crossLinks = translateLinksToParameters(methodInfo);
        if (crossLinks.isEmpty()) return;
        crossLinks.forEach((pi, lv) -> {
            boolean sourceIsVarArgs = pi.isVarArgs();
            assert !sourceIsVarArgs : "Varargs must always be a target";
            HiddenContentSelector hcsSource = methodInfo.parameters().get(pi.index()).analysis()
                    .getOrDefault(HCS_PARAMETER, NONE);
            ParameterizedType sourceType = parameterExpressions.get(pi.index()).parameterizedType();
            LinkedVariables sourceLvs = linkedVariablesOfParameter(pi.parameterizedType(),
                    parameterExpressions.get(pi.index()).parameterizedType(),
                    linkedVariables.get(pi.index()).linkedVariables(), hcsSource);

            lv.stream().forEach(e -> {
                ParameterInfo target = (ParameterInfo) e.getKey();

                boolean targetIsVarArgs = target.isVarArgs();
                if (!targetIsVarArgs || linkedVariables.size() > target.index()) {

                    LV level = e.getValue();

                    for (int i = target.index(); i < linkedVariables.size(); i++) {
                        ParameterizedType targetType = parameterExpressions.get(target.index()).parameterizedType();
                        HiddenContentSelector hcsTarget = methodInfo.parameters().get(target.index()).analysis()
                                .getOrDefault(HCS_PARAMETER, NONE);

                        LinkedVariables targetLinkedVariables = linkedVariablesOfParameter(target.parameterizedType(),
                                parameterExpressions.get(i).parameterizedType(),
                                linkedVariables.get(i).linkedVariables(), hcsSource);

                        Value.Independent independentDv = level.isCommonHC() ? ValueImpl.IndependentImpl.INDEPENDENT_HC
                                : ValueImpl.IndependentImpl.DEPENDENT;
                        LinkedVariables mergedLvs = linkedVariables(hcsSource, targetType, target.parameterizedType(), hcsSource,
                                targetLinkedVariables, targetIsVarArgs, independentDv, sourceType, pi.parameterizedType(),
                                hcsTarget, targetIsVarArgs);
                        crossLink(sourceLvs, mergedLvs, builder); // FIXME
                    }
                } // else: no value... empty varargs
            });
        });
    }

    private LinkedVariables linkedVariablesToResult(ParameterInfo pi) {
        return LinkedVariablesImpl.EMPTY; // FIXME
    }

    private Map<ParameterInfo, LinkedVariables> translateLinksToParameters(MethodInfo methodInfo) {
        Map<ParameterInfo, Map<Variable, LV>> res = new HashMap<>();
        for (ParameterInfo pi : methodInfo.parameters()) {
            Independent independent = pi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER,
                    ValueImpl.IndependentImpl.DEPENDENT);
            Map<Variable, LV> lvMap = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : independent.linkToParametersReturnValue().entrySet()) {
                if (e.getKey() >= 0) {
                    ParameterInfo target = methodInfo.parameters().get(e.getKey());
                    LV lv;
                    if (e.getValue() == 0) {
                        lv = LINK_DEPENDENT;
                    } else {
                        lv = createHC(linkAllSameType(pi.parameterizedType()));
                    }
                    lvMap.put(target, lv);
                }
            }
            if (!lvMap.isEmpty()) res.put(pi, lvMap);
        }
        return res.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> LinkedVariablesImpl.of(e.getValue())));
    }

    public static Links linkAllSameType(ParameterizedType parameterizedType) {
        TypeInfo typeInfo = parameterizedType.typeInfo();
        HiddenContentTypes hct = typeInfo.analysis().getOrNull(HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
        assert hct != null : "HCT not yet computed for " + typeInfo;
        if (hct.hasHiddenContent()) {
            Map<Indices, Link> map = new HashMap<>();
            for (int i = 0; i < hct.size(); i++) {
                // not mutable, because hidden content
                // from i to i, because we have a -1- relation, so the type must be the same
                map.put(new IndicesImpl(i), new LinkImpl(new IndicesImpl(i), false));
            }
            return new LinksImpl(Map.copyOf(map));
        }
        return LinksImpl.NO_LINKS; // FIXME
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

    public LinkedVariables linkedVariablesMethodCallObjectToReturnType(ParameterizedType objectType,
                                                                       LinkedVariables linkedVariablesOfObject,
                                                                       List<LinkedVariables> linkedVariables,
                                                                       ParameterizedType returnType) {
        return linkedVariablesMethodCallObjectToReturnType(objectType, linkedVariablesOfObject, linkedVariables, returnType, Map.of());
    }

    public LinkedVariables linkedVariablesMethodCallObjectToReturnType(ParameterizedType objectType,
                                                                       LinkedVariables linkedVariablesOfObjectIn,
                                                                       List<LinkedVariables> linkedVariables,
                                                                       ParameterizedType returnType,
                                                                       Map<Integer, Integer> mapMethodHCTIndexToTypeHCTIndex) {
        // RULE 1: void method cannot link
        if (methodInfo.noReturnValue()) return LinkedVariablesImpl.EMPTY;
        boolean recursiveCall = recursiveCall(methodInfo, currentMethod);
        boolean breakCallCycleDelay = methodInfo.analysis()
                .getOrDefault(PartOfCallCycle.IGNORE_ME_PART_OF_CALL_CYCLE, ValueImpl.BoolImpl.FALSE).isTrue();
        if (recursiveCall || breakCallCycleDelay) {
            return LinkedVariablesImpl.EMPTY;
        }

        // RULE 2: @Identity links to the 1st parameter
        Value.Bool identity = methodInfo.analysis().getOrNull(PropertyImpl.IDENTITY_METHOD, ValueImpl.BoolImpl.class);
        if (identity != null && identity.isTrue()) {
            return linkedVariables.get(0).maximum(LINK_ASSIGNED);
        }
        LinkedVariables linkedVariablesOfObject = linkedVariablesOfObjectIn.maximum(LINK_ASSIGNED); // should be delay-able!

        if (identity == null && !linkedVariables.isEmpty()) {
            // temporarily link to both the object and the parameter, in a delayed way
            LinkedVariables allParams = linkedVariables.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge);
            return linkedVariablesOfObject.merge(allParams).changeToDelay();
        }

        // RULE 3: @Fluent simply returns the same object, hence, the same linked variables
        Value.Bool fluent = methodInfo.analysis().getOrNull(PropertyImpl.FLUENT_METHOD, ValueImpl.BoolImpl.class);
        if (fluent == null) {
            return linkedVariablesOfObject.changeNonStaticallyAssignedToDelay();
        }
        if (fluent.isTrue()) {
            return linkedVariablesOfObject;
        }
        Value.Independent independent = methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD,
                ValueImpl.IndependentImpl.class);
        ParameterizedType methodType = methodInfo.typeInfo().asParameterizedType(runtime);
        ParameterizedType methodReturnType = methodInfo.returnType();

        HiddenContentSelector hcsTarget = methodInfo.analysis().getOrDefault(HCS_METHOD, NONE)
                .correct(mapMethodHCTIndexToTypeHCTIndex);

        return linkedVariables(hcsSource, objectType,
                methodType, hcsSource, linkedVariablesOfObject,
                false,
                independent, returnType, methodReturnType, hcsTarget,
                false);
    }

       /* we have to probe the object first, to see if there is a value
       A. if there is a value, and the value offers a concrete implementation, we replace methodInfo by that
       concrete implementation.
       B. if there is no value, and the delay indicates that a concrete implementation may be forthcoming,
       we delay
       C otherwise (no value, no concrete implementation forthcoming) we continue with the abstract method.
       */

    public static boolean recursiveCall(MethodInfo methodInfo, MethodInfo currentMethod) {
        if (currentMethod == methodInfo) return true;
        MethodInfo enclosingMethod = currentMethod.typeInfo().enclosingMethod();
        if (enclosingMethod != null) {
            LOGGER.debug("Going recursive on call to {}, to {} ", methodInfo.fullyQualifiedName(),
                    enclosingMethod.typeInfo());
            return recursiveCall(methodInfo, enclosingMethod);
        }
        return false;
    }

    /**
     * Important: this method does not deal with hidden content specific to the method, because it has been designed
     * to connect the object to the return value, as called from <code>linkedVariablesMethodCallObjectToReturnType</code>.
     * Calls originating from <code>linksInvolvingParameters</code> must take this into account.
     *
     * @param sourceType                    must be type of object or parameterExpression, return type, non-evaluated
     * @param methodSourceType              the method declaration's type of the source
     * @param hiddenContentSelectorOfSource with respect to the method's HCT and methodSourceType
     * @param sourceLvs                     linked variables of the source
     * @param sourceIsVarArgs               allow for a correction of array -> element
     * @param transferIndependent           the transfer mode (dependent, independent HC, independent)
     * @param targetType                    must be type of object or parameterExpression, return type, non-evaluated
     * @param methodTargetType              the method declaration's type of the target
     * @param hiddenContentSelectorOfTarget with respect to the method's HCT and methodTargetType
     * @param reverse                       reverse the link, because we're reversing source and target, because we
     *                                      only deal with *->0 in this method, never 0->*,
     * @return the linked values of the target
     */
    private LinkedVariables linkedVariables(HiddenContentSelector hcsSource,
                                            ParameterizedType sourceType,
                                            ParameterizedType methodSourceType,
                                            HiddenContentSelector hiddenContentSelectorOfSource,
                                            LinkedVariables sourceLvs,
                                            boolean sourceIsVarArgs,
                                            Value.Independent transferIndependent,
                                            ParameterizedType targetType,
                                            ParameterizedType methodTargetType,
                                            HiddenContentSelector hiddenContentSelectorOfTarget,
                                            boolean reverse) {
        assert targetType != null;

        // RULE 1: no linking when the source is not linked or there is no transfer
        if (sourceLvs.isEmpty() || transferIndependent != null && transferIndependent.isIndependent()) {
            return LinkedVariablesImpl.EMPTY;
        }
        assert !(hiddenContentSelectorOfTarget.isNone()
                 && transferIndependent != null && transferIndependent.isIndependentHc())
                : "Impossible to have no knowledge of hidden content, and INDEPENDENT_HC";

        Value.Immutable immutableOfSource = analysisHelper.typeImmutable(currentPrimaryType, sourceType);

        // RULE 2: delays
        if (immutableOfSource == null) {
            return sourceLvs.changeToDelay();
        }

        // RULE 3: immutable -> no link
        if (immutableOfSource.isImmutable()) {
            /*
             if the result type immutable because of a choice in type parameters, methodIndependent will return
             INDEPENDENT_HC, but the concrete type is deeply immutable
             */
            return LinkedVariablesImpl.EMPTY;
        }

        // RULE 4: delays
        if (transferIndependent == null) {
            // delay in method independent
            return sourceLvs.changeToDelay();
        }

        // special code block for functional interfaces with both return value and parameters (i.e. variants
        // on Function<T,R>, BiFunction<T,S,R> etc. Not Consumers (no return value) nor Suppliers (no parameters))
        if (hiddenContentSelectorOfTarget.isOnlyAll() && transferIndependent.isIndependentHc()) {
            HiddenContentTypes hctContext = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);

            HiddenContentSelector hcsTargetContext = HiddenContentSelector.selectAll(hctContext, targetType);
            HiddenContentSelector hcsSourceContext = HiddenContentSelector.selectAll(hctContext, sourceType);
            Set<Integer> set = new HashSet<>(hcsSourceContext.set());
            set.retainAll(hcsTargetContext.set());
            if (!set.isEmpty()) {
                List<LinkedVariables> lvsList = new ArrayList<>();
                for (int index : set) {
                    Indices indices = hcsSourceContext.getMap().get(index);
                    if (indices.containsSize2Plus()) {
                        Indices newIndices = indices.size2PlusDropOne();
                        Indices base = indices.first();
                        HiddenContentSelector newHiddenContentSelectorOfSource
                                = new HiddenContentSelector(hctContext, Map.of(index, newIndices));
                        ParameterizedType newSourceType = base.find(runtime, sourceType);
                        Supplier<Map<Indices, HiddenContentSelector.IndicesAndType>> hctMethodToHctSourceSupplier =
                                () -> Map.of(newIndices, new HiddenContentSelector.IndicesAndType(newIndices, newSourceType));
                        HiddenContentSelector newHcsTarget;
                        ParameterizedType newTargetType;
                        if (reverse && !targetType.isTypeParameter()) {
                            // List<T> as parameter
                            newHcsTarget = newHiddenContentSelectorOfSource;
                            newTargetType = newSourceType;
                        } else if (!reverse && !targetType.isTypeParameter()) {
                            // List<T> as return type
                            newTargetType = targetType;
                            newHcsTarget = newHiddenContentSelectorOfSource;
                        } else {
                            // object -> return
                            newHcsTarget = new HiddenContentSelector(hctContext, Map.of(index, ALL_INDICES));
                            newTargetType = targetType;
                        }

                        LinkedVariables lvs = continueLinkedVariables(hctContext, newHiddenContentSelectorOfSource,
                                sourceLvs, sourceIsVarArgs, transferIndependent, immutableOfSource,
                                newTargetType, newTargetType, newHcsTarget, hctMethodToHctSourceSupplier,
                                reverse);
                        lvsList.add(lvs);
                    }
                }
                if (!lvsList.isEmpty()) {
                    return lvsList.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge);
                }
            }
        }
        Supplier<Map<Indices, HiddenContentSelector.IndicesAndType>> hctMethodToHctSourceSupplier =
                () -> HiddenContentSelector.translateHcs(runtime, genericsHelper, hcsSource, methodSourceType, sourceType);

        Value.Immutable immutableOfFormalSource;
        if (sourceType.typeInfo() != null) {
            ParameterizedType formalSource = sourceType.typeInfo().asParameterizedType(runtime);
            immutableOfFormalSource = analysisHelper.typeImmutable(currentPrimaryType, formalSource);
        } else {
            immutableOfFormalSource = immutableOfSource;
        }
        return continueLinkedVariables(hiddenContentTypes,
                hiddenContentSelectorOfSource,
                sourceLvs, sourceIsVarArgs, transferIndependent, immutableOfFormalSource, targetType,
                methodTargetType, hiddenContentSelectorOfTarget, hctMethodToHctSourceSupplier, reverse);
    }

    private LinkedVariables continueLinkedVariables(HiddenContentTypes hiddenContentTypes,
                                                    HiddenContentSelector hiddenContentSelectorOfSource,
                                                    LinkedVariables sourceLvs,
                                                    boolean sourceIsVarArgs,
                                                    Value.Independent transferIndependent,
                                                    Value.Immutable immutableOfFormalSource,
                                                    ParameterizedType targetType,
                                                    ParameterizedType methodTargetType,
                                                    HiddenContentSelector hiddenContentSelectorOfTarget,
                                                    Supplier<Map<Indices, IndicesAndType>> hctMethodToHctSourceSupplier,
                                                    boolean reverse) {
        Map<Indices, HiddenContentSelector.IndicesAndType> hctMethodToHcsTarget = HiddenContentSelector
                .translateHcs(runtime, genericsHelper, hiddenContentSelectorOfTarget, methodTargetType, targetType);
        Value.Independent correctedIndependent = correctIndependent(immutableOfFormalSource, transferIndependent,
                targetType, hiddenContentSelectorOfTarget, hctMethodToHcsTarget);

        if (correctedIndependent == null) {
            // delay in method independent
            return sourceLvs.changeToDelay();
        }
        if (correctedIndependent.isIndependent()) {
            return LinkedVariablesImpl.EMPTY;
        }

        Map<Indices, HiddenContentSelector.IndicesAndType> hctMethodToHctSource = hctMethodToHctSourceSupplier.get();
        Map<Variable, LV> newLinked = new HashMap<>();

        for (Map.Entry<Variable, LV> e : sourceLvs) {
            ParameterizedType pt = e.getKey().parameterizedType();
            // for the purpose of this algorithm, unbound type parameters are HC
            Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, pt);
            LV lv = e.getValue();
            assert lv.lt(LINK_INDEPENDENT);

            if (immutable == null || lv.isDelayed()) {
                return sourceLvs.changeToDelay();
            }
            if (!immutable.isImmutable()) {

                if (hiddenContentSelectorOfTarget.isNone()) {
                    newLinked.put(e.getKey(), LINK_DEPENDENT);
                } else {
                    // from mine==target to theirs==source
                    Map<Indices, Link> linkMap = new HashMap<>();

                        /*
                        this is the only place during computational analysis where we create common HC links.
                        all other links are created in the ShallowMethodAnalyser.
                         */
                 /*   if (hiddenContentSelectorOfTarget instanceof HiddenContentSelector.All all) {
                        DV typeImmutable = context.evaluationContext().immutable(targetType);
                        if (typeImmutable.isDelayed()) {
                            causesOfDelay = causesOfDelay.merge(typeImmutable.causesOfDelay());
                        }
                        boolean mutable = MultiLevel.isMutable(typeImmutable);
                        int i = all.getHiddenContentIndex();

                        assert hctMethodToHctSource != null;
                        // the indices contain a single number, the index in the hidden content types of the source.
                        HiddenContentTypes.IndicesAndType indicesAndType = hctMethodToHctSource.get(new Indices(i));
                        assert indicesAndType != null;
                        Indices iInHctSource = indicesAndType.indices();
                        if (iInHctSource != null) {
                            linkMap.put(ALL_INDICES, new Link(iInHctSource, mutable));
                        }// else: no type parameters available, see e.g. Linking_0P.reverse5
                    } else {*/
                    // both are CsSet, we'll set mutable what is mutable, in a common way

                    Boolean correctForVarargsMutable = null;

                    assert hctMethodToHctSource != null;

                    // NOTE: this type of filtering occurs in 'linkedVariablesOfParameter' as well
                    Set<Map.Entry<Integer, Indices>> entrySet;
                    if (hiddenContentSelectorOfTarget.isOnlyAll() || !lv.haveLinks()) {
                        entrySet = hiddenContentSelectorOfTarget.getMap().entrySet();
                    } else {
                        entrySet = filter(lv.links().map().keySet(), hiddenContentSelectorOfTarget.getMap().entrySet());
                    }
                    for (Map.Entry<Integer, Indices> entry : entrySet) {
                        Indices indicesInTargetWrtMethod = entry.getValue();
                        HiddenContentSelector.IndicesAndType targetAndType = hctMethodToHcsTarget.get(indicesInTargetWrtMethod);
                        assert targetAndType != null;
                        ParameterizedType type = targetAndType.type();
                        assert type != null;

                        Value.Immutable typeImmutable = analysisHelper.typeImmutable(currentPrimaryType, type);
                        if (typeImmutable == null) {
                            return sourceLvs.changeToDelay();
                        }
                        if (typeImmutable.isImmutable()) {
                            continue;
                        }
                        boolean mutable = typeImmutable.isMutable();
                        if (sourceIsVarArgs) {
                            // we're in a varargs situation: the first element is the type itself
                            correctForVarargsMutable = mutable;
                        }

                        Indices indicesInSourceWrtMethod = hiddenContentSelectorOfSource.getMap().get(entry.getKey());
                        assert indicesInSourceWrtMethod != null;
                        HiddenContentSelector.IndicesAndType indicesAndType = hctMethodToHctSource.get(indicesInSourceWrtMethod);
                        assert indicesAndType != null;
                        Indices indicesInSourceWrtType = indicesAndType.indices();
                        assert indicesInSourceWrtType != null;

                        // FIXME this feels rather arbitrary, see Linking_0P.reverse4 yet the 2nd clause seems needed for 1A.f10()
                        Indices indicesInTargetWrtType = (lv.theirsIsAll()
                                                          && entrySet.size() < hiddenContentSelectorOfTarget.getMap().size()
                                                          && reverse) ? ALL_INDICES : targetAndType.indices();
                        Indices correctedIndicesInTargetWrtType;
                        if (correctForVarargsMutable != null) {
                            correctedIndicesInTargetWrtType = ALL_INDICES;
                        } else {
                            correctedIndicesInTargetWrtType = indicesInTargetWrtType;
                        }
                        assert correctedIndicesInTargetWrtType != null;
                        linkMap.put(correctedIndicesInTargetWrtType, new LinkImpl(indicesInSourceWrtType, mutable));
                    }


                    boolean createDependentLink = immutable.isMutable() && isDependent(transferIndependent,
                            correctedIndependent, immutableOfFormalSource, lv);
                    if (createDependentLink) {
                        if (linkMap.isEmpty()) {
                            newLinked.put(e.getKey(), LINK_DEPENDENT);
                        } else {
                            Links links = new LinksImpl(Map.copyOf(linkMap));
                            LV dependent = reverse ? LVImpl.createDependent(links.reverse()) : LVImpl.createDependent(links);
                            newLinked.put(e.getKey(), dependent);
                        }
                    } else if (!linkMap.isEmpty()) {
                        Links links = new LinksImpl(Map.copyOf(linkMap));
                        LV commonHC = reverse ? LVImpl.createHC(links.reverse()) : LVImpl.createHC(links);
                        newLinked.put(e.getKey(), commonHC);
                    }
                }
            } else {
                throw new UnsupportedOperationException("I believe we should not link");
            }
        }
        return LinkedVariablesImpl.of(newLinked);
    }

    private Set<Map.Entry<Integer, Indices>> filter(Set<Indices> indices, Set<Map.Entry<Integer, Indices>> entries) {
        return entries.stream().filter(e -> indices.contains(e.getValue())).collect(Collectors.toUnmodifiableSet());
    }

    private boolean isDependent(Value.Independent transferIndependent,
                                Value.Independent correctedIndependent,
                                Value.Immutable immutableOfSource,
                                LV lv) {
        return
                // situation immutable(mutable), we'll have to override
                transferIndependent.isIndependentHc()
                && correctedIndependent.isDependent()
                ||
                // situation mutable(immutable), dependent method,
                transferIndependent.isDependent()
                && !lv.isCommonHC()
                && !immutableOfSource.isAtLeastImmutableHC();
    }
    
    /*
     Important: the last three parameters should form a consistent set, all computed with respect to the same
     formal type (targetType.typeInfo).
    
     First translate the HCS from the method target to the target!
     */

    private Value.Independent correctIndependent(Value.Immutable immutableOfSource,
                                                 Value.Independent independent,
                                                 ParameterizedType targetType,
                                                 HiddenContentSelector hiddenContentSelectorOfTarget,
                                                 Map<Indices, HiddenContentSelector.IndicesAndType> hctMethodToHcsTarget) {
        // immutableOfSource is not recursively immutable, independent is not fully independent
        // remaining values immutable: mutable, immutable HC
        // remaining values independent: dependent, independent hc
        if (independent.isDependent()) {
            if (immutableOfSource.isAtLeastImmutableHC()) {
                return ValueImpl.IndependentImpl.INDEPENDENT_HC;
            }

            // if all types of the hcs are independent HC, then we can upgrade
            Map<Integer, Indices> selectorSet = hiddenContentSelectorOfTarget.getMap();
            boolean allIndependentHC = true;
            assert hctMethodToHcsTarget != null;
            for (Map.Entry<Indices, HiddenContentSelector.IndicesAndType> entry : hctMethodToHcsTarget.entrySet()) {
                if (selectorSet.containsValue(entry.getKey())) {
                    if (hiddenContentSelectorOfTarget.hiddenContentTypes().isExtensible(entry.getKey().single()) != null) {
                        return ValueImpl.IndependentImpl.INDEPENDENT_HC;
                    }
                    Value.Immutable immutablePt = analysisHelper.typeImmutable(currentPrimaryType, entry.getValue().type());
                    if (immutablePt == null) return null;
                    if (!immutablePt.isAtLeastImmutableHC()) {
                        allIndependentHC = false;
                        break;
                    }
                }
            }
            if (allIndependentHC) {
                return ValueImpl.IndependentImpl.INDEPENDENT_HC;
            }

        }
        if (independent.isIndependentHc()) {
            if (hiddenContentSelectorOfTarget.isOnlyAll()) {
                Value.Immutable immutablePt = analysisHelper.typeImmutable(currentPrimaryType, targetType);
                if (immutablePt == null) return null; // no value yet
                if (immutablePt.isImmutable()) {
                    return ValueImpl.IndependentImpl.INDEPENDENT;
                }
            } else {
                assert !hiddenContentSelectorOfTarget.isNone();
            }
        }
        return independent;
    }

    public interface CreateLink {
        void link(Variable from, Variable to, LV lv);
    }

    public void crossLink(LinkedVariables linkedVariablesOfObject,
                          LinkedVariables linkedVariablesOfObjectFromParams,
                          LinkEvaluation.Builder link) {
        linkedVariablesOfObject.stream().forEach(e ->
                linkedVariablesOfObjectFromParams.stream().forEach(e2 -> {
                    Variable from = e.getKey();
                    Variable to = e2.getKey();
                    LV fromLv = e.getValue();
                    LV toLv = e2.getValue();
                    LV lv = follow(fromLv, toLv);
                    if (lv != null) {
                        link.merge(from, LinkedVariablesImpl.of(to, lv));
                    }
                })
        );
    }

    public static LV follow(LV fromLv, LV toLv) {
        if (fromLv.isDelayed() || toLv.isDelayed()) {
            return LVImpl.LINK_DELAYED;
        }
        boolean fromLvHaveLinks = fromLv.haveLinks();
        boolean toLvHaveLinks = toLv.haveLinks();
        if (!fromLvHaveLinks && !toLvHaveLinks) {
            return fromLv.max(toLv); // -0- and -1-, -1- and -2-
        }
        if (fromLv.isStaticallyAssignedOrAssigned()) {
            return toLv; // -0- 0-4-1
        }
        if (toLv.isStaticallyAssignedOrAssigned()) {
            return fromLv; // 1-2-1 -1-
        }
        if (fromLvHaveLinks && toLvHaveLinks) {
            boolean fromLvMineIsAll = fromLv.mineIsAll();
            boolean toLvMineIsAll = toLv.mineIsAll();
            if (fromLvMineIsAll && !toLvMineIsAll) {
                return fromLv.reverse();
            }
            if (toLvMineIsAll && !fromLvMineIsAll) {
                return toLv;
            } else if (toLvMineIsAll) {
                return LVImpl.createHC(fromLv.links().theirsToTheirs(toLv.links()));
            }
            boolean fromLvTheirsIsAll = fromLv.theirsIsAll();
            boolean toLvTheirsIsAll = toLv.theirsIsAll();
            if (fromLvTheirsIsAll && !toLvTheirsIsAll) {
                return LVImpl.createHC(toLv.links().theirsToTheirs(fromLv.links()));
            }
            if (toLvTheirsIsAll && fromLvTheirsIsAll) {
                return null;
            }
            return LVImpl.createHC(fromLv.links().mineToTheirs(toLv.links()));
        }
        if (fromLv.isDependent()) {
            assert !fromLvHaveLinks && toLv.isCommonHC();
            return null;
        }
        if (toLv.isDependent()) {
            assert !toLvHaveLinks && fromLv.isCommonHC();
            return null;
        }
        throw new UnsupportedOperationException("?");
    }

    /*
    by-pass the method for field access; used in VariableExpression
     */
    public static LinkedVariables forFieldAccess(Runtime runtime,
                                                 GenericsHelper genericsHelper,
                                                 AnalysisHelper analysisHelper,
                                                 LinkedVariables linkedVariables,
                                                 MethodInfo currentMethod,
                                                 ParameterizedType fieldType,
                                                 ParameterizedType formalFieldType,
                                                 ParameterizedType scopeType) {
        if (linkedVariables.isDelayed()) {
            // the 'changeToDelay' call is mostly unnecessary, unless there's a direct :0 link to the scope
            return linkedVariables.changeToDelay();
        }
        HiddenContentTypes hct = scopeType.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        HiddenContentSelector hcsTarget = HiddenContentSelector.selectAll(hct, formalFieldType);
        if (hcsTarget.isOnlyAll() && formalFieldType.isTypeParameter() && !fieldType.parameters().isEmpty()) {
            int index = hcsTarget.getMap().keySet().stream().findFirst().orElseThrow();
            ParameterizedType fft = fieldType.typeInfo().asParameterizedType(runtime);
            LinkedVariables recursive = forFieldAccess(runtime, genericsHelper, analysisHelper, linkedVariables,
                    currentMethod, fieldType, fft, fieldType);
            return recursive.map(lv -> lv.prefixTheirs(index));
        }
        ParameterizedType formalScopeType = scopeType.typeInfo().asParameterizedType(runtime);
        HiddenContentSelector hcsSource = HiddenContentSelector.selectAll(hct, formalScopeType);
        LinkHelper lh = new LinkHelper(runtime, genericsHelper, analysisHelper, currentMethod, hct, hcsSource);
        Value.Immutable immutable = analysisHelper.typeImmutable(currentMethod.typeInfo(), fieldType);
        Value.Independent independent = immutable.toCorrespondingIndependent();
        return lh.linkedVariables(hcsSource, scopeType, formalScopeType, hcsSource, linkedVariables, false,
                independent, fieldType, formalFieldType, hcsTarget, false);
    }

    public static Links factoryMethodLinks(HiddenContentTypes hct,
                                           HiddenContentSelector hcsFrom,
                                           HiddenContentSelector hcsTo,
                                           boolean isDependent) {
        if (hcsFrom.isNone() || hcsTo.isNone()) return null;
        if (hcsFrom.isOnlyAll() && hcsTo.isOnlyAll()) return null;
        Map<Indices, Link> linkMap = new HashMap<>();
        Map<Integer, Integer> correct = hct.mapMethodToTypeIndices(null);

        for (Map.Entry<Integer, Indices> entry : hcsFrom.getMap().entrySet()) {
            Indices to = hcsTo.getMap().get(entry.getKey());
            if (to != null) {
                Indices correctedTo = to.map(i -> correct.getOrDefault(i, i));
                linkMap.put(correctedTo, new LinkImpl(entry.getValue(), false));
            } // FIXME: else to be reviewed
        }

        if (linkMap.isEmpty()) return null;
        return new LinksImpl(linkMap);
    }

}

