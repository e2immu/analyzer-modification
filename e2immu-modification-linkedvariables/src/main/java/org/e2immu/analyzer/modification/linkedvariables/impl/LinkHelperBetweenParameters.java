package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.List;
import java.util.Map;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.NONE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT_HC;

class LinkHelperBetweenParameters {
    private final LinkHelperCore linkHelperObjectToReturnValue;
    private final LinkHelperParameter linkHelperParameter;

    LinkHelperBetweenParameters(LinkHelperCore linkHelperObjectToReturnValue,
                                LinkHelperParameter linkHelperParameter) {
        this.linkHelperObjectToReturnValue = linkHelperObjectToReturnValue;
        this.linkHelperParameter = linkHelperParameter;
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
                        null, // FIXME
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
}
