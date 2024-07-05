package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.type.TypeParameter;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.inspection.api.parser.GenericsHelper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HiddenContentTypes implements Value {
    public static HiddenContentTypes OF_PRIMITIVE = new HiddenContentTypes(null, false, Map.of(), Map.of());
    public static HiddenContentTypes NO_VALUE = new HiddenContentTypes(null, false, Map.of(), Map.of());

    public static PropertyImpl HIDDEN_CONTENT_TYPES = new PropertyImpl("hiddenContentTypesOfType", NO_VALUE);

    public static final int UNSPECIFIED_EXTENSION = -2; // extension of the type itself, only if typeIsExtensible == true

    private final TypeInfo typeInfo;
    private final boolean typeIsExtensible;
    private final int startOfMethodParameters;

    private final HiddenContentTypes hcsTypeInfo;
    private final MethodInfo methodInfo;

    private final Map<NamedType, Integer> typeToIndex;
    private final Map<Integer, NamedType> indexToType;

    HiddenContentTypes(TypeInfo typeInfo,
                       boolean typeIsExtensible,
                       Map<NamedType, Integer> myTypeToIndex,
                       Map<NamedType, Integer> superTypeToIndex) {
        this.typeInfo = typeInfo;
        this.typeIsExtensible = typeIsExtensible;
        Map<NamedType, Integer> combined = new HashMap<>(myTypeToIndex);
        combined.putAll(superTypeToIndex);
        this.typeToIndex = Map.copyOf(combined);
        this.indexToType = myTypeToIndex.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
        startOfMethodParameters = myTypeToIndex.size();
        methodInfo = null;
        hcsTypeInfo = null;
    }

    HiddenContentTypes(HiddenContentTypes hcsTypeInfo,
                       MethodInfo methodInfo,
                       Map<NamedType, Integer> typeToIndexIn) {
        this.typeIsExtensible = hcsTypeInfo.typeIsExtensible;
        this.hcsTypeInfo = hcsTypeInfo;
        this.typeInfo = hcsTypeInfo.typeInfo;
        this.methodInfo = methodInfo;
        assert typeInfo == methodInfo.typeInfo()
                : "HCS typeInfo = " + typeInfo + ", method type info = " + methodInfo.typeInfo();
        this.startOfMethodParameters = hcsTypeInfo.startOfMethodParameters;
        Map<Integer, NamedType> i2t = new HashMap<>();
        Map<NamedType, Integer> t2i = new HashMap<>();
        for (Map.Entry<NamedType, Integer> entry : typeToIndexIn.entrySet()) {
            NamedType nt = entry.getKey();
            if (!hcsTypeInfo.typeToIndex.containsKey(nt)) {
                int i = startOfMethodParameters + entry.getValue();
                i2t.put(i, nt);
                t2i.put(nt, i);
            }
        }
        indexToType = Map.copyOf(i2t);
        typeToIndex = Map.copyOf(t2i);
    }

    @Override
    public Codec.EncodedValue encode(Codec codec) {
        return null;
    }

    public boolean forMethod() {
        return methodInfo != null;
    }


    public boolean hasHiddenContent() {
        return typeIsExtensible || size() > 0;
    }

    private static NamedType namedType(ParameterizedType type) {
        if (type.typeParameter() != null) return type.typeParameter();
        if (type.typeInfo() != null) return type.typeInfo();
        return null;
    }

    public boolean isEmpty() {
        return (hcsTypeInfo == null || hcsTypeInfo.isEmpty()) && indexToType.isEmpty();
    }

    @Override
    public String toString() {
        String s = hcsTypeInfo == null ? "" : hcsTypeInfo + " - ";
        String l = hcsTypeInfo == null ? typeInfo.simpleName() : methodInfo.name();
        return s + l + ":" + indexToType.values().stream()
                .map(NamedType::simpleName).sorted().collect(Collectors.joining(", "));
    }

    public int size() {
        return (hcsTypeInfo == null ? 0 : hcsTypeInfo.size()) + indexToType.size();
    }

    /*
    make a translation map based on pt2, and translate from formal to concrete.

    If types contains E=formal type parameter of List<E>, and pt = List<T>, we want
    to return a HiddenContentTypes containing T instead of E
     */
    public HiddenContentTypes translate(Runtime runtime, ParameterizedType pt) {
        Map<NamedType, ParameterizedType> map = pt.initialTypeParameterMap(runtime);
        Set<ParameterizedType> newTypes = typeToIndex.keySet().stream()
                .map(t -> translate(runtime, pt, map, t))
                .collect(Collectors.toUnmodifiableSet());
        // return new HiddenContentTypes(newTypes);
        throw new UnsupportedOperationException();
    }

    private ParameterizedType translate(Runtime runtime, ParameterizedType pt,
                                        Map<NamedType, ParameterizedType> map,
                                        NamedType t) {
        if (map.isEmpty() && t instanceof TypeParameter tp
            && tp.asSimpleParameterizedType().isAssignableFrom(runtime, pt)) {
            return pt;
        }
        //  return t.applyTranslation(inspectionProvider.getPrimitives(), map);
        throw new UnsupportedOperationException();
    }

    public NamedType typeByIndex(int i) {
        NamedType here = indexToType.get(i);
        if (here != null) return here;
        if (hcsTypeInfo != null) {
            return hcsTypeInfo.indexToType.get(i);
        }
        return null;
    }

    public int indexOf(ParameterizedType type) {
        return indexOfOrNull(type);
    }

    public int indexOf(NamedType namedType) {
        Integer here = typeToIndex.get(namedType);
        if (here != null) return here;
        if (hcsTypeInfo != null) return hcsTypeInfo.typeToIndex.get(namedType);
        throw new UnsupportedOperationException("Expected " + namedType + " to be known");
    }

    public Integer indexOfOrNull(ParameterizedType type) {
        NamedType namedType = namedType(type);
        if (namedType == null) return null;
        Integer here = typeToIndex.get(namedType);
        if (here != null) return here;
        if (hcsTypeInfo != null) return hcsTypeInfo.typeToIndex.get(namedType);
        return null;
    }

    public Collection<NamedType> types() {
        return indexToType.values();
    }

    public String sortedTypes() {
        String s = forMethod() ? hcsTypeInfo.sortedTypes() + " - " : "";
        return s + indexToType.values().stream()
                .map(NamedType::simpleName).sorted().collect(Collectors.joining(", "));
    }

    public String detailedSortedTypes() {
        String s = forMethod() ? hcsTypeInfo.detailedSortedTypes() + " - " : "";
        return s + indexToType.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().simpleName())
                .sorted().collect(Collectors.joining(", "));
    }


    public Map<Integer, Integer> mapMethodToTypeIndices(ParameterizedType parameterizedType) {
        // FIXME this is not a good implementation
        Map<Integer, Integer> result = new HashMap<>();
        for (int i : indexToType.keySet()) {
            result.put(i, i - startOfMethodParameters);
        }
        return Map.copyOf(result);
    }

    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    public TypeInfo isExtensible(Integer single) {
        if (single == null) return null;
        NamedType nt = typeByIndex(single);
        return nt instanceof TypeInfo ti ? ti : null;
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

    public Map<Indices, IndicesAndType> translateHcs(Runtime runtime,
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
                Indices indices = new Indices(Set.of(Index.createZeroes(from.arrays())));
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
        Indices findAll = Indices.allOccurrencesOf(res.type, to);
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
                return new IndicesAndType(new Indices(Set.of(index)), concrete);
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
            HiddenContentTypes hct = to.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
            int iTo = hct.indexOf(ptTo);
            Index indexTo = index.replaceLast(iTo);
            Indices indicesTo = new Indices(Set.of(indexTo));
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

    public HiddenContentTypes getHcsTypeInfo() {
        return hcsTypeInfo;
    }

    public Map<Integer, NamedType> getIndexToType() {
        return indexToType;
    }

    public TypeInfo getTypeInfo() {
        return typeInfo;
    }

    public Map<NamedType, Integer> getTypeToIndex() {
        return typeToIndex;
    }

    public Stream<Map.Entry<NamedType, Integer>> typesOfExtensibleFields() {
        return typeToIndex.entrySet().stream().filter(e -> e.getKey() instanceof TypeInfo);
    }

    public boolean isTypeIsExtensible() {
        return typeIsExtensible;
    }
}
