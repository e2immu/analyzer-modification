package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record SingleLinksImpl(HiddenContentSelector hcsMethod,
                              Map<Integer, FromToMutable> hcMethodToFromToMutableMap,
                              HiddenContentTypes hctFrom,
                              HiddenContentTypes hctTo,
                              Indices modificationAreaSource,
                              Indices modificationAreaTarget) implements Links {

    public static Links create(Runtime runtime,
                               HiddenContentSelector hcsMethod,
                               Map<Integer, FromToMutable> hcMethodToFromToMutableMap,
                               HiddenContentTypes hctFrom,
                               HiddenContentTypes hctTo,
                               Indices modificationAreaSource,
                               Indices modificationAreaTarget) {
        assert !hcMethodToFromToMutableMap.isEmpty();
        assert hcMethodToFromToMutableMap.entrySet().stream().allMatch(e -> {
            ParameterizedType pt = hcsMethod.hiddenContentTypes().typeByIndex(e.getKey()).asParameterizedType();
            return hctFrom.containsIndex(e.getValue().hcFrom())
                   && hctTo.containsIndex(e.getValue().hcTo())
                   && hcsMethod.containsIndex(e.getKey())
                   && hctFrom.typeByIndex(e.getValue().hcFrom()).asParameterizedType().isAssignableFrom(runtime, pt)
                   && hctTo.typeByIndex(e.getValue().hcTo()).asParameterizedType().isAssignableFrom(runtime, pt);
        });
        return new SingleLinksImpl(hcsMethod, hcMethodToFromToMutableMap, hctFrom, hctTo, modificationAreaSource,
                modificationAreaTarget);
    }

    @Override
    public Links ensureNoModification() {
        Map<Integer, FromToMutable> newMap = hcMethodToFromToMutableMap.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        e -> e.getValue().withMutable(false)));
        return new SingleLinksImpl(hcsMethod, newMap, hctFrom, hctTo, modificationAreaSource, modificationAreaTarget);
    }

    @Override
    public boolean fromIsAll() {
        if (hcMethodToFromToMutableMap.size() != 1) return false;
        FromToMutable ftm = hcMethodToFromToMutableMap.values().stream().findFirst().orElseThrow();
        return hctFrom.isAll(ftm.hcFrom());
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
    public boolean singleMethodLinks() {
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
    public String toString(int hc) {
        return hcsMethod.hiddenContentTypes().getHctTypeInfo().getTypeInfo().simpleName()
               + "." + hcsMethod.hiddenContentTypes().getMethodInfo().simpleName() + ":"
               + hcMethodToFromToMutableMap.entrySet().stream().map(e ->
                        e.getValue().hcFrom() + "-" + hc + "[" + e.getKey() + "]-"
                        + e.getValue().hcTo() + (e.getValue().mutable() ? "M" : ""))
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
