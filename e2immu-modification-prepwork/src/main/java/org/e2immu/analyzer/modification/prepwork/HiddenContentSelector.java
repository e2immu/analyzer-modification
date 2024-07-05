package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import java.util.*;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.prepwork.Indices.ALL_INDICES;
import static org.e2immu.analyzer.modification.prepwork.Indices.UNSPECIFIED;

/*
Numeric encoding of the Indices:

natural numbers: type parameters
-1: the object itself, ALL

as opposed to the numeric encoding of HCT:
natural numbers: hidden content type indices, starting with type parameters from hierarchy,
end with extensible fields and  method parameters
-1: the object itself, when extensible

 */
public class HiddenContentSelector {
    public static final HiddenContentSelector NONE = new HiddenContentSelector();

    private final CausesOfDelay causesOfDelay;
    private final HiddenContentTypes hiddenContentTypes;
    /*
    map key: the index in HCT
    map value: how to extract from the type
     */
    private final Map<Integer, Indices> map;

    // only used for NONE
    private HiddenContentSelector() {
        map = Map.of();
        causesOfDelay = CausesOfDelay.EMPTY;
        hiddenContentTypes = HiddenContentTypes.OF_PRIMITIVE;
    }

    public HiddenContentSelector(HiddenContentTypes hiddenContentTypes, CausesOfDelay causes) {
        this.map = Map.of();
        this.hiddenContentTypes = hiddenContentTypes;
        this.causesOfDelay = causes;
        assert causes.isDelayed();
    }

    // note: if map is empty, isNone() will be true
    public HiddenContentSelector(HiddenContentTypes hiddenContentTypes, Map<Integer, Indices> map) {
        this.map = map;
        this.hiddenContentTypes = hiddenContentTypes;
        this.causesOfDelay = CausesOfDelay.EMPTY;
    }

    public HiddenContentTypes hiddenContentTypes() {
        return hiddenContentTypes;
    }

    // for testing
    public static HiddenContentSelector selectTypeParameter(HiddenContentTypes hiddenContentTypes, int i) {
        return new HiddenContentSelector(hiddenContentTypes, Map.of(i, new Indices(i)));
    }

    @Override
    public String toString() {
        return toString(false);
    }

    public String detailed() {
        return toString(true);
    }

