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

public class MethodLinks extends SingleLinks {
    private final HiddenContentSelector hcsMethod;

    public MethodLinks(Runtime runtime,
                       HiddenContentSelector hcsMethod,
                       Map<Integer, FromToMutable> hcMethodToFromToMutableMap,
                       HiddenContentTypes hctFrom,
                       HiddenContentTypes hctTo,
                       Indices modificationAreaSource,
                       Indices modificationAreaTarget) {
        super(hcMethodToFromToMutableMap, hctFrom, hctTo, modificationAreaSource, modificationAreaTarget);
        this.hcsMethod = hcsMethod;

        assert !hcMethodToFromToMutableMap.isEmpty();
        assert runtime == null ||
               hcMethodToFromToMutableMap.entrySet().stream().allMatch(e -> {
                   ParameterizedType pt = hcsMethod.hiddenContentTypes().typeByIndex(e.getKey()).asParameterizedType();
                   return hctFrom.containsIndex(e.getValue().hcFrom())
                          && hctTo.containsIndex(e.getValue().hcTo())
                          && hcsMethod.containsIndex(e.getKey())
                          && hctFrom.typeByIndex(e.getValue().hcFrom()).asParameterizedType().isAssignableFrom(runtime, pt)
                          && hctTo.typeByIndex(e.getValue().hcTo()).asParameterizedType().isAssignableFrom(runtime, pt);
               });
    }

    @Override
    public Links ensureNoModification() {
        Map<Integer, FromToMutable> newMap = hcMethodToFromToMutableMap().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        e -> e.getValue().withMutable(false)));
        return new MethodLinks(null, hcsMethod, newMap, hctFrom(), hctTo(), modificationAreaSource(),
                modificationAreaTarget());
    }

    @Override
    public HiddenContentSelector hcsMethod() {
        return hcsMethod;
    }

    @Override
    public String toString(int hc) {
        return hcsMethod.hiddenContentTypes().getHctTypeInfo().getTypeInfo().simpleName()
               + "." + hcsMethod.hiddenContentTypes().getMethodInfo().simpleName() + ":"
               + hcMethodToFromToMutableMap().entrySet().stream().map(e ->
                        e.getValue().hcFrom() + "-" + hc + "[" + e.getKey() + "]-"
                        + e.getValue().hcTo() + (e.getValue().mutable() ? "M" : ""))
                       .collect(Collectors.joining(";"));
    }
}
