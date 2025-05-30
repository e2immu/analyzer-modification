package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MultiLinksImpl implements Links {
    private final List<SingleLinksImpl> chain;
    private final Map<Integer, FromToMutable> hcMethodToFromToMutableMap;

    public MultiLinksImpl(List<SingleLinksImpl> chain, Map<Integer, FromToMutable> hcMethodToFromToMutableMap) {
        assert chain != null && chain.size() > 1;
        this.chain = chain;
        assert hcMethodToFromToMutableMap != null && !hcMethodToFromToMutableMap.isEmpty();
        this.hcMethodToFromToMutableMap = hcMethodToFromToMutableMap;
    }

    @Override
    public Links ensureNoModification() {
        Map<Integer, FromToMutable> newMap = hcMethodToFromToMutableMap.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        e -> e.getValue().withMutable(false)));
        return new MultiLinksImpl(chain, newMap);
    }

    @Override
    public boolean fromIsAll() {
        return chain.getFirst().fromIsAll();
    }

    @Override
    public HiddenContentSelector hcsMethod() {
        return null;
    }

    @Override
    public HiddenContentTypes hctFrom() {
        return chain.getFirst().hctFrom();
    }

    @Override
    public HiddenContentTypes hctTo() {
        return chain.getLast().hctTo();
    }

    @Override
    public DijkstraShortestPath.Accept next(Function<Integer, String> nodePrinter, int from, int to,
                                            DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        return null;
    }

    @Override
    public boolean singleMethodLinks() {
        return false;
    }

    @Override
    public List<? extends Links> fullChain() {
        return chain;
    }

    @Override
    public boolean toContainsAll() {
        return chain.getLast().toContainsAll();
    }

    @Override
    public boolean toIsAll() {
        return chain.getLast().toContainsAll();
    }

    @Override
    public Map<Integer, FromToMutable> hcMethodToFromToMutableMap() {
        return Map.of();
    }

    @Override
    public Indices modificationAreaSource() {
        return chain.getFirst().modificationAreaSource();
    }

    @Override
    public Indices modificationAreaTarget() {
        return chain.getLast().modificationAreaTarget();
    }

    @Override
    public String toString(int hc) {
        return hcMethodToFromToMutableMap.values().stream()
                .map(fromToMutable -> fromToMutable.hcFrom() + "-" + hc + "-"
                                      + fromToMutable.hcTo() + (fromToMutable.mutable() ? "M" : ""))
                .collect(Collectors.joining(";"));
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
