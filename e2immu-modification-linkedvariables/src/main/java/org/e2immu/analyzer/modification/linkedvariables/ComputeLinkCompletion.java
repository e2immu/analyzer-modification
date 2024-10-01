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
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;

/*
given a number of links, build a graph, and compute the shortest path between all combinations,
to be stored in the VariableInfo objects.
 */
public class ComputeLinkCompletion {
    Cache cache = new GraphCacheImpl(100);

    class Builder {
        private final WeightedGraph weightedGraph = new WeightedGraphImpl(cache);
        private final Set<Variable> modifiedInEval = new HashSet<>();
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
                    vii.analysis().set(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.from(isModified));
                }
            }
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
                        if (vi != null && vi.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue()) {
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
