package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.LINK_DEPENDENT;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.LINK_INDEPENDENT;
import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT_HC;

class LinkHelperCore extends CommonLinkHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(LinkHelperCore.class);
    private final MethodInfo methodInfo;

    LinkHelperCore(MethodInfo methodInfo,
                   Runtime runtime,
                   AnalysisHelper analysisHelper,
                   GenericsHelper genericsHelper) {
        super(methodInfo.primaryType(), runtime, analysisHelper, genericsHelper);
        this.methodInfo = methodInfo;
    }

    /**
     * Important: this method does not deal with hidden content specific to the method, because it has been designed
     * to connect the object to the return value, as called from <code>linkedVariablesMethodCallObjectToReturnType</code>.
     * Calls originating from <code>linksInvolvingParameters</code> must take this into account.
     * <p>
     * IMPORTANT: this method reports the linked values of the TARGET, starting off with the linked variables of the SOURCE.
     *
     * @param sourceTypeIn                  must be type of object or parameterExpression, return type, non-evaluated
     * @param methodSourceType              the method declaration's type of the source
     * @param hiddenContentSelectorOfSource with respect to the method's HCT and methodSourceType
     * @param sourceLvs                     linked variables of the source
     * @param sourceIsVarArgs               allow for a correction of array -> element
     * @param transferIndependent           the transfer mode (dependent, independent HC, independent)
     * @param targetTypeIn                  must be type of object or parameterExpression, return type, non-evaluated
     * @param methodTargetType              the method declaration's type of the target
     * @param hiddenContentSelectorOfTarget with respect to the method's HCT and methodTargetType
     * @param reverse                       reverse the link, because we're reversing source and target, because we
     *                                      only deal with *->0 in this method, never 0->*,
     * @param indexOfDirectlyLinkedField    helper info to build the modification area part of the link
     * @return the linked values of the target.
     */
    LinkedVariables linkedVariables(ParameterizedType sourceTypeIn,
                                    ParameterizedType methodSourceType,
                                    HiddenContentSelector hiddenContentSelectorOfSource,
                                    LinkedVariables sourceLvs,
                                    boolean sourceIsVarArgs,
                                    Value.Independent transferIndependent,
                                    ParameterizedType targetTypeIn,
                                    ParameterizedType methodTargetType,
                                    HiddenContentSelector hiddenContentSelectorOfTarget,
                                    boolean reverse,
                                    Integer indexOfDirectlyLinkedField) {
        assert hiddenContentSelectorOfSource.hiddenContentTypes() == hiddenContentSelectorOfTarget.hiddenContentTypes();
        assert sourceTypeIn != null;
        assert hiddenContentSelectorOfSource.compatibleWith(runtime, methodSourceType);
        assert sourceLvs.compatibleWith(hiddenContentSelectorOfSource);
        assert hiddenContentSelectorOfTarget.compatibleWith(runtime, methodTargetType);

        ParameterizedType sourceType = ensureTypeParameters(sourceTypeIn); // Pair -> Pair<Object, Object>
        assert targetTypeIn != null;
        ParameterizedType targetType = ensureTypeParameters(targetTypeIn);
        // RULE 1: no linking when the source is not linked or there is no transfer
        if (sourceLvs.isEmpty() || transferIndependent.isIndependent()) {
            return LinkedVariablesImpl.EMPTY;
        }
        if (hiddenContentSelectorOfTarget.isNone() && transferIndependent.isIndependentHc()) {
            throw new UnsupportedOperationException(
                    "Impossible to have no knowledge of hidden content, and INDEPENDENT_HC: " + methodInfo);
        }
        Value.Immutable immutableOfSource = analysisHelper.typeImmutable(currentPrimaryType, sourceType);

        // RULE 3: immutable -> no link
        if (immutableOfSource.isImmutable()) {
            /*
             if the result type immutable because of a choice in type parameters, methodIndependent will return
             INDEPENDENT_HC, but the concrete type is deeply immutable
             */
            return LinkedVariablesImpl.EMPTY;
        }

        LinkedVariables lvFunctional = lvFunctional(transferIndependent, hiddenContentSelectorOfTarget, reverse,
                targetType, sourceType, immutableOfSource, sourceLvs, sourceIsVarArgs, indexOfDirectlyLinkedField);
        if (lvFunctional != null) return lvFunctional;

        Supplier<Map<Indices, HiddenContentSelector.IndicesAndType>> hctMethodToHctSourceSupplier =
                () -> hiddenContentSelectorOfSource.translateHcs(runtime, genericsHelper, methodSourceType, sourceType,
                        sourceIsVarArgs);

        Value.Immutable immutableOfFormalSource;
        if (sourceType.typeInfo() != null) {
            ParameterizedType formalSource = sourceType.typeInfo().asParameterizedType();
            immutableOfFormalSource = analysisHelper.typeImmutable(currentPrimaryType, formalSource);
            if (immutableOfFormalSource.isImmutable()) {
                return LinkedVariablesImpl.EMPTY;
            }
        } else {
            immutableOfFormalSource = immutableOfSource;
        }
        Value.Immutable immutable1 = analysisHelper.typeImmutable(currentPrimaryType, targetType);
        if (immutable1.isImmutable()) return LinkedVariablesImpl.EMPTY;
        Value.Immutable immutable2 = analysisHelper.typeImmutable(currentPrimaryType, methodTargetType);
        if (immutable2.isImmutable()) return LinkedVariablesImpl.EMPTY;

        return continueLinkedVariables(
                hiddenContentSelectorOfSource,
                sourceLvs, sourceIsVarArgs, transferIndependent, immutableOfFormalSource, targetType,
                methodTargetType, hiddenContentSelectorOfTarget, hctMethodToHctSourceSupplier, reverse,
                indexOfDirectlyLinkedField);
    }

    private LinkedVariables lvFunctional(Value.Independent transferIndependent,
                                         HiddenContentSelector hiddenContentSelectorOfTarget,
                                         boolean reverse,
                                         ParameterizedType targetType,
                                         ParameterizedType sourceType,
                                         Value.Immutable immutableOfSource,
                                         LinkedVariables sourceLvs,
                                         boolean sourceIsVarArgs,
                                         Integer indexOfDirectlyLinkedField) {

        // special code block for functional interfaces with both return value and parameters (i.e. variants
        // on Function<T,R>, BiFunction<T,S,R> etc. Not Consumers (no return value) nor Suppliers (no parameters))
        if (!hiddenContentSelectorOfTarget.isOnlyAll() || !transferIndependent.isIndependentHc()) {
            return null;
        }

        HiddenContentTypes hctContext = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        HiddenContentSelector hcsTargetContext = HiddenContentSelector.selectAll(hctContext, targetType);
        HiddenContentSelector hcsSourceContext = HiddenContentSelector.selectAll(hctContext, sourceType);
        Set<Integer> set = new HashSet<>(hcsSourceContext.set());
        set.retainAll(hcsTargetContext.set());
        if (set.isEmpty()) {
            return null;
        }

        List<LinkedVariables> lvsList = new ArrayList<>();
        for (int index : set) {
            LOGGER.debug("Linked variables functional: do {} in hcs source {} overlapping with hcsTarget {}: {}", index,
                    hcsSourceContext, hcsTargetContext, set);
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

                Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, targetType);
                if (!immutable.isImmutable()) {
                    LinkedVariables lvs = continueLinkedVariables(newHiddenContentSelectorOfSource,
                            sourceLvs, sourceIsVarArgs, transferIndependent, immutableOfSource,
                            newTargetType, newTargetType, newHcsTarget, hctMethodToHctSourceSupplier,
                            reverse, indexOfDirectlyLinkedField);
                    lvsList.add(lvs);
                }
            }
        }
        if (!lvsList.isEmpty()) {
            return lvsList.stream().reduce(LinkedVariablesImpl.EMPTY, LinkedVariables::merge);
        }
        return null;
    }

    private LinkedVariables continueLinkedVariables(HiddenContentSelector hiddenContentSelectorOfSource,
                                                    LinkedVariables sourceLvs,
                                                    boolean sourceIsVarArgs,
                                                    Value.Independent transferIndependent,
                                                    Value.Immutable immutableOfFormalSource,
                                                    ParameterizedType targetType,
                                                    ParameterizedType methodTargetType,
                                                    HiddenContentSelector hiddenContentSelectorOfTarget,
                                                    Supplier<Map<Indices, HiddenContentSelector.IndicesAndType>> hctMethodToHctSourceSupplier,
                                                    boolean reverse,
                                                    Integer indexOfDirectlyLinkedField) {
        Map<Indices, HiddenContentSelector.IndicesAndType> hctMethodToHcsTarget = hiddenContentSelectorOfTarget
                .translateHcs(runtime, genericsHelper, methodTargetType, targetType, sourceIsVarArgs);
        LOGGER.debug("Linked variables: hcs method to hcs target: {}", hctMethodToHcsTarget);
        LOGGER.debug("Linked variables: hcs source: {}, target: {}", hiddenContentSelectorOfSource,
                hiddenContentSelectorOfTarget);
        Value.Independent correctedIndependent = correctIndependent(immutableOfFormalSource, transferIndependent,
                targetType, hiddenContentSelectorOfTarget, hctMethodToHcsTarget);

        if (correctedIndependent.isIndependent()) {
            return LinkedVariablesImpl.EMPTY;
        }

        Map<Indices, HiddenContentSelector.IndicesAndType> hctMethodToHctSource = hctMethodToHctSourceSupplier.get();
        Map<Variable, LV> newLinked = new HashMap<>();

        for (Map.Entry<Variable, LV> e : sourceLvs) {
            LOGGER.debug("Linked variables: do {} in sourceLvs {}", e, sourceLvs);

            ParameterizedType pt = e.getKey().parameterizedType();
            // for the purpose of this algorithm, unbound type parameters are HC
            Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, pt);
            LV lv = e.getValue();
            assert lv.lt(LINK_INDEPENDENT);

            assert !immutable.isImmutable();

            if (hiddenContentSelectorOfTarget.isNone()) {
                LV prev = newLinked.put(e.getKey(), LINK_DEPENDENT);
                assert prev == null;
            } else {
                // from mine==target to theirs==source
                Map<Indices, Link> linkMap = new HashMap<>();

                Boolean correctForVarargsMutable = null;

                Set<Map.Entry<Integer, Indices>> entrySet = hiddenContentSelectorOfTarget.getMap().entrySet();
                for (Map.Entry<Integer, Indices> entry : entrySet) {
                    LOGGER.debug("Linked variables: do {} in entry set {}", entry, entrySet);
                    Indices indicesInTargetWrtMethod = entry.getValue();

                    HiddenContentSelector.IndicesAndType targetAndType = hctMethodToHcsTarget.get(indicesInTargetWrtMethod);
                    if (targetAndType == null) {
                        continue; // see TestConsumer
                    }
                    ParameterizedType type = targetAndType.type();
                    assert type != null;
                    Indices targetIndices = targetAndType.indices();

                    Value.Immutable typeImmutable = analysisHelper.typeImmutable(currentPrimaryType, type);
                    if (typeImmutable.isImmutable()) {
                        continue;
                    }
                    boolean mutable = typeImmutable.isMutable();
                    if (sourceIsVarArgs) {
                        // we're in a varargs situation: the first element is the type itself
                        correctForVarargsMutable = mutable;
                    }

                    /*
                    example: HCT of List (0=E). HCS source = 0(HC type E)->0(index in List's parameters)
                    entry:0->0
                     */
                    Indices indicesInSourceWrtMethod = hiddenContentSelectorOfSource.getMap().get(entry.getKey());
                    if (indicesInSourceWrtMethod == null) {
                        continue;
                    }
                    assert hctMethodToHctSource != null;
                    HiddenContentSelector.IndicesAndType indicesAndType = hctMethodToHctSource.get(indicesInSourceWrtMethod);
                    if (indicesAndType == null) {
                        continue;
                    }
                    Indices indicesInSourceWrtType = indicesAndType.indices();
                    if (indicesInSourceWrtType == null) {
                        continue; // see TestVarargs,3
                    }

                    // IMPROVE this feels rather arbitrary, see Linking_0P.reverse4 yet the 2nd clause seems needed for 1A.f10()
                    Indices indicesInTargetWrtType = (lv.theirsIsAll()
                                                      && entrySet.size() < hiddenContentSelectorOfTarget.getMap().size()
                                                      && reverse) ? ALL_INDICES : targetIndices;
                    Indices correctedIndicesInTargetWrtType;
                    if (correctForVarargsMutable != null) {
                        correctedIndicesInTargetWrtType = ALL_INDICES;
                    } else {
                        correctedIndicesInTargetWrtType = indicesInTargetWrtType;
                    }
                    assert correctedIndicesInTargetWrtType != null;
                    // see TestLinkToReturnValueMap,1(copy8) for an example of merging
                    if (!(correctedIndicesInTargetWrtType.isAll() && indicesInSourceWrtType.isAll())) {
                        linkMap.merge(correctedIndicesInTargetWrtType, new LinkImpl(indicesInSourceWrtType, mutable),
                                Link::merge);
                    }
                }

                boolean createDependentLink = immutable.isMutable() && isDependent(transferIndependent,
                        correctedIndependent, immutableOfFormalSource, lv);
                LV theLink;
                if (createDependentLink) {
                    if (linkMap.isEmpty()) {
                        theLink = LINK_DEPENDENT;
                    } else {
                        Links links = buildLinks(hiddenContentSelectorOfTarget, immutable, linkMap,
                                indexOfDirectlyLinkedField);
                        theLink = reverse ? LVImpl.createDependent(links.reverse()) : LVImpl.createDependent(links);
                    }
                } else if (!linkMap.isEmpty()) {
                    Links links = new LinksImpl(Map.copyOf(linkMap));
                    theLink = reverse ? LVImpl.createHC(links.reverse()) : LVImpl.createHC(links);
                } else {
                    theLink = null;
                }
                if (theLink != null) {
                    LV prev = newLinked.put(e.getKey(), theLink);
                    assert prev == null;
                }
            }
        }
        return LinkedVariablesImpl.of(newLinked);
    }

    /*
    special code to add the modificationArea objects in case of a Getter
     */
    private Links buildLinks(HiddenContentSelector hiddenContentSelectorOfTarget,
                             Value.Immutable immutable,
                             Map<Indices, Link> linkMap,
                             Integer indexOfDirectlyLinkedField) {
        Indices modificationAreaSource;
        Indices modificationAreaTarget;
        if (immutable.isAtLeastImmutableHC()) {
            modificationAreaSource = IndicesImpl.NO_MODIFICATION_INDICES;
            modificationAreaTarget = IndicesImpl.NO_MODIFICATION_INDICES;
        } else {
            modificationAreaSource = ALL_INDICES;
            if (hiddenContentSelectorOfTarget.containsAll() && indexOfDirectlyLinkedField != null) {
                modificationAreaTarget = new IndicesImpl(indexOfDirectlyLinkedField);
            } else {
                modificationAreaTarget = ALL_INDICES;
            }
        }
        return new LinksImpl(Map.copyOf(linkMap), modificationAreaSource, modificationAreaTarget);
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
                return INDEPENDENT_HC;
            }

            // if all types of the hcs are independent HC, then we can upgrade
            Map<Integer, Indices> selectorSet = hiddenContentSelectorOfTarget.getMap();
            boolean allIndependentHC = true;
            assert hctMethodToHcsTarget != null;
            for (Map.Entry<Indices, HiddenContentSelector.IndicesAndType> entry : hctMethodToHcsTarget.entrySet()) {
                if (selectorSet.containsValue(entry.getKey())) {
                    if (hiddenContentSelectorOfTarget.hiddenContentTypes().isExtensible(entry.getKey().single()) != null) {
                        return INDEPENDENT_HC;
                    }
                    Value.Immutable immutablePt = analysisHelper.typeImmutable(currentPrimaryType, entry.getValue().type());
                    if (!immutablePt.isAtLeastImmutableHC()) {
                        allIndependentHC = false;
                        break;
                    }
                }
            }
            if (allIndependentHC) {
                return INDEPENDENT_HC;
            }

        }
        if (independent.isIndependentHc()) {
            if (hiddenContentSelectorOfTarget.isOnlyAll()) {
                Value.Immutable immutablePt = analysisHelper.typeImmutable(currentPrimaryType, targetType);
                if (immutablePt.isImmutable()) {
                    return INDEPENDENT;
                }
            } else {
                assert !hiddenContentSelectorOfTarget.isNone();
            }
        }
        return independent;
    }
}
