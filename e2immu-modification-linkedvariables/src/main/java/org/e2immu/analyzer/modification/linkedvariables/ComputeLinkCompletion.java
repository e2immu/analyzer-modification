package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.graph.Cache;
import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.GraphCacheImpl;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.WeightedGraphImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.support.Either;
import org.e2immu.util.internal.util.ListUtil;

import java.util.*;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;

/*
given a number of links, build a graph, and compute the shortest path between all combinations,
to be stored in the VariableInfo objects.
 */
public class ComputeLinkCompletion {
    private final Cache cache = new GraphCacheImpl(100);
    private final Runtime runtime;

    ComputeLinkCompletion(Runtime runtime) {
        this.runtime = runtime;
    }

    class Builder {
        private final WeightedGraph weightedGraph = new WeightedGraphImpl(cache);
        private final Set<Variable> modifiedInEval = new HashSet<>();
        private final Map<FieldReference, Boolean> modifiedFunctionalComponents = new HashMap<>();

        private final Map<Variable, List<StaticValues>> staticValues = new HashMap<>();

        void addLinkEvaluation(LinkEvaluation linkEvaluation, VariableData destination) {
            for (Map.Entry<Variable, LinkedVariables> entry : linkEvaluation.links().entrySet()) {
                VariableInfoImpl vi = (VariableInfoImpl) destination.variableInfo(entry.getKey());
                addLink(entry.getValue(), vi);
            }
            for (Map.Entry<Variable, StaticValues> entry : linkEvaluation.assignments().entrySet()) {
                VariableInfoImpl vi = (VariableInfoImpl) destination.variableInfo(entry.getKey());
                addAssignment(vi.variable(), entry.getValue());
            }
            this.modifiedInEval.addAll(linkEvaluation.modified());
            this.modifiedFunctionalComponents.putAll(linkEvaluation.modifiedFunctionalComponents());
        }

        void addAssignment(Variable variable, StaticValues value) {
            staticValues.computeIfAbsent(variable, l -> new ArrayList<>()).add(value);
        }

        void addLink(LinkedVariables linkedVariables, VariableInfoImpl destinationVi) {
            weightedGraph.addNode(destinationVi.variable(), linkedVariables.variables());
        }

        public void write(VariableData variableData, Stage stage,
                          VariableData previous, Stage stageOfPrevious,
                          String statementIndex) {
            writeLinksAndModification(variableData, stage, previous, stageOfPrevious);
            writeAssignments(variableData, stage, previous, stageOfPrevious, statementIndex);
        }

        private void writeAssignments(VariableData variableData, Stage stage,
                                      VariableData previous, Stage stageOfPrevious,
                                      String statementIndex) {
            if (previous != null) {
                // copy previous assignment data into the map, but only for variables that are known to the current one
                // (some variables disappear after a statement, e.g. pattern variables)
                previous.variableInfoStream(stageOfPrevious)
                        .forEach(vi -> {
                            if (variableData.isKnown(vi.variable().fullyQualifiedName()) && vi.staticValues() != null) {
                                addAssignment(vi.variable(), vi.staticValues());
                            }
                        });
            }
            recursivelyAddAssignmentsAtScopeLevel(variableData, statementIndex);
            for (Map.Entry<Variable, List<StaticValues>> entry : staticValues.entrySet()) {
                Variable variable = entry.getKey();
                VariableInfoContainer vic = variableData.variableInfoContainerOrNull(variable.fullyQualifiedName());
                if (!vic.has(stage)) {
                    //     throw new UnsupportedOperationException("We should make an entry at this stage?");
                }
                VariableInfoImpl vii = (VariableInfoImpl) vic.best(stage);
                StaticValues merge = entry.getValue().stream().reduce(StaticValuesImpl.NONE, StaticValues::merge);
                vii.staticValuesSet(merge);
            }
        }

