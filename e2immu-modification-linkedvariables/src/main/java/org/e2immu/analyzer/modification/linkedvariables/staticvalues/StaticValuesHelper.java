package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.element.Source;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.support.Either;
import org.e2immu.util.internal.util.ListUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StaticValuesHelper(Runtime runtime) {


    /*
    If we have an assignment of E=3 to a.b, we add an assignment of this.b=3 to a.
    If we have an assignment of E=3 to a.b.c, we first add an assignment of this.c=3 to a.b,
    then we add an assignment of this.b.c=3 to a.

    If we have an assignment of E=3 to a[0].b, then we add an assignment of this[0].b to a

    If we have an assignment of E=3 to a.b[0], then we don't add anything. We focus on field references.
     */
    public void recursivelyAddAssignmentsAtScopeLevel(Map<Variable, List<StaticValues>> staticValuesMap,
                                                      Source source,
                                                      VariableData variableData,
                                                      String statementIndex) {
        Map<Variable, List<StaticValues>> append = new HashMap<>();
        for (Map.Entry<Variable, List<StaticValues>> entry : staticValuesMap.entrySet()) {
            if (entry.getKey() instanceof FieldReference fr) {
                recursivelyAdd1(append, fr, source, entry.getValue(), variableData, statementIndex);
            }
        }
        append.forEach((v, list) -> staticValuesMap.merge(v, list, ListUtil::immutableConcat));
    }

    void recursivelyAdd1(Map<Variable, List<StaticValues>> append,
                         FieldReference fr,
                         Source source,
                         List<StaticValues> values,
                         VariableData variableData,
                         String statementIndex) {
        Expression svScope = runtime.newVariableExpressionBuilder()
                .setVariable(runtime.newThis(fr.fieldInfo().typeInfo().asParameterizedType()))
                .setSource(source)
                .build();
        recursivelyAdd(append, fr.scope(), fr.scope(), fr, svScope, fr.fieldInfo(), values, variableData, statementIndex);
    }

    // we have assignments to 'a.b'; we now find that 'a' is a variable, so we add modified assignments to 'a'
    private void recursivelyAdd(Map<Variable, List<StaticValues>> append,
                        Expression newScope,
                        Expression fullScope,
                        FieldReference fr,
                        Expression svScope,
                        FieldInfo svFieldInfo,
                        List<StaticValues> values,
                        VariableData variableData,
                        String statementIndex) {
        if (newScope instanceof VariableExpression ve && !(ve.variable() instanceof This)) {
            List<StaticValues> newList = values.stream().map(sv -> {
                Expression expression;
                DependentVariable dependentVariable;
                if (sv.expression() != null && sv.values().isEmpty()) {
                    expression = sv.expression();
                    dependentVariable = null;
                } else if (!sv.values().isEmpty()) {
                    Map.Entry<Variable, Expression> e = sv.values().entrySet().stream().findFirst().orElseThrow();
                    expression = e.getValue();
                    dependentVariable = e.getKey() instanceof DependentVariable dv ? dv : null;
                } else {
                    return null; // empty sv
                }
                Map<Variable, Expression> newMap;
                if (dependentVariable != null) {
                    FieldReference arrayVariable = runtime.newFieldReference(svFieldInfo, svScope, svFieldInfo.type());
                    VariableExpression array = runtime.newVariableExpressionBuilder().setVariable(arrayVariable)
                            .setSource(svScope.source()).build();
                    DependentVariable dv = runtime.newDependentVariable(array, dependentVariable.indexExpression());
                    newMap = Map.of(dv, expression);
                } else {
                    newMap = Map.of(runtime.newFieldReference(svFieldInfo, svScope, svFieldInfo.type()), expression);
                }
                return (StaticValues) new StaticValuesImpl(null, null, false, newMap);
            }).filter(Objects::nonNull).toList();
            Variable variable = ve.variable();
            if (!newList.isEmpty()) {
                append.put(variable, newList);
            }
            VariableInfoContainer vic = variableData.variableInfoContainerOrNull(variable.fullyQualifiedName());
            if (vic == null) {
                // grab the "original", we'll copy some info
                VariableInfoContainer vicOrig = variableData.variableInfoContainerOrNull(fr.fullyQualifiedName());
                assert vicOrig != null;
                // we're creating new variables as well, added in "recursivelyAddAssignmentsAtScopeLevel"
                Assignments assignments = new Assignments(statementIndex);
                Reads reads = new Reads(statementIndex);
                VariableInfoImpl initial = new VariableInfoImpl(variable, assignments, reads,
                        vicOrig.getPreviousOrInitial().variableInfoInClosure());
                VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vicOrig.variableNature(),
                        Either.right(initial), null, vicOrig.hasMerge());
                ((VariableDataImpl) variableData).put(variable, newVic);
            }
            if (variable instanceof FieldReference fr2) {
                Expression newSvScope = suffix(fullScope, fr2.scope());
                recursivelyAdd(append, fr2.scope(), fullScope, fr2, newSvScope, svFieldInfo, newList, variableData,
                        statementIndex);
            }
        }

        // internal check
        for (Map.Entry<Variable, List<StaticValues>> entry : append.entrySet()) {
            Variable v = entry.getKey();
            for (StaticValues sv : entry.getValue()) {
                assert sv.targetVariableStreamDescend().noneMatch(v::equals);
            }
        }
    }

    /*
    if all = a.b.c.d, and
    prefix = a.b.c, result should be d
    prefix = a.b, result should be c.d
     */
    private Expression suffix(Expression all, Expression prefix) {
        if (all instanceof VariableExpression ve && ve.variable() instanceof FieldReference fr) {
            This newThis = runtime.newThis(fr.fieldInfo().typeInfo().asParameterizedType());
            Expression scope = runtime.newVariableExpressionBuilder().setVariable(newThis).setSource(ve.source()).build();
            Expression move = fr.scope();
            while (!move.equals(prefix)) {
                if (move instanceof VariableExpression ve2 && ve2.variable() instanceof FieldReference fr2) {
                    FieldReference intermediaryFr = runtime.newFieldReference(fr2.fieldInfo(), scope, fr.parameterizedType());
                    scope = runtime.newVariableExpressionBuilder().setSource(ve2.source()).setVariable(intermediaryFr).build();
                    move = fr2.scope();
                }
            }
            FieldReference newFr = runtime.newFieldReference(fr.fieldInfo(), scope, fr.parameterizedType());
            return runtime.newVariableExpressionBuilder().setVariable(newFr).setSource(all.source()).build();
        }
        throw new UnsupportedOperationException();
    }
}
