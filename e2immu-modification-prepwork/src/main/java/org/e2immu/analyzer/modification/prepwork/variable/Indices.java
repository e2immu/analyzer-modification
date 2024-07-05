package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import java.util.Set;
import java.util.function.IntFunction;

public interface Indices extends Comparable<Indices> {
    Indices merge(Indices indices);

    ParameterizedType findInFormal(Runtime runtime, ParameterizedType type);

    ParameterizedType find(Runtime runtime, ParameterizedType type);

    Indices allOccurrencesOf(Runtime runtime, ParameterizedType where);

    boolean containsSize2Plus();

    Indices size2PlusDropOne();

    Indices first();

    Indices prefix(int index);

    Integer single();

    Indices map(IntFunction<Integer> intFunction);

    Set<Index> set();
}
