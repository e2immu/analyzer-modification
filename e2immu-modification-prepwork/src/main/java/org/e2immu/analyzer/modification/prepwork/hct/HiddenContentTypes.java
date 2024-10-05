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
import org.e2immu.language.cst.io.CodecImpl;
import org.parsers.json.ast.StringLiteral;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class HiddenContentTypes implements Value {
    public static HiddenContentTypes OF_PRIMITIVE = new HiddenContentTypes(null, false, Map.of());
    public static HiddenContentTypes NO_VALUE = new HiddenContentTypes(null, false, Map.of());

    public static PropertyImpl HIDDEN_CONTENT_TYPES = new PropertyImpl("hct", NO_VALUE);

    public static final int UNSPECIFIED_EXTENSION = -2; // extension of the type itself, only if typeIsExtensible == true

    private final TypeInfo typeInfo;
    private final boolean typeIsExtensible;
    private final int startOfMethodParameters;

    private final HiddenContentTypes hctTypeInfo;
    private final MethodInfo methodInfo;

    private final Map<NamedType, Integer> typeToIndex;
    private final Map<Integer, NamedType> indexToType;

    static HiddenContentTypes of(TypeInfo typeInfo,
                                 boolean typeIsExtensible,
                                 Map<NamedType, Integer> myTypeToIndex,
                                 Map<NamedType, Integer> superTypeToIndex) {
        Map<NamedType, Integer> combined = new HashMap<>(myTypeToIndex);
        combined.putAll(superTypeToIndex);
        return new HiddenContentTypes(typeInfo, typeIsExtensible, Map.copyOf(combined));
    }

    private HiddenContentTypes(TypeInfo typeInfo, boolean typeIsExtensible, Map<NamedType, Integer> typeToIndex) {
        this.typeInfo = typeInfo;
        this.typeIsExtensible = typeIsExtensible;
        this.typeToIndex = typeToIndex;
        this.indexToType = typeToIndex.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
        this.startOfMethodParameters = typeToIndex.size();
        hctTypeInfo = null;
        methodInfo = null;
    }


    HiddenContentTypes(HiddenContentTypes hctTypeInfo,
                       MethodInfo methodInfo,
                       Map<NamedType, Integer> typeToIndexIn) {
        this.typeIsExtensible = hctTypeInfo.typeIsExtensible;
        this.hctTypeInfo = hctTypeInfo;
        this.typeInfo = hctTypeInfo.typeInfo;
        this.methodInfo = methodInfo;
        assert typeInfo == methodInfo.typeInfo()
                : "HCS typeInfo = " + typeInfo + ", method type info = " + methodInfo.typeInfo();
        this.startOfMethodParameters = hctTypeInfo.startOfMethodParameters;
        Map<Integer, NamedType> i2t = new HashMap<>();
        Map<NamedType, Integer> t2i = new HashMap<>();
        for (Map.Entry<NamedType, Integer> entry : typeToIndexIn.entrySet()) {
            NamedType nt = entry.getKey();
            if (!hctTypeInfo.typeToIndex.containsKey(nt)) {
                int i = startOfMethodParameters + entry.getValue();
                i2t.put(i, nt);
                t2i.put(nt, i);
            }
        }
        indexToType = Map.copyOf(i2t);
        typeToIndex = Map.copyOf(t2i);
    }

    public static HiddenContentTypes decode(Codec codec, Codec.Context context, Codec.EncodedValue encodedValue) {
        Map<Codec.EncodedValue, Codec.EncodedValue> map = codec.decodeMap(context, encodedValue);
        boolean isExtensible = false;
        Map<NamedType, Integer> typeToIndex = new HashMap<>();
        for (Map.Entry<Codec.EncodedValue, Codec.EncodedValue> entry : map.entrySet()) {
            String key = codec.decodeString(context, entry.getKey());
            if ("E".equals(key)) {
                isExtensible = codec.decodeBoolean(context, entry.getValue());
            } else {
                int index = Integer.parseInt(key);
                NamedType namedType;
                if (entry.getValue() instanceof CodecImpl.D d && d.s() instanceof StringLiteral sl) {
                    String string = CodecImpl.unquote(sl.getSource());
                    char first = string.charAt(0);
                    String rest = string.substring(1);
                    if ('T' == first) {
                        namedType = context.findType(rest);
                    } else if ('P' == first) {
                        int colon = rest.lastIndexOf(':');
                        int tpIndex = Integer.parseInt(rest.substring(colon + 1));
                        namedType = context.currentType().typeParameters().get(tpIndex);
                    } else throw new UnsupportedOperationException();
                } else {
                    throw new UnsupportedOperationException();
                }
                typeToIndex.put(namedType, index);
            }
        }
        if (context.methodBeforeType()) {
            HiddenContentTypes hctType = context.currentType().typeInfo().analysis().getOrNull(HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
            assert hctType != null;
            return new HiddenContentTypes(hctType, context.currentMethod(), typeToIndex);
        }
        return new HiddenContentTypes(context.currentType(), isExtensible, typeToIndex);
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        Map<Codec.EncodedValue, Codec.EncodedValue> map = new HashMap<>();
        if (methodInfo == null && typeIsExtensible) {
            map.put(codec.encodeString(context, "E"), codec.encodeBoolean(context, true));
        }
        indexToType.forEach((key, value) -> {
            Codec.EncodedValue v;
            if (value instanceof TypeInfo ti) {
                v = codec.encodeString(context, "T" + ti.fullyQualifiedName());
            } else if (value instanceof TypeParameter tp) {
                v = codec.encodeString(context, "P" + tp.simpleName() + ":" + tp.getIndex());
            } else throw new UnsupportedOperationException();
            map.put(codec.encodeInt(context, key), v);
        });
        if (map.isEmpty()) return null;
        return codec.encodeMap(context, map);
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
        return (hctTypeInfo == null || hctTypeInfo.isEmpty()) && indexToType.isEmpty();
    }

    @Override
    public String toString() {
        String s = hctTypeInfo == null ? "" : hctTypeInfo + " - ";
        String l = hctTypeInfo == null ? typeInfo.simpleName() : methodInfo.name();
        return s + l + ":" + indexToType.values().stream()
                .map(NamedType::simpleName).sorted().collect(Collectors.joining(", "));
    }

    public int size() {
        return (hctTypeInfo == null ? 0 : hctTypeInfo.size()) + indexToType.size();
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
        if (hctTypeInfo != null) {
            return hctTypeInfo.indexToType.get(i);
        }
        return null;
    }

    public int indexOf(ParameterizedType type) {
        return indexOfOrNull(type);
    }

    public int indexOf(NamedType namedType) {
        Integer here = typeToIndex.get(namedType);
        if (here != null) return here;
        if (hctTypeInfo != null) return hctTypeInfo.typeToIndex.get(namedType);
        throw new UnsupportedOperationException("Expected " + namedType + " to be known");
    }

    public Integer indexOfOrNull(ParameterizedType type) {
        NamedType namedType = namedType(type);
        if (namedType == null) return null;
        Integer here = typeToIndex.get(namedType);
        if (here != null) return here;
        if (hctTypeInfo != null) return hctTypeInfo.typeToIndex.get(namedType);
        return null;
    }

    public Collection<NamedType> types() {
        return indexToType.values();
    }

    public String sortedTypes() {
        String s = forMethod() ? hctTypeInfo.sortedTypes() + " - " : "";
        return s + indexToType.values().stream()
                .map(NamedType::simpleName).sorted().collect(Collectors.joining(", "));
    }

    public String detailedSortedTypes() {
        String s = forMethod() ? hctTypeInfo.detailedSortedTypes() + " - " : "";
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

    public HiddenContentTypes getHctTypeInfo() {
        return hctTypeInfo;
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
