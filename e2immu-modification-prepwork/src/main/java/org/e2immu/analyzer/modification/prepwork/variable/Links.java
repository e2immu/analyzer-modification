package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.Map;

public interface Links extends DijkstraShortestPath.Connection {
    Map<Indices, Link> map();

    Links mineToTheirs(Links links);

    Links reverse();

    Links theirsToTheirs(Links links);
}
