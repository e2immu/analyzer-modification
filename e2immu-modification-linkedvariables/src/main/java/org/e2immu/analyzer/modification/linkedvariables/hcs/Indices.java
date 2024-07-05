package org.e2immu.analyzer.modification.linkedvariables.hcs;

import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// important: as soon as there are multiple elements, use a TreeSet!!
public record Indices(Set<Index> set) implements Comparable<Indices> {
    public static final Indices ALL_INDICES = new Indices(Set.of(Index.ALL_INDEX));
    public static final int UNSPECIFIED = -2;

    public Indices {
        assert set != null && !set.isEmpty() && (set.size() == 1 || set instanceof TreeSet);
        assert set.size() == 1 || !set.contains(Index.ALL_INDEX);
    }

    public Indices(int i) {
        this(Set.of(new Index(List.of(i))));
    }

    @Override
    public String toString() {
        return set.stream().map(Object::toString).collect(Collectors.joining(";"));
    }

    @Override
    public int compareTo(Indices o) {
        Iterator<Index> mine = set.iterator();
        Iterator<Index> theirs = o.set.iterator();
        while (mine.hasNext()) {
            if (!theirs.hasNext()) return 1;
            int c = mine.next().compareTo(theirs.next());
            if (c != 0) return c;
        }
        if (theirs.hasNext()) return -1;
        return 0;
    }

    public Indices merge(Indices indices) {
        return new Indices(Stream.concat(set.stream(), indices.set.stream())
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    public ParameterizedType findInFormal(Runtime runtime, ParameterizedType type) {
        // in theory, they all should map to the same type... so we pick one
        Index first = set.stream().findFirst().orElseThrow();
        return first.findInFormal(runtime, type);
    }

    public ParameterizedType find(Runtime runtime, ParameterizedType type) {
        // in theory, they all should map to the same type... so we pick one
        Index first = set.stream().findFirst().orElseThrow();
        return first.find(runtime, type);
    }

    public Indices allOccurrencesOf(Runtime runtime, ParameterizedType where) {
        Index first = set.stream().findFirst().orElseThrow();
        ParameterizedType what = first.find(runtime, where);
        return allOccurrencesOf(what, where);
    }

    public static Indices allOccurrencesOf(ParameterizedType what, ParameterizedType where) {
        Set<Index> set = new TreeSet<>();
        allOccurrencesOf(what, where, set, new Stack<>());
        if (set.isEmpty()) return null;
        return new Indices(set);
    }

    private static void allOccurrencesOf(ParameterizedType what, ParameterizedType where, Set<Index> set, Stack<Integer> pos) {
        if (what.equals(where)) {
            Index index = new Index(List.copyOf(pos));
            set.add(index);
            return;
        }
        int i = 0;
        for (ParameterizedType pt : where.parameters()) {
            pos.push(i);
            allOccurrencesOf(what, pt, set, pos);
            pos.pop();
            i++;
        }
    }

    public boolean containsSize2Plus() {
        return set.stream().anyMatch(i -> i.list().size() > 1);
    }

    public Indices size2PlusDropOne() {
        return new Indices(set.stream().filter(i -> i.list().size() > 1)
                .map(Index::dropFirst)
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    public Indices first() {
        return new Indices(set.stream().filter(i -> i.list().size() > 1)
                .map(Index::takeFirst)
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    public Indices prefix(int index) {
        Set<Index> newSet = set.stream().map(i -> i.prefix(index)).collect(Collectors.toUnmodifiableSet());
        return new Indices(newSet);
    }

    public Integer single() {
        return set.stream().findFirst().map(Index::single).orElse(null);
    }

    public Indices map(IntFunction<Integer> intFunction) {
        return new Indices(set.stream().map(index -> index.map(intFunction)).collect(Collectors.toUnmodifiableSet()));
    }
}
