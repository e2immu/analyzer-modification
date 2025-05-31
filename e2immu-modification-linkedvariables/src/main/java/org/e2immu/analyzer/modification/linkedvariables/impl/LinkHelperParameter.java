package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.inspection.api.parser.GenericsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.createDependent;
import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;

public class LinkHelperParameter extends CommonLinkHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkHelperParameter.class);

    protected LinkHelperParameter(TypeInfo currentPrimaryType,
                                  Runtime runtime,
                                  AnalysisHelper analysisHelper,
                                  GenericsHelper genericsHelper) {
        super(currentPrimaryType, runtime, analysisHelper, genericsHelper);
    }


    /*
    Linked variables of parameter.
    There are 2 types involved:
      1. type in declaration = formalParameterType
      2. type in method call = parameterExpression.parameterizedType() == concreteParameterType
    The hcsSource
    In the case of links between parameters, the "source" becomes the object: we compute links from the object
    to the variables that the argument is linked to.


    This is a prep method, where we re-compute the base of the argument's link to the hidden content types of the method.
    The minimum link level is LINK_DEPENDENT.
    The direction of the links is from the method to the variables linked to the argument, correcting for the
    concrete parameter type.
    All Indices on the 'from'-side are single HCT indices.

    If the formalParameterType is a type parameter, we'll have an index of hc wrt the method:
    If the argument is a variable, the link is typically v:0; we'll link 'index of hc' -2-> ALL if the concrete type
      allows so.
    If the argument is dependently linked to a variable, e.g. v.subList(..), then we'll still link DEPENDENT, and
      add the 'index of hc' -2-> ALL.
    If the argument is HC linked to a variable, e.g. new ArrayList<>(..), we'll link 'index of hc' -4-> ALL.

    If formalParameterType is not a type parameter, we'll compute a translation map which expresses
    the hidden content of the method, expressed in formalParameterType, to indices in the parameter type.

    IMPORTANT: these links are meant to be combined with either links to object, or links to other parameters.
    This code is different from the normal linkedVariables(...) method.

     */
    LinkedVariables linkedVariablesOfParameter(ParameterizedType formalParameterType,
                                               ParameterizedType concreteParameterType,
                                               LinkedVariables linkedVariablesOfParameter,
                                               HiddenContentSelector hcsSource,
                                               boolean allowVarargs) {
        LinkedVariables res = linkedVariablesOfParameter(hcsSource.hiddenContentTypes(),
                formalParameterType, concreteParameterType,
                linkedVariablesOfParameter, hcsSource, allowVarargs);
        LOGGER.debug("LV of parameter {}; {}; {}; {} = {}", formalParameterType, concreteParameterType,
                linkedVariablesOfParameter, hcsSource, res);
        return res;
    }

    // recursive!
    private LinkedVariables linkedVariablesOfParameter(HiddenContentTypes hiddenContentTypes,
                                                       ParameterizedType formalParameterType,
                                                       ParameterizedType concreteParameterTypeIn,
                                                       LinkedVariables linkedVariablesOfParameter,
                                                       HiddenContentSelector hcsSource,
                                                       boolean allowVarargs) {
        ParameterizedType concreteParameterType = ensureTypeParameters(concreteParameterTypeIn);
        Value.Immutable immutable = analysisHelper.typeImmutable(currentPrimaryType, concreteParameterType);
        if (immutable != null && immutable.isImmutable()) {
            return LinkedVariablesImpl.EMPTY;
        }

        Integer index = hiddenContentTypes.indexOfOrNull(formalParameterType);
        if (index != null && formalParameterType.arrays() > 0
            && concreteParameterType.arrays() == formalParameterType.arrays()) {
            // see TestRecursiveCall
            return linkedVariablesOfParameter(hiddenContentTypes, formalParameterType.copyWithOneFewerArrays(),
                    concreteParameterType.copyWithOneFewerArrays(), linkedVariablesOfParameter, hcsSource, allowVarargs);
        }
        if (index != null && formalParameterType.parameters().isEmpty()) {
            if (!concreteParameterType.parameters().isEmpty()) {
                // recursion at the level of the type parameters
                HiddenContentTypes newHiddenContentTypes = concreteParameterType.typeInfo().analysis()
                        .getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                ParameterizedType newParameterMethodType = concreteParameterType.typeInfo().asParameterizedType()
                        // add the arrays, e.g. because of Class<?>[]
                        .copyWithArrays(concreteParameterType.arrays());
                HiddenContentSelector newHcsSource = HiddenContentSelector.selectAll(newHiddenContentTypes,
                        newParameterMethodType);
                LinkedVariables recursive = linkedVariablesOfParameter(newHiddenContentTypes,
                        newParameterMethodType, concreteParameterType, linkedVariablesOfParameter, newHcsSource,
                        allowVarargs);
                return recursive.map(lv -> lv.prefixMine(index));
            }
        }

        // the current formal type is not one of the hidden content types
        Map<Indices, HiddenContentSelector.IndicesAndType> targetData = hcsSource
                .translateHcs(runtime, genericsHelper, formalParameterType, concreteParameterType, allowVarargs);
        Map<Variable, LV> map = new HashMap<>();
        linkedVariablesOfParameter.stream().forEach(e -> {
            Value.Immutable immutableOfKey = analysisHelper.typeImmutable(currentPrimaryType, e.getKey().parameterizedType());
            if (!immutableOfKey.isImmutable()) {
                LV newLv = lvOfParameter(e, targetData, concreteParameterType);
                if (newLv != null) {
                    map.put(e.getKey(), newLv);
                }
            }
        });
        return LinkedVariablesImpl.of(map);
    }

    private LV lvOfParameter(Map.Entry<Variable, LV> e,
                             Map<Indices, HiddenContentSelector.IndicesAndType> targetData,
                             ParameterizedType concreteParameterType) {
        LV lv = e.getValue();
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
                if (immutable.isImmutable()) {
                    continue;
                }
                boolean mutable = immutable.isMutable();
                assert iInHctTarget != null;
                if (!(iInHctSource.isAll() && iInHctTarget.isAll())) {
                    Link prev = linkMap.put(iInHctSource, new LinkImpl(iInHctTarget, mutable));
                    assert prev == null;
                }
            }
            if (!linkMap.isEmpty()) {
                Links links = new LinksImpl(Map.copyOf(linkMap));
                boolean independentHc = lv.isCommonHC();
                return independentHc ? LVImpl.createHC(links) : LVImpl.createDependent(links);
            }
        }
        return LVImpl.LINK_DEPENDENT;
        // org.e2immu.analyzer.modification.linkedvariables.staticvalues.TestStaticValuesRecord.test4
        // createDependent(linkAllSameType(concreteParameterType));
        // FIXME we should link to the indices of the HCSparam, not the existing ones
    }



}
