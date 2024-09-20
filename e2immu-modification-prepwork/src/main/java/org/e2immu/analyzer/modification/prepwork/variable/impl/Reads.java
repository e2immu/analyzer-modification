package org.e2immu.analyzer.modification.prepwork.variable.impl;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Reads {

    public static final Reads NOT_YET_READ = new Reads(List.of());

    private final List<String> indices;

    public Reads(List<String> indices) {
        this.indices = indices;
    }

    public Reads(String readIndexOrNull) {
        this.indices = readIndexOrNull == null ? List.of() : List.of(readIndexOrNull);
    }

    public boolean between(String fromIncl, String toExcl) {
        int pos0 = Collections.binarySearch(indices, fromIncl);
        String s0 = indices.get(pos0 >= 0 ? pos0 : -(pos0 + 1));
        return s0.compareTo(fromIncl) >= 0 && s0.compareTo(toExcl) < 0;
    }

    public List<String> indices() {
        return indices;
    }

    public Reads with(String i) {
        assert indices.isEmpty() || i.compareTo(indices.get(indices.size() - 1)) > 0;
        return new Reads(Stream.concat(indices.stream(), Stream.of(i)).toList());
    }

    @Override
    public String toString() {
        if(indices.isEmpty()) return "-";
        return String.join(", ", indices);
    }
}
