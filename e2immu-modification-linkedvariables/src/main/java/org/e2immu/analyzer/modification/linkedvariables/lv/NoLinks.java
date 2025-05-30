package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class NoLinks implements Links {
    public static final Links NO_LINKS = new NoLinks();

    private NoLinks() {
        // no code here
    }

    @Override
    public Links ensureNoModification() {
        return this;
    }

    @Override
    public boolean fromIsAll() {
        return false;
    }

    @Override
    public HiddenContentSelector hcsMethod() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HiddenContentTypes hctFrom() {
        throw new UnsupportedOperationException();
    }

    @Override
    public HiddenContentTypes hctTo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public DijkstraShortestPath.Accept next(Function<Integer, String> nodePrinter, int from, int to, DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public boolean singleLink() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<? extends Links> fullChain() {
        return List.of();
    }

    @Override
    public boolean toContainsAll() {
        return false;
    }

    @Override
    public boolean toIsAll() {
        return false;
    }

    @Override
    public Map<Integer, FromToMutable> hcMethodToFromToMutableMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Indices modificationAreaSource() {
        return null;
    }

    @Override
    public Indices modificationAreaTarget() {
        return null;
    }

    @Override
    public String toString(int hc) {
        return "NO LINKS";
    }

    @Override
    public Links mineToTheirs(Links links) {
        return null;
    }

    @Override
    public Links reverse() {
        return null;
    }

    @Override
    public Links theirsToTheirs(Links links) {
        return null;
    }
}
