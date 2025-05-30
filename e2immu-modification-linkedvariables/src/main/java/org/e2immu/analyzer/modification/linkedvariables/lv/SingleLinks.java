package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class SingleLinks implements Links {
    private final Map<Integer, FromToMutable> hcMethodToFromToMutableMap;
    private final HiddenContentTypes hctFrom;
    private final HiddenContentTypes hctTo;
    private final Indices modificationAreaSource;
    private final Indices modificationAreaTarget;

    public SingleLinks(Map<Integer, FromToMutable> hcMethodToFromToMutableMap,
                       HiddenContentTypes hctFrom,
                       HiddenContentTypes hctTo,
                       Indices modificationAreaSource,
                       Indices modificationAreaTarget) {
        this.hcMethodToFromToMutableMap = hcMethodToFromToMutableMap;
        this.hctFrom = hctFrom;
        this.hctTo = hctTo;
        this.modificationAreaSource = modificationAreaSource;
        this.modificationAreaTarget = modificationAreaTarget;
    }

    @Override
    public boolean fromIsAll() {
        if (hcMethodToFromToMutableMap.size() != 1) return false;
        FromToMutable ftm = hcMethodToFromToMutableMap.values().stream().findFirst().orElseThrow();
        return hctFrom.isAll(ftm.hcFrom());
    }

    @Override
    public HiddenContentTypes hctFrom() {
        return hctFrom;
    }

    @Override
    public HiddenContentTypes hctTo() {
        return hctTo;
    }

    @Override
    public DijkstraShortestPath.Accept next(Function<Integer, String> nodePrinter, int from,
                                            int to, DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public boolean singleLink() {
        return true;
    }

    @Override
    public List<? extends Links> fullChain() {
        return List.of(this);
    }

    @Override
    public boolean toContainsAll() {
        return hcMethodToFromToMutableMap.values().stream().anyMatch(ftm -> hctTo.isAll(ftm.hcTo()));
    }

    @Override
    public boolean toIsAll() {
        if (hcMethodToFromToMutableMap.size() != 1) return false;
        FromToMutable ftm = hcMethodToFromToMutableMap.values().stream().findFirst().orElseThrow();
        return hctTo.isAll(ftm.hcTo());
    }

    @Override
    public Map<Integer, FromToMutable> hcMethodToFromToMutableMap() {
        return hcMethodToFromToMutableMap;
    }

    @Override
    public Indices modificationAreaSource() {
        return modificationAreaSource;
    }

    @Override
    public Indices modificationAreaTarget() {
        return modificationAreaTarget;
    }

    @Override
    public Links mineToTheirs(Links links) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Links reverse() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Links theirsToTheirs(Links links) {
        throw new UnsupportedOperationException();
    }
}
