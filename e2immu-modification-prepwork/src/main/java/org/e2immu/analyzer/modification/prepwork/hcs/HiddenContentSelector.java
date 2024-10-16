package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Index;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;

import java.util.*;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

/*
Numeric encoding of the Indices:

natural numbers: type parameters
-1: the object itself, ALL

as opposed to the numeric encoding of HCT:
natural numbers: hidden content type indices, starting with type parameters from hierarchy,
end with extensible fields and  method parameters
-1: the object itself, when extensible

 */
public class HiddenContentSelector implements Value {
    public static final HiddenContentSelector NONE = new HiddenContentSelector();
    public static final PropertyImpl HCS_METHOD = new PropertyImpl("hcsMethod", NONE);
    public static final PropertyImpl HCS_PARAMETER = new PropertyImpl("hcsParameter", NONE);

    private final HiddenContentTypes hiddenContentTypes;
    /*
    map key: the index in HCT
    map value: how to extract from the type
     */
    private final Map<Integer, Indices> map;

    // only used for NONE
    private HiddenContentSelector() {
        map = Map.of();
        hiddenContentTypes = HiddenContentTypes.OF_PRIMITIVE;
    }

    // note: if map is empty, isNone() will be true
    public HiddenContentSelector(HiddenContentTypes hiddenContentTypes, Map<Integer, Indices> map) {
        this.map = map;
        this.hiddenContentTypes = hiddenContentTypes;
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        Map<Codec.EncodedValue, Codec.EncodedValue> mapOfEncoded = map.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> codec.encodeInt(context, e.getKey()),
                        e -> e.getValue().encode(codec, context)));
        if (mapOfEncoded.isEmpty()) return null;
        return codec.encodeMap(context, mapOfEncoded);
    }

    public static HiddenContentSelector decode(Codec codec, Codec.Context context, Codec.EncodedValue encodedValue) {
        Map<Codec.EncodedValue, Codec.EncodedValue> map = codec.decodeMap(context, encodedValue);
        Map<Integer, Indices> intIndices = new HashMap<>();
        for (Map.Entry<Codec.EncodedValue, Codec.EncodedValue> entry : map.entrySet()) {
            int i = codec.decodeInt(context, entry.getKey());
            Indices indices = IndicesImpl.decode(codec, context, entry.getValue());
            intIndices.put(i, indices);
        }
        HiddenContentTypes hct;
        if (context.methodBeforeType()) {
            MethodInfo methodInfo = context.currentMethod();
            hct = methodInfo.analysis().getOrCreate(HIDDEN_CONTENT_TYPES,
                    () -> HiddenContentTypes.of(methodInfo, Map.of()));
        } else {
            hct = context.currentType().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        }
        assert hct != null;
        return new HiddenContentSelector(hct, Map.copyOf(intIndices));
    }

    public HiddenContentTypes hiddenContentTypes() {
        return hiddenContentTypes;
    }

    // for testing
    public static HiddenContentSelector selectTypeParameter(HiddenContentTypes hiddenContentTypes, int i) {
        return new HiddenContentSelector(hiddenContentTypes, Map.of(i, new IndicesImpl(i)));
    }

    @Override
    public String toString() {
        return toString(false);
    }

    public String detailed() {
        return toString(true);
    }

    private String toString(boolean detailed) {
        if (map.isEmpty()) {
            return "X"; // None
        }
        return map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> print(e.getKey(), e.getValue(), detailed))
                .collect(Collectors.joining(","));
    }

    /*
    '*' means: the whole object; otherwise, we're digging deeper
     */
    private static String print(int i, Indices indices, boolean detailed) {
        if (ALL_INDICES.equals(indices)) return detailed ? i + "=*" : "*";
        if (FIELD_INDICES.equals(indices)) return detailed ? i + "=F" : "F";
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HiddenContentSelector hcs = (HiddenContentSelector) o;
        return hiddenContentTypes.equals(hcs.hiddenContentTypes) && map.equals(hcs.map);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hiddenContentTypes, map);
    }

    public Map<Indices, ParameterizedType> extract(Runtime runtime, ParameterizedType type) {
        assert this != NONE;
        return map.values().stream()
                .filter(i -> !FIELD_INDICES.equals(i))
                .collect(Collectors.toUnmodifiableMap(i -> i,
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
            Indices indices = map.values().stream().findFirst().orElseThrow();
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
        assert hiddenContentTypes != null && typeIn != null;
        if (typeIn.typeInfo() == null && typeIn.typeParameter() == null) {
            // type is "?", or "?" extends Object
            return new HiddenContentSelector(hiddenContentTypes,
                    Map.of(HiddenContentTypes.UNSPECIFIED_EXTENSION, new IndicesImpl(UNSPECIFIED)));
        }

        boolean haveArrays = typeIn.arrays() > 0;
        ParameterizedType type = typeIn.copyWithoutArrays();
        Integer index = hiddenContentTypes.indexOfOrNull(type);
        Map<Integer, Indices> map = new HashMap<>();

        if (index != null) {
            if (haveArrays) {
                map.put(index, new IndicesImpl(index));
            } else {
                map.put(index, ALL_INDICES);
            }
        }
        if (type.typeInfo() != null && type.typeInfo().equals(hiddenContentTypes.getTypeInfo())) {
            // we're on our own type, add indices for types -> FIELD
            hiddenContentTypes.typesOfExtensibleFields().forEach(e -> map.put(e.getValue(), IndicesImpl.FIELD_INDICES));
        }
        if (type.typeInfo() != null && !haveArrays) {
            recursivelyCollectHiddenContentParameters(hiddenContentTypes, type, new Stack<>(), map);
        }
        return new HiddenContentSelector(hiddenContentTypes, Map.copyOf(map));
    }

    private static void recursivelyCollectHiddenContentParameters(HiddenContentTypes hiddenContentTypes,
                                                                  ParameterizedType type,
                                                                  Stack<Integer> prefix,
                                                                  Map<Integer, Indices> map) {
        Integer index = hiddenContentTypes.indexOfOrNull(type.copyWithoutArrays());
        if (index != null && type.parameters().isEmpty() && !prefix.isEmpty()) {
            map.merge(index, new IndicesImpl(Set.of(new IndexImpl(List.copyOf(prefix)))), Indices::merge);
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
        return map.isEmpty();
    }

    // useful for testing
    public boolean isOnlyAll() {
        return map.keySet().size() == 1
               && map.entrySet().stream().findFirst().orElseThrow().getValue().equals(ALL_INDICES);
    }

     /*
     The hidden content selector's hct indices (the keys in the map) are computed with respect to 'this'.
     They map to indices (the values in the map) which exist in 'from'.

     'to' is a concrete type for 'from'. We'll map the indices of the selector to indices wrt the formal
     type of to, also attaching the concrete types at those indices.

     E.g. method context is the type ArrayList<EA>.new ArrayList<>(Collection<? extends EA>)
     concrete constructor call is new ArrayList<>(List<M>)

     'this' is with respect to ArrayList<EA> and the constructor, mapping EA=0 (and EL=0, EC=0 for List, Collection)
     'from' is Collection<? extends EA>, formal type Collection<EC>.
     The hidden content selector is 0=0.

     'to' is List<M>, with formal type List<EL>.
     The result maps 'indices' 0 to the combination of "M" and indices 0.
    */

    // FIXME move to HCS, and use the HCT of the HCS
    public record IndicesAndType(Indices indices, ParameterizedType type) {
    }

    public static Map<Indices, IndicesAndType> translateHcs(Runtime runtime,
                                                            GenericsHelper genericsHelper,
                                                            HiddenContentSelector hiddenContentSelector,
                                                            ParameterizedType from,
                                                            ParameterizedType to) {
        if (hiddenContentSelector.isNone()) return Map.of();
        Map<Indices, ParameterizedType> map1 = hiddenContentSelector.extract(runtime, from);
        Map<Indices, IndicesAndType> result = new HashMap<>();
        for (Map.Entry<Indices, ParameterizedType> entry1 : map1.entrySet()) {
            IndicesAndType iat;
            if (from.arrays() > 0 && hiddenContentSelector.selectArrayElement(from.arrays())) {
                Indices indices = new IndicesImpl(Set.of(IndexImpl.createZeroes(from.arrays())));
                iat = new IndicesAndType(indices, to);
            } else if (from.typeParameter() != null || from.equals(to)) {
                iat = new IndicesAndType(entry1.getKey(), to);
            } else {
                iat = findAll(runtime, genericsHelper, entry1.getKey(), entry1.getValue(), from, to);
            }
            result.put(entry1.getKey(), iat);
        }
        return Map.copyOf(result);
    }

    /*
    if what is a type parameter, is will be with respect to the formal type of that level of generics.

    what == the result of 'ptInFrom in from' translated to ('to' == where)

    Given what=EL, with where==List<String>, return 0,String
    Given what=K in Map.Entry, with where = Set<Map.Entry<A,B>>, return 0.0,A

    If from=Set<Set<K>>, and we extract K at 0.0
    If to=Collection<ArrayList<String>>, we'll have to return 0.0, String
     */
    private static IndicesAndType findAll(Runtime runtime,
                                          GenericsHelper genericsHelper,
                                          Indices indices,
                                          ParameterizedType ptInFrom,
                                          ParameterizedType from,
                                          ParameterizedType to) {
        // it does not matter with which index we start
        Index index = indices.set().stream().findFirst().orElseThrow();
        IndicesAndType res = findAll(runtime, genericsHelper, index, 0, ptInFrom, from, to);
        // but once we have found it, we must make sure that we return all occurrences
        assert res.indices.set().size() == 1;
        assert res.type != null;
        Indices findAll = IndicesImpl.staticAllOccurrencesOf(res.type, to);
        return new IndicesAndType(findAll, res.type);
    }


    private static IndicesAndType findAll(Runtime runtime,
                                          GenericsHelper genericsHelper,
                                          Index index,
                                          int pos,
                                          ParameterizedType ptFrom,
                                          ParameterizedType from,
                                          ParameterizedType to) {
        int atPos = index.list().get(pos);
        if (pos == index.list().size() - 1) {
            // the last entry
            assert from.typeInfo() != null;
            ParameterizedType formalFrom = from.typeInfo().asParameterizedType(runtime);
            assert formalFrom.parameters().get(atPos).equals(ptFrom);
            if (formalFrom.typeInfo() == to.typeInfo()) {
                ParameterizedType concrete;
                if (atPos >= to.parameters().size()) {
                    // type parameters are missing, we'd expect <> so that they get filled in automatically
                    concrete = runtime.objectParameterizedType();
                } else {
                    concrete = to.parameters().get(atPos);
                }
                return new IndicesAndType(new IndicesImpl(Set.of(index)), concrete);
            }
            ParameterizedType formalTo = to.typeInfo().asParameterizedType(runtime);
            Map<NamedType, ParameterizedType> map1;
            if (formalFrom.isAssignableFrom(runtime, formalTo)) {
                map1 = genericsHelper.mapInTermsOfParametersOfSuperType(to.typeInfo(), formalFrom);
            } else {
                map1 = genericsHelper.mapInTermsOfParametersOfSubType(from.typeInfo(), formalTo);
            }
            assert map1 != null;
            ParameterizedType ptTo = map1.get(ptFrom.namedType());
            assert ptTo != null;
            HiddenContentTypes hct = to.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
            int iTo = hct.indexOf(ptTo);
            Index indexTo = index.replaceLast(iTo);
            Indices indicesTo = new IndicesImpl(Set.of(indexTo));
            Map<NamedType, ParameterizedType> map2 = to.initialTypeParameterMap(runtime);
            ParameterizedType concreteTypeTo = map2.get(ptTo.namedType());
            assert concreteTypeTo != null;
            return new IndicesAndType(indicesTo, concreteTypeTo);
        }
        if (from.typeInfo() == to.typeInfo()) {
            ParameterizedType inFrom = from.parameters().get(atPos);
            ParameterizedType inTo = to.parameters().get(atPos);
            return findAll(runtime, genericsHelper, index, pos + 1, ptFrom, inFrom, inTo);
        }
        throw new UnsupportedOperationException();
    }
}