package org.e2immu.analyzer.modification.prepwork.hct;

import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.type.TypeParameter;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HiddenContentTypes implements Value {
    public static HiddenContentTypes OF_PRIMITIVE = new HiddenContentTypes(null, false, Map.of(), Map.of());
    public static HiddenContentTypes NO_VALUE = new HiddenContentTypes(null, false, Map.of(), Map.of());

    public static PropertyImpl HIDDEN_CONTENT_TYPES = new PropertyImpl("hct", NO_VALUE);

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
        Map<Codec.EncodedValue, Codec.EncodedValue> map = new HashMap<>();
        if (methodInfo == null) {
            if (typeIsExtensible) {
                map.put(codec.encodeString("E"), codec.encodeBoolean(typeIsExtensible));
            }
            if (startOfMethodParameters != 0) {
                map.put(codec.encodeString("M"), codec.encodeInt(startOfMethodParameters));
            }
        }
        indexToType.forEach((key, value) -> {
            Codec.EncodedValue v;
            if (value instanceof TypeInfo ti) {
                v = codec.encodeInfo(ti);
            } else if (value instanceof TypeParameter tp) {
                v = codec.encodeString(tp.simpleName());
            } else throw new UnsupportedOperationException();
            map.put(codec.encodeInt(key), v);
        });
        if (map.isEmpty()) return null;
        return codec.encodeMap(map);
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
