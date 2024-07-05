package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import java.util.List;
import java.util.function.IntFunction;

public interface Index extends Comparable<Index> {
    int countSequentialZeros();

    Index dropFirst();

    ParameterizedType find(Runtime ru, ParameterizedType type);

    /*
         extract type given the indices. Switch to formal for the last index in the list only!
         Map.Entry<A,B>, with index 0, will return K in Map.Entry
         Set<Map.Entry<A,B>>, with indices 0.1, will return V in Map.Entry.
         */
    ParameterizedType findInFormal(Runtime runtime, ParameterizedType type);

    List<Integer> list();

    Index map(IntFunction<Integer> intFunction);

    Index prefix(int index);

    Index replaceLast(int v);

    Integer single();

    Index takeFirst();
}
