package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.util.ListUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.HiddenContentTypes.*;

public record Index(List<Integer> list) implements Comparable<Index> {
    public static final int ALL = -1;
    public static final Index ALL_INDEX = new Index(List.of(ALL));

    public static Index createZeroes(int arrays) {
        List<Integer> list = new ArrayList<>(arrays);
        for (int i = 0; i < arrays; i++) list.add(0);
        return new Index(List.copyOf(list));
    }

    @Override
    public int compareTo(Index o) {
        return ListUtil.compare(list, o.list);
    }

    @Override
    public String toString() {
        return list.stream().map(Object::toString).collect(Collectors.joining("."));
    }

    /*
     extract type given the indices. Switch to formal for the last index in the list only!
     Map.Entry<A,B>, with index 0, will return K in Map.Entry
     Set<Map.Entry<A,B>>, with indices 0.1, will return V in Map.Entry.
     */
    public ParameterizedType findInFormal(Runtime runtime, ParameterizedType type) {
        return findInFormal(runtime, type, 0, true);
    }

    public ParameterizedType find(Runtime ru, ParameterizedType type) {
        return findInFormal(ru, type, 0, false);
    }

    private ParameterizedType findInFormal(Runtime runtime, ParameterizedType type, int pos, boolean switchToFormal) {
        if (type.parameters().isEmpty()) {
            // no generics, so substitute "Object"
            if (type.typeInfo() != null) {
                HiddenContentTypes hct = type.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
                NamedType byIndex = hct.typeByIndex(pos);
                if (byIndex != null) {
                    return byIndex.asParameterizedType(runtime);
                }
            }
            return runtime.objectParameterizedType();
        }
        int index = list.get(pos);
        if (pos == list.size() - 1) {
            assert type.typeInfo() != null;
            ParameterizedType formal = switchToFormal ? type.typeInfo().asParameterizedType(runtime) : type;
            assert index < formal.parameters().size();
            return formal.parameters().get(index);
        }
        ParameterizedType nextType = type.parameters().get(index);
        assert nextType != null;
        return findInFormal(runtime, nextType, pos + 1, switchToFormal);
    }

    public Index replaceLast(int v) {
        if (list.get(list.size() - 1) == v) return this;
        return new Index(Stream.concat(list.stream().limit(list.size() - 1), Stream.of(v)).toList());
    }

    public int countSequentialZeros() {
        int cnt = 0;
        for (int i : list) {
            if (i != 0) return -1;
            cnt++;
        }
        return cnt;
    }

    public Index dropFirst() {
        assert list.size() > 1;
        return new Index(list.subList(1, list.size()));
    }

    public Index takeFirst() {
        assert list.size() > 1;
        return new Index(List.of(list.get(0)));
    }

    public Index prefix(int index) {
        return new Index(Stream.concat(Stream.of(index), list.stream()).toList());
    }

    public Integer single() {
        return list.size() == 1 ? list.get(0) : null;
    }

    public Index map(IntFunction<Integer> intFunction) {
        int index = list.get(0);
        return new Index(Stream.concat(Stream.of(intFunction.apply(index)), list.stream().skip(1)).toList());
    }
}