        /*
        if we have an assignment of E=3 to a.b, we add an assignment of b=3 to a.

        If we have an assignment of E=3 to a.b.c, we first add an assignment of this.c=3 to a.b,
        then we add an assignment of this.b.c=3 to a.
         */
        private void recursivelyAddAssignmentsAtScopeLevel(VariableData variableData, String statementIndex) {
            Map<Variable, List<StaticValues>> append = new HashMap<>();
            for (Map.Entry<Variable, List<StaticValues>> entry : staticValues.entrySet()) {
                if (entry.getKey() instanceof FieldReference fr) {
                    Expression newScope = ExpressionAnalyzer.recursivelyReplaceAccessorByFieldReference(runtime, fr.scope());
                    Expression svScope = runtime.newVariableExpression(runtime.newThis(fr.fieldInfo().typeInfo().asParameterizedType()));
                    recursivelyAdd(append, newScope, newScope, fr, svScope, fr.fieldInfo(), entry.getValue(), variableData, statementIndex);
                }
            }
            append.forEach((v, list) -> staticValues.merge(v, list, ListUtil::immutableConcat));
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
                    if (sv.expression() != null && sv.values().isEmpty()) {
                        expression = sv.expression();
                    } else if (!sv.values().isEmpty()) {
                        expression = sv.values().values().stream().findFirst().orElseThrow();
                    } else {
                        return null; // empty sv
                    }
                    Map<Variable, Expression> newMap = Map.of(runtime.newFieldReference(svFieldInfo, svScope,
                            svFieldInfo.type()), expression);
                    return (StaticValues) new StaticValuesImpl(null, null, newMap);
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
                    VariableInfoImpl initial = new VariableInfoImpl(variable, assignments, reads);
                    VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vicOrig.variableNature(),
                            Either.right(initial), null, vicOrig.hasMerge());
                    ((VariableDataImpl) variableData).put(variable, newVic);
                }
                if (variable instanceof FieldReference fr2) {
                    Expression newScope2 = ExpressionAnalyzer.recursivelyReplaceAccessorByFieldReference(runtime, fr2.scope());
                    Expression newSvScope = suffix(fullScope, newScope2);
                    recursivelyAdd(append, newScope2, fullScope, fr2, newSvScope, svFieldInfo, newList, variableData, statementIndex);
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
                Expression scope = runtime.newVariableExpression(runtime.newThis(fr.fieldInfo().typeInfo().asParameterizedType()));
                Expression move = fr.scope();
                while (!move.equals(prefix)) {
                    if (move instanceof VariableExpression ve2 && ve2.variable() instanceof FieldReference fr2) {
                        FieldReference intermediaryFr = runtime.newFieldReference(fr2.fieldInfo(), scope, fr.parameterizedType());
                        scope = runtime.newVariableExpression(intermediaryFr);
                        move = fr2.scope();
                    }
                }
                FieldReference newFr = runtime.newFieldReference(fr.fieldInfo(), scope, fr.parameterizedType());
                return runtime.newVariableExpression(newFr);
            }
            throw new UnsupportedOperationException();
        }

        private void writeLinksAndModification(VariableData variableData, Stage stage,
                                               VariableData previous, Stage stageOfPrevious) {
            if (previous != null) {
                // copy previous link data into the graph, but only for variables that are known to the current one
                // (some variables disappear after a statement, e.g. pattern variables)
                previous.variableInfoStream(stageOfPrevious)
                        .forEach(vi -> {
                            if (variableData.isKnown(vi.variable().fullyQualifiedName()) && vi.linkedVariables() != null) {
                                Map<Variable, LV> map = new HashMap<>();
                                vi.linkedVariables().stream().filter(e -> variableData.isKnown(e.getKey().fullyQualifiedName()))
                                        .forEach(e -> map.put(e.getKey(), e.getValue()));
                                weightedGraph.addNode(vi.variable(), map);
                            }
                        });
            }
            // ensure that all variables known at this stage, are present
            variableData.variableInfoStream(stage).forEach(vi -> weightedGraph.addNode(vi.variable(), Map.of()));

            ShortestPath shortestPath = weightedGraph.shortestPath();
            Set<Variable> modifying = computeModified(previous, stageOfPrevious, modifiedInEval, shortestPath);
            Map<Variable, Map<Variable, Boolean>> mfiComponentMaps = computeMFIComponents(previous, stageOfPrevious,
                    modifiedFunctionalComponents, shortestPath);

            for (Variable variable : shortestPath.variables()) {
                Map<Variable, LV> links = shortestPath.links(variable, null);

                VariableInfoContainer vic = variableData.variableInfoContainerOrNull(variable.fullyQualifiedName());
                assert vic != null;
                if (!vic.has(stage)) {
                    throw new UnsupportedOperationException("We should make an entry at this stage?");
                }
                VariableInfoImpl vii = (VariableInfoImpl) vic.best(stage);
                LinkedVariables linkedVariables = LinkedVariablesImpl.of(links).remove(Set.of(variable));
                vii.initializeLinkedVariables(LinkedVariablesImpl.NOT_YET_SET);
                if (links.isEmpty()) {
                    vii.setLinkedVariables(LinkedVariablesImpl.EMPTY);
                } else {
                    vii.setLinkedVariables(linkedVariables);
                }
                if (!vii.analysis().haveAnalyzedValueFor(VariableInfoImpl.MODIFIED_VARIABLE)) {
                    boolean isModified = modifying.contains(variable);
                    vii.analysis().set(MODIFIED_VARIABLE, ValueImpl.BoolImpl.from(isModified));
                }
                Map<Variable, Boolean> mfiComponents = mfiComponentMaps.get(vii.variable());
                if (mfiComponents != null && !vii.analysis().haveAnalyzedValueFor(MODIFIED_FI_COMPONENTS_VARIABLE)) {
                    vii.analysis().set(MODIFIED_FI_COMPONENTS_VARIABLE, new ValueImpl.VariableBooleanMapImpl(mfiComponents));
                }
            }
        }

        private Map<Variable, Map<Variable, Boolean>> computeMFIComponents
                (VariableData previous, Stage stageOfPrevious,
                 Map<FieldReference, Boolean> modifiedFunctionalComponents,
                 ShortestPath shortestPath) {
            Map<Variable, Map<Variable, Boolean>> mapForAllVariables = new HashMap<>();
            modifiedFunctionalComponents.forEach((fr, b) -> recursivelyAddTo(mapForAllVariables, fr, b));
            if (previous != null) {
                for (Variable variable : shortestPath.variables()) {
                    VariableInfoContainer vicPrev = previous.variableInfoContainerOrNull(variable.fullyQualifiedName());
                    if (vicPrev != null) {
                        VariableInfo vi = vicPrev.best(stageOfPrevious);
                        if (vi != null) {
                            Value.VariableBooleanMap map = vi.analysis().getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE,
                                    ValueImpl.VariableBooleanMapImpl.EMPTY);
                            mergeMFIMaps(mapForAllVariables, vi.variable(), map.map());
                        }
                    }
                }
            }
            boolean change = true;
            while (change) {
                change = false;
                for (Variable variable : shortestPath.variables()) {
                    Map<Variable, LV> links = shortestPath.links(variable, null);
                    for (Map.Entry<Variable, LV> e : links.entrySet()) {
                        Variable to = e.getKey();
                        if (to != variable && mapForAllVariables.containsKey(to) && e.getValue().isStaticallyAssignedOrAssigned()) {
                            change |= mergeMFIMaps(mapForAllVariables, variable, mapForAllVariables.get(to));
                        }
                    }
                }
            }
            return mapForAllVariables;
        }

        /*
        when presented with r.function->true, we'll add r.function->true in r

        when presented with s.r.function->true, we'll add r.function->true in s.r and s.r.function->true in s

        when presented with t.s.r.function->true, we add 3:
            r.function->true in t.s.r
            s.r.function->true in t.s
            t.s.r.function->true in t

         this mechanism is very similar to the one in recursivelyAdd() for static values.
         FOR NOW, we'll only add when the variable already exists. There is, I believe, no point to have the
         intermediaries.
         */
        private void recursivelyAddTo(Map<Variable, Map<Variable, Boolean>> mapForAllVariables, FieldReference fr, Boolean b) {
            Variable base = fr;
            while (base instanceof FieldReference fr2 && fr2.scope() instanceof VariableExpression ve) {
                base = ve.variable();
            }
            mapForAllVariables.put(base, Map.of(fr, b));
        }

        private static boolean mergeMFIMaps(Map<Variable, Map<Variable, Boolean>> mapForAllVariables,
                                            Variable v,
                                            Map<Variable, Boolean> map) {
            Map<Variable, Boolean> inMap = mapForAllVariables.get(v);
            Map<Variable, Boolean> newMap = mapForAllVariables.merge(v, map, (m1, m2) -> {
                Map<Variable, Boolean> res = new HashMap<>(m1);
                for (Map.Entry<Variable, Boolean> e : m2.entrySet()) {
                    Boolean b = res.put(e.getKey(), e.getValue());
                    if (b != null && b != e.getValue()) throw new UnsupportedOperationException();
                }
                return Map.copyOf(res);
            });
            return !Objects.equals(inMap, newMap);
        }


        private Set<Variable> computeModified(VariableData previous,
                                              Stage stageOfPrevious,
                                              Set<Variable> modifiedInEval,
                                              ShortestPath shortestPath) {

            // because we have no completeness of the graph at the moment, we iterate
            Set<Variable> modified = new HashSet<>(modifiedInEval);
            if (previous != null) {
                for (Variable variable : shortestPath.variables()) {
                    VariableInfoContainer vicPrev = previous.variableInfoContainerOrNull(variable.fullyQualifiedName());
                    if (vicPrev != null) {
                        VariableInfo vi = vicPrev.best(stageOfPrevious);
                        if (vi != null && vi.analysis().getOrDefault(MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue()) {
                            modified.add(vi.variable());
                        }
                    }
                }
            }
            boolean change = true;
            while (change) {
                change = false;
                for (Variable variable : shortestPath.variables()) {
                    Map<Variable, LV> links = shortestPath.links(variable, null);
                    for (Map.Entry<Variable, LV> e : links.entrySet()) {
                        Variable to = e.getKey();
                        if (to != variable && modified.contains(to) &&
                            (e.getValue().isDependent() && !e.getValue().intoField() || e.getValue().isStaticallyAssignedOrAssigned())) {
                            change |= modified.add(variable);
                        }
                    }
                }
            }
            return modified;
        }
    }
}
