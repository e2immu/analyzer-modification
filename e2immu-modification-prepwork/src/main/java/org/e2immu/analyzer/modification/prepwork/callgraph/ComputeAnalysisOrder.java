package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.op.Linearize;

import java.util.*;

/*
given the call graph, compute the linearization, and all supporting information to deal with cycles.
 */
public class ComputeAnalysisOrder {

    public AnalysisOrder go(G<Info> callGraph, Set<MethodInfo> recursive) {
        Set<MethodInfo> partOfConstruction = computePartOfConstruction(callGraph);
        Linearize.Result<Info> result = Linearize.linearize(callGraph, Linearize.LinearizationMode.ALL);
        List<AnalysisOrder.InfoAndDetails> list = result.asList(Comparator.comparing(Info::fullyQualifiedName))
                .stream()
                .map(info -> new AnalysisOrder.InfoAndDetails(info,
                        false, // TODO
                        info instanceof MethodInfo mi && partOfConstruction.contains(mi),
                        info instanceof MethodInfo mi && recursive.contains(mi)))
                .toList();
        return new AnalysisOrder(list);
    }

    private Set<MethodInfo> computePartOfConstruction(G<Info> callGraph) {
        Set<MethodInfo> candidates = new HashSet<>();
        callGraph.vertices().forEach(v -> {
            if (v.t() instanceof MethodInfo mi && canBePartOfConstruction(mi)) {
                candidates.add(mi);
            }
        });
        boolean changes = true;
        while (changes) {
            changes = false;
            for (V<Info> v : callGraph.vertices()) {
                if (v.t() instanceof MethodInfo methodInfo) {
                    if (!canBePartOfConstruction(methodInfo)) {
                        Map<V<Info>, Long> edges = callGraph.edges(v);
                        if (edges != null) {
                            for (Map.Entry<V<Info>, Long> entry : edges.entrySet()) {
                                if (entry.getKey().t() instanceof MethodInfo toMethod) {
                                    changes |= candidates.remove(toMethod);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Set.copyOf(candidates);
    }

    private boolean canBePartOfConstruction(MethodInfo mi) {
        return mi.isConstructor() || mi.access().isPrivate() && mi.typeInfo().enclosingMethod() == null;
    }
}
