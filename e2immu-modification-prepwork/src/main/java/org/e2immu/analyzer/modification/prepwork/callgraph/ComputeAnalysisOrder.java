package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.language.cst.api.info.Info;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.op.Linearize;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
given the call graph, compute the linearization, and all supporting information to deal with cycles.

TODO this one will become more complex, giving priority to methods with more modification within each call cycle.
 */
public class ComputeAnalysisOrder {

    public List<Info> go(G<Info> callGraph) {
        Set<V<Info>> subSet = callGraph.vertices().stream()
                .filter(v -> !v.t().typeInfo().compilationUnit().externalLibrary())
                .collect(Collectors.toUnmodifiableSet());
        G<Info> subGraph = callGraph.subGraph(subSet, l -> l >= ComputeCallGraph.REFERENCES);
        Linearize.Result<Info> result = Linearize.linearize(subGraph, Linearize.LinearizationMode.ALL);
        return result.asList(Comparator.comparing(Info::fullyQualifiedName));
    }

}
