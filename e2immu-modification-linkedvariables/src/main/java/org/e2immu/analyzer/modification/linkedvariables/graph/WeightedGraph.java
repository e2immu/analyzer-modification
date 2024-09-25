package org.e2immu.analyzer.modification.linkedvariables.graph;

import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.annotation.Independent;
import org.e2immu.annotation.Modified;
import org.e2immu.annotation.NotModified;
import org.e2immu.annotation.NotNull;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public interface WeightedGraph {

    @NotNull
    ClusterResult staticClusters();

    record Cluster(Set<Variable> variables) {
        @Override
        public String toString() {
            return "[" + variables.stream().map(Variable::simpleName).sorted()
                    .collect(Collectors.joining(", ")) + ']';
        }
    }

    record ClusterResult(Cluster returnValueCluster, Variable rv, List<Cluster> clusters) {
        public Set<Variable> variablesInClusters() {
            return clusters.stream().flatMap(c -> c.variables.stream())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @NotModified
    int size();

    @NotModified
    boolean isEmpty();

    @Independent(hc = true)
    @NotModified
    ShortestPath shortestPath();

    @NotModified
    void visit(@NotNull @Independent(hc = true) BiConsumer<Variable, Map<Variable, LV>> consumer);

    @Modified
    void addNode(@NotNull @Independent(hc = true) Variable v,
                 @NotNull @Independent(hc = true) Map<Variable, LV> dependsOn);
}