    private String toString(boolean detailed) {
        if (causesOfDelay.isDelayed()) {
            return causesOfDelay.toString();
        }
        if (map.isEmpty()) {
            return "X"; // None
        }
        return map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> print(e.getKey(), e.getValue(), detailed))
                .collect(Collectors.joining(","));
    }

    private static String print(int i, Indices indices, boolean detailed) {
        if (ALL_INDICES.equals(indices)) return detailed ? i + "=*" : "*";
        String is = indices.toString();
        String iToString = "" + i;
        if (!detailed && is.equals(iToString)) return iToString;
        return iToString + "=" + is;
    }

    public Set<Integer> set() {
        return map.keySet();
    }

    public Map<Integer, Indices> getMap() {
        return map;
    }

    public boolean isDelayed() {
        return causesOfDelay().isDelayed();
    }

    public CausesOfDelay causesOfDelay() {
        return causesOfDelay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HiddenContentSelector hcs = (HiddenContentSelector) o;
        return hiddenContentTypes.equals(hcs.hiddenContentTypes)
               && causesOfDelay.equals(hcs.causesOfDelay)
               && map.equals(hcs.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(causesOfDelay, hiddenContentTypes, map);
    }

    public Map<Indices, ParameterizedType> extract(Runtime runtime, ParameterizedType type) {
        assert this != NONE;
        return map.values().stream().collect(Collectors.toUnmodifiableMap(i -> i,
                i -> {
                    Integer single = i.single();
                    TypeInfo fieldType = hiddenContentTypes.isExtensible(single);
                    if (fieldType != null) {
                        return fieldType.asSimpleParameterizedType();
                    }
                    Integer index = hiddenContentTypes.indexOfOrNull(type);
                    if (index != null) {
                        return type;
                    }
                    return i.findInFormal(runtime, type);
                }));
    }

    public boolean selectArrayElement(int arrays) {
        if (map.size() == 1) {
            Indices indices = map.get(0);
            return indices.set().size() == 1
                   && indices.set().stream().findFirst().orElseThrow().countSequentialZeros() == arrays;
        }
        return false;
    }

    public HiddenContentSelector correct(Map<Integer, Integer> mapMethodHCTIndexToTypeHCTIndex) {
        Map<Integer, Indices> newMap = map.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                e -> mapMethodHCTIndexToTypeHCTIndex.getOrDefault(e.getKey(), e.getKey()),
                Map.Entry::getValue, (i1, i2) -> i1));
        return new HiddenContentSelector(hiddenContentTypes, newMap);
    }


    /*
     Take in a type, and return the hidden content components of this type, with respect to the hidden content types
     of the current type or method.
     Set<Map.Entry<K, V>> will return the indices of K and V mapped to their position in the formal type,
     i.e., 0 -> 0.0, 1 -> 0.1
     Collection<V> will return 1 -> 0

     in the following context:
     static <X> X method(...) { Supplier<X> s = new Supplier<>() { ... }}
     the anonymous type $1 has no direct type parameters, but its enclosing method does. We'll replace $2 by
     Supplier<X>.
     */
    public static HiddenContentSelector selectAll(HiddenContentTypes hiddenContentTypes,
                                                  ParameterizedType typeIn) {
        assert hiddenContentTypes != null;
        boolean haveArrays = typeIn.arrays() > 0;
        ParameterizedType type = typeIn.copyWithoutArrays();
        Integer index = hiddenContentTypes.indexOfOrNull(type);
        Map<Integer, Indices> map = new HashMap<>();

        if (index != null) {
            if (haveArrays) {
                map.put(index, new Indices(index));
            } else {
                map.put(index, ALL_INDICES);
            }
        }
        if (type.typeParameter() == null) {
            // not a type parameter
            if (type.typeInfo() == null) {
                // ?, equivalent to ? extends Object
                return new HiddenContentSelector(hiddenContentTypes,
                        Map.of(HiddenContentTypes.UNSPECIFIED_EXTENSION, new Indices((UNSPECIFIED))));
            } else if (type.arrays() == 0) {
                recursivelyCollectHiddenContentParameters(hiddenContentTypes, type, new Stack<>(), map);
                hiddenContentTypes.typesOfExtensibleFields()
                        .forEach(e -> map.put(e.getValue(), new Indices(e.getValue())));
            } // else: we don't combine type parameters and arrays for now
        }
        return new HiddenContentSelector(hiddenContentTypes, Map.copyOf(map));
    }

    private static void recursivelyCollectHiddenContentParameters(HiddenContentTypes hiddenContentTypes,
                                                                  ParameterizedType type,
                                                                  Stack<Integer> prefix,
                                                                  Map<Integer, Indices> map) {
        Integer index = hiddenContentTypes.indexOfOrNull(type.copyWithoutArrays());
        if (index != null && type.parameters().isEmpty() && !prefix.isEmpty()) {
            map.merge(index, new Indices(Set.of(new Index(List.copyOf(prefix)))), Indices::merge);
        } else {
            int i = 0;
            for (ParameterizedType parameter : type.parameters()) {
                prefix.push(i);
                recursivelyCollectHiddenContentParameters(hiddenContentTypes, parameter, prefix, map);
                prefix.pop();
                i++;
            }
        }
    }

    public boolean isNone() {
        return map.isEmpty() && causesOfDelay.isDone();
    }

    // useful for testing
    public boolean isOnlyAll() {
        return causesOfDelay.isDone() && map.keySet().size() == 1 &&
               map.entrySet().stream().findFirst().orElseThrow().getValue().equals(ALL_INDICES);
    }
}