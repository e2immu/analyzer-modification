package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.graph.Cache;
import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.GraphCacheImpl;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.WeightedGraphImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.util.internal.util.MapUtil;

import java.util.*;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_VARIABLE;

/*
given a number of links, build a graph, and compute the shortest path between all combinations,
to be stored in the VariableInfo objects.
 */
public class ComputeLinkCompletion {
    Cache cache = new GraphCacheImpl(100);

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
                          VariableData previous, Stage stageOfPrevious) {
            writeLinksAndModification(variableData, stage, previous, stageOfPrevious);
            writeAssignments(variableData, stage, previous, stageOfPrevious);
        }

        private void writeAssignments(VariableData variableData, Stage stage,
                                      VariableData previous, Stage stageOfPrevious) {
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
            for (Map.Entry<Variable, List<StaticValues>> entry : staticValues.entrySet()) {
                Variable variable = entry.getKey();
                VariableInfoContainer vic = variableData.variableInfoContainerOrNull(variable.fullyQualifiedName());
                assert vic != null;
                if (!vic.has(stage)) {
                    throw new UnsupportedOperationException("We should make an entry at this stage?");
                }
                VariableInfoImpl vii = (VariableInfoImpl) vic.best(stage);
                StaticValues merge = entry.getValue().stream().reduce(StaticValuesImpl.NONE, StaticValues::merge);
                vii.staticValuesSet(merge);
            }
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
            Map<Variable, Map<FieldInfo, Boolean>> mfiComponentMaps = computeMFIComponents(previous, stageOfPrevious,
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
                Map<FieldInfo, Boolean> mfiComponents = mfiComponentMaps.get(vii.variable());
                if (mfiComponents != null && !vii.analysis().haveAnalyzedValueFor(MODIFIED_FI_COMPONENTS_VARIABLE)) {
                    vii.analysis().set(MODIFIED_FI_COMPONENTS_VARIABLE, new ValueImpl.FieldBooleanMapImpl(mfiComponents));
                }
            }
        }

        private Map<Variable, Map<FieldInfo, Boolean>> computeMFIComponents
                (VariableData previous, Stage stageOfPrevious,
                 Map<FieldReference, Boolean> modifiedFunctionalComponents,
                 ShortestPath shortestPath) {
            Map<Variable, Map<FieldInfo, Boolean>> mapForAllVariables = new HashMap<>();
            modifiedFunctionalComponents.forEach((fr, b) -> mapForAllVariables.put(fr.scopeVariable(), Map.of(fr.fieldInfo(), b)));
            if (previous != null) {
                for (Variable variable : shortestPath.variables()) {
                    VariableInfoContainer vicPrev = previous.variableInfoContainerOrNull(variable.fullyQualifiedName());
                    if (vicPrev != null) {
                        VariableInfo vi = vicPrev.best(stageOfPrevious);
                        if (vi != null) {
                            Value.FieldBooleanMap map = vi.analysis().getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE, ValueImpl.FieldBooleanMapImpl.EMPTY);
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

        private static boolean mergeMFIMaps(Map<Variable, Map<FieldInfo, Boolean>> mapForAllVariables,
                                            Variable v,
                                            Map<FieldInfo, Boolean> map) {
            Map<FieldInfo, Boolean> inMap = mapForAllVariables.get(v);
            Map<FieldInfo, Boolean> newMap = mapForAllVariables.merge(v, map, (m1, m2) -> {
                Map<FieldInfo, Boolean> res = new HashMap<>(m1);
                for (Map.Entry<FieldInfo, Boolean> e : m2.entrySet()) {
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
                            (e.getValue().isDependent() || e.getValue().isStaticallyAssignedOrAssigned())) {
                            change |= modified.add(variable);
                        }
                    }
                }
            }
            return modified;
        }
    }
}
