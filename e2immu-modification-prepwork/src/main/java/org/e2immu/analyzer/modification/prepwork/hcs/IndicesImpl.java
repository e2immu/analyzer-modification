package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.variable.Index;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// important: as soon as there are multiple elements, use a TreeSet!!
public record IndicesImpl(Set<Index> set) implements Indices, Comparable<Indices> {
    public static final Indices ALL_INDICES = new IndicesImpl(Set.of(IndexImpl.ALL_INDEX));
    public static final Indices FIELD_INDICES = new IndicesImpl(Set.of(IndexImpl.FIELD_INDEX));

    public static final int UNSPECIFIED = -2;

    public IndicesImpl {
        assert set != null && !set.isEmpty() && (set.size() == 1 || set instanceof TreeSet);
        assert set.size() == 1 || !set.contains(IndexImpl.ALL_INDEX);
    }

    public IndicesImpl(int i) {
        this(Set.of(new IndexImpl(List.of(i))));
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        List<Codec.EncodedValue> encodedValues = set.stream().map(i -> i.encode(codec, context)).toList();
        return codec.encodeList(context, encodedValues);
    }

    public static Indices decode(Codec codec, Codec.Context context, Codec.EncodedValue encodedValue) {
        List<Codec.EncodedValue> list = codec.decodeList(context, encodedValue);
        Set<Index> set = new TreeSet<>();
        for (Codec.EncodedValue ev : list) {
            Index index = IndexImpl.decode(codec, context, ev);
            set.add(index);
        }
        return new IndicesImpl(set);
    }


    @Override
    public String toString() {
        return set.stream().map(Object::toString).collect(Collectors.joining(";"));
    }

    @Override
    public int compareTo(Indices o) {
        Iterator<Index> mine = set.iterator();
        Iterator<Index> theirs = o.set().iterator();
        while (mine.hasNext()) {
            if (!theirs.hasNext()) return 1;
            int c = mine.next().compareTo(theirs.next());
            if (c != 0) return c;
        }
        if (theirs.hasNext()) return -1;
        return 0;
    }

    @Override
    public Indices merge(Indices indices) {
        return new IndicesImpl(Stream.concat(set.stream(), indices.set().stream())
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    @Override
    public ParameterizedType findInFormal(Runtime runtime, ParameterizedType type) {
        // in theory, they all should map to the same type... so we pick one
        Index first = set.stream().findFirst().orElseThrow();
        return first.findInFormal(runtime, type);
    }

    @Override
    public ParameterizedType find(Runtime runtime, ParameterizedType type) {
        // in theory, they all should map to the same type... so we pick one
        Index first = set.stream().findFirst().orElseThrow();
        return first.find(runtime, type);
    }

    @Override
    public Indices allOccurrencesOf(Runtime runtime, ParameterizedType where) {
        Index first = set.stream().findFirst().orElseThrow();
        ParameterizedType what = first.find(runtime, where);
        return staticAllOccurrencesOf(what, where);
    }

    public static Indices staticAllOccurrencesOf(ParameterizedType what, ParameterizedType where) {
        Set<Index> set = new TreeSet<>();
        staticAllOccurrencesOf(what, where, set, new Stack<>());
        if (set.isEmpty()) return null;
        return new IndicesImpl(set);
    }

    public static void staticAllOccurrencesOf(ParameterizedType what, ParameterizedType where, Set<Index> set, Stack<Integer> pos) {
        if (what.equals(where)) {
            Index index = new IndexImpl(List.copyOf(pos));
            set.add(index);
            return;
        }
        int i = 0;
        for (ParameterizedType pt : where.parameters()) {
            pos.push(i);
            staticAllOccurrencesOf(what, pt, set, pos);
            pos.pop();
            i++;
        }
    }

    @Override
    public boolean containsSize2Plus() {
        return set.stream().anyMatch(i -> i.list().size() > 1);
    }

    @Override
    public Indices size2PlusDropOne() {
        return new IndicesImpl(set.stream().filter(i -> i.list().size() > 1)
                .map(Index::dropFirst)
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    @Override
    public Indices first() {
        return new IndicesImpl(set.stream().filter(i -> i.list().size() > 1)
                .map(Index::takeFirst)
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    @Override
    public Indices prefix(int index) {
        Set<Index> newSet = set.stream().map(i -> i.prefix(index)).collect(Collectors.toUnmodifiableSet());
        return new IndicesImpl(newSet);
    }

    @Override
    public Integer single() {
        return set.stream().findFirst().map(Index::single).orElse(null);
    }

    @Override
    public Indices map(IntFunction<Integer> intFunction) {
        return new IndicesImpl(set.stream().map(index -> index.map(intFunction)).collect(Collectors.toUnmodifiableSet()));
    }
}
