package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.graph.Cache;
import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.GraphCacheImpl;
import org.e2immu.analyzer.modification.linkedvariables.graph.impl.WeightedGraphImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/*
given a number of links, build a graph, and compute the shortest path between all combinations,
to be stored in the VariableInfo objects.
 */
public class ComputeLinkCompletion {
    Cache cache = new GraphCacheImpl(100);

    class Builder {
        private final WeightedGraph weightedGraph = new WeightedGraphImpl(cache);

        void addLinkEvaluation(LinkEvaluation linkEvaluation, VariableData destination) {
            for (Map.Entry<Variable, LinkedVariables> entry : linkEvaluation.links().entrySet()) {
                VariableInfoImpl vi = (VariableInfoImpl) destination.variableInfo(entry.getKey());
                addLink(entry.getValue(), vi);
            }
        }

        void addLink(LinkedVariables linkedVariables, VariableInfoImpl destinationVi) {
            weightedGraph.addNode(destinationVi.variable(), linkedVariables.variables());
        }

        public void write(VariableData variableData, Stage stage, VariableData previous, Stage stageOfPrevious) {
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
            }
        }
    }
}
