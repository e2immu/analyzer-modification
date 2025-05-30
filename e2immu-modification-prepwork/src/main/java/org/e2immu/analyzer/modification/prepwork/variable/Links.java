package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.List;
import java.util.Map;

public interface Links extends DijkstraShortestPath.Connection {
    Links ensureNoModification();

    boolean fromIsAll();

    HiddenContentSelector hcsMethod();

    HiddenContentTypes hctFrom();

    HiddenContentTypes hctTo();

    // if not single method links, then a chain of method calls eventually resulting in the current from -> to

    boolean singleLink();

    // hcsMethodFrom() === fullChain().getFirst().hcsMethodFrom()
    // hcsMethodTo() === fullChain().getLast().hcsMethodTo();
    List<? extends Links> fullChain();

    boolean toContainsAll();

    boolean toIsAll();

    record FromToMutable(int hcFrom, int hcTo, boolean mutable) {
        public FromToMutable withMutable(boolean b) {
            return new FromToMutable(hcFrom, hcTo, b);
        }
    }

    Map<Integer, FromToMutable> hcMethodToFromToMutableMap();

    Indices modificationAreaSource();

    Indices modificationAreaTarget();

    String toString(int hc);

    // let's see if we still need these

    Links mineToTheirs(Links links);

    Links reverse();

    Links theirsToTheirs(Links links);
}
