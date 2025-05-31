package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.LINK_DEPENDENT;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.createHC;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.NONE;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT_HC;

class LinkHelperBetweenParameters {
    private final LinkHelperCore linkHelperObjectToReturnValue;
    private final LinkHelperParameter linkHelperParameter;
    private final Runtime runtime;

    LinkHelperBetweenParameters(Runtime runtime,
                                LinkHelperCore linkHelperObjectToReturnValue,
                                LinkHelperParameter linkHelperParameter) {
        this.runtime = runtime;
        this.linkHelperObjectToReturnValue = linkHelperObjectToReturnValue;
        this.linkHelperParameter = linkHelperParameter;
    }

    void doCrossLinkOfParameter(EvaluationResult.Builder builder,
                                        MethodInfo methodInfo,
                                        List<Expression> parameterExpressions,
                                        List<EvaluationResult> linkedVariables,
                                        ParameterInfo pi,
                                        LinkedVariables lv) {
        boolean sourceIsVarArgs = pi.isVarArgs();
        assert !sourceIsVarArgs : "Varargs must always be a target";
        HiddenContentSelector hcsSource = methodInfo.parameters().get(pi.index()).analysis()
                .getOrDefault(HCS_PARAMETER, NONE);
        ParameterizedType sourceType = parameterExpressions.get(pi.index()).parameterizedType();
        LinkedVariables sourceLvs = linkHelperParameter.linkedVariablesOfParameter(pi.parameterizedType(),
                parameterExpressions.get(pi.index()).parameterizedType(),
                linkedVariables.get(pi.index()).linkedVariables(), hcsSource, false);
        lv.stream().forEach(e -> {
            ParameterInfo target = (ParameterInfo) e.getKey();
            doCrossLinkFromTo(builder, methodInfo, parameterExpressions, linkedVariables,
                    pi, e, target, hcsSource, sourceType, sourceLvs);
        });
    }

    void doCrossLinkFromTo(EvaluationResult.Builder builder,
                           MethodInfo methodInfo,
                           List<Expression> parameterExpressions,
                           List<EvaluationResult> linkedVariables,
                           ParameterInfo pi,
                           Map.Entry<Variable, LV> e,
                           ParameterInfo target,
                           HiddenContentSelector hcsSource,
                           ParameterizedType sourceType,
                           LinkedVariables sourceLvs) {
        boolean targetIsVarArgs = target.isVarArgs();
        if (!targetIsVarArgs || linkedVariables.size() > target.index()) {

            LV level = e.getValue();

            for (int i = target.index(); i < linkedVariables.size(); i++) {
                ParameterizedType targetType = parameterExpressions.get(target.index()).parameterizedType();
                HiddenContentSelector hcsTarget = methodInfo.parameters().get(target.index()).analysis()
                        .getOrDefault(HCS_PARAMETER, NONE);

                LinkedVariables targetLinkedVariables = linkHelperParameter.linkedVariablesOfParameter(target.parameterizedType(),
                        parameterExpressions.get(i).parameterizedType(),
                        linkedVariables.get(i).linkedVariables(), hcsSource, pi.isVarArgs());

                Value.Independent independentDv = level.isCommonHC() ? INDEPENDENT_HC
                        : DEPENDENT;
                LinkedVariables mergedLvs = linkHelperObjectToReturnValue.linkedVariables(targetType,
                        target.parameterizedType(), hcsSource,
                        targetLinkedVariables, targetIsVarArgs, independentDv,
                        sourceType, pi.parameterizedType(),
                        hcsTarget, targetIsVarArgs, null); // IMPROVE indexOfDirectlyLinkedField??
                crossLink(sourceLvs, mergedLvs, builder);
            }
        } // else: no value... empty varargs
    }


    void crossLink(LinkedVariables linkedVariablesOfObject,
                   LinkedVariables linkedVariablesOfObjectFromParams,
                   EvaluationResult.Builder link) {
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

    static LV follow(LV fromLv, LV toLv) {
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
            if (toLvMineIsAll) {  //X-Y *-Z
                if (!fromLvMineIsAll) return toLv;
                // *-Y *-Z
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
        if (fromLv.isDependent() == toLv.isDependent()) {
            return fromLv;
        }
        return null;
    }

    Map<ParameterInfo, LinkedVariables> translateLinksToParameters(MethodInfo methodInfo) {
        Map<ParameterInfo, Map<Variable, LV>> res = new HashMap<>();
        for (ParameterInfo pi : methodInfo.parameters()) {
            Value.Independent independent = pi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER,
                    DEPENDENT);
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
                    LV prev = lvMap.put(target, lv);
                    assert prev == null;
                }
            }
            if (!lvMap.isEmpty()) res.put(pi, lvMap);
        }
        return res.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> LinkedVariablesImpl.of(e.getValue())));
    }


    Links linkAllSameType(ParameterizedType parameterizedType) {
        TypeInfo typeInfo = parameterizedType.bestTypeInfo();
        if (typeInfo == null) return LinksImpl.NO_LINKS;
        HiddenContentTypes hct = typeInfo.analysis().getOrCreate(HIDDEN_CONTENT_TYPES,
                () -> new ComputeHiddenContent(runtime).compute(typeInfo));
        if (hct.hasHiddenContent()) {
            Map<Indices, Link> map = new HashMap<>();
            for (int i = 0; i < hct.size(); i++) {
                // not mutable, because hidden content
                // from i to i, because we have a -1- relation, so the type must be the same
                map.put(new IndicesImpl(i), new LinkImpl(new IndicesImpl(i), false));
            }
            return new LinksImpl(Map.copyOf(map));
        }
        return LinksImpl.NO_LINKS;
    }

}
