package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.NamedType;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.type.TypeParameter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.HiddenContentTypes.NO_VALUE;

public class ComputeHiddenContent {
    private final Runtime runtime;

    public ComputeHiddenContent(Runtime runtime) {
        this.runtime = runtime;
    }

    // accessible, not accessible

    public HiddenContentTypes compute(TypeInfo typeInfo) {
        return compute(typeInfo, true, null);
    }

    public HiddenContentTypes compute(HiddenContentTypes hcsTypeInfo, MethodInfo methodInfo) {
        assert hcsTypeInfo != null : "For method " + methodInfo;

        Map<NamedType, Integer> typeToIndex = new HashMap<>();
        int max = 0;
        for (TypeParameter tp : methodInfo.typeParameters()) {
            typeToIndex.put(tp, tp.getIndex());
            max = Math.max(max, tp.getIndex());
        }
        // are any of the parameter's type's a type parameter, not yet used in the fields? See resolve.Method_15
        for (ParameterInfo pi : methodInfo.parameters()) {
            TypeParameter tp = pi.parameterizedType().typeParameter();
            if (tp != null && tp.getOwner().isLeft() && !hcsTypeInfo.getTypeToIndex().containsKey(tp)) {
                typeToIndex.put(tp, ++max);
            }
        }
        return new HiddenContentTypes(hcsTypeInfo, methodInfo, typeToIndex);
    }

    // private static Stream<ParameterizedType> expand(ParameterizedType pt) {

    //  }

    public HiddenContentTypes compute(TypeInfo typeInfo, boolean shallow) {
        return compute(typeInfo, shallow, null);
    }

    private HiddenContentTypes compute(TypeInfo typeInfo,
                                       boolean shallow,
                                       Set<TypeInfo> cycleProtection) {

        int offset;
        Map<NamedType, Integer> fromEnclosing;
        if (typeInfo.isInnerClass()) {
            HiddenContentTypes hct;
            MethodInfo enclosingMethod = typeInfo.enclosingMethod();
            Stream<Map.Entry<Integer, NamedType>> indexToTypeStream;
            if (enclosingMethod != null) {
                hct = getOrCompute(enclosingMethod, shallow, cycleProtection);
                indexToTypeStream = Stream.concat(hct.getHcsTypeInfo().getIndexToType().entrySet().stream(),
                        hct.getIndexToType().entrySet().stream());
            } else {
                TypeInfo enclosing = typeInfo.compilationUnitOrEnclosingType().getRight();
                hct = getOrCompute(enclosing, shallow, cycleProtection);
                indexToTypeStream = hct == null ? Stream.of() : hct.getIndexToType().entrySet().stream();
            }
            offset = hct == null ? 0 : hct.size();
            fromEnclosing = indexToTypeStream.collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
        } else {
            offset = 0;
            fromEnclosing = Map.of();
        }

        Set<TypeParameter> typeParametersInFields;
        if (shallow) {
            typeParametersInFields = typeInfo.typeParameters().stream().collect(Collectors.toUnmodifiableSet());
        } else {
            typeParametersInFields = typeInfo.fields().stream()
                    .flatMap(fi -> typeParameterStream(fi.type()))
                    .collect(Collectors.toUnmodifiableSet());
        }
        Map<NamedType, Integer> fromThis = typeParametersInFields.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> tp.getIndex() + offset, (tp1, tp2) -> tp1, HashMap::new));
        fromThis.putAll(fromEnclosing);

        Map<NamedType, Integer> superTypeToIndex = new HashMap<>();
        if (typeInfo.parentClass() != null && !typeInfo.parentClass().isJavaLangObject()) {
            addFromSuperType(typeInfo.parentClass(), shallow, fromThis,
                    superTypeToIndex, cycleProtection);
        }
        for (ParameterizedType interfaceType : typeInfo.interfacesImplemented()) {
            addFromSuperType(interfaceType, shallow, fromThis, superTypeToIndex, cycleProtection);
        }
        /*
         Finally, we add hidden content types from extensible fields without type parameters.

         NOTE: Linking to extensible fields with type parameters is done at the level of those type parameters ONLY
         in the current implementation, which does not allow for the combination of ALL and CsSet.
         */
        if (!shallow) {
            for (FieldInfo f : typeInfo.fields()) {
                addExtensible(f.type(), fromThis, shallow, cycleProtection);
            }
        }
        return new HiddenContentTypes(typeInfo, typeInfo.isExtensible(), Map.copyOf(fromThis),
                Map.copyOf(superTypeToIndex));
    }

    /*
    If a type is not extensible, it may still have hidden content (e.g. a final List<T> implementation).
    Problem is that "hasHiddenContent" is a computation that requires HCT, which can lead to cycles in the computation.
    If we encounter a cycle, we must stop and assume that there is no hidden content.

    FIXME we must not add types whose sole hidden content is already present!
     */
    private void addExtensible(ParameterizedType type,
                               Map<NamedType, Integer> fromThis,
                               boolean shallow,
                               Set<TypeInfo> cycleProtection) {
        if (!type.isTypeParameter()) {
            TypeInfo bestType = Objects.requireNonNullElse(type.bestTypeInfo(), runtime.objectTypeInfo());
            boolean hasHiddenContent;
            if (bestType.isExtensible()) {
                hasHiddenContent = true;
            } else {
                HiddenContentTypes hct = getOrCompute(bestType, shallow, cycleProtection);
                hasHiddenContent = hct != null && hct.hasHiddenContent();
            }
            if (hasHiddenContent) {
                int index = fromThis.size();
                fromThis.putIfAbsent(bestType, index);
            }
            for (ParameterizedType pt : type.parameters()) {
                addExtensible(pt, fromThis, shallow, cycleProtection);
            }
        }
    }

    private void addFromSuperType(ParameterizedType superType,
                                  boolean shallow,
                                  Map<NamedType, Integer> fromThis,
                                  Map<NamedType, Integer> superTypeToIndex,
                                  Set<TypeInfo> cycleProtection) {
        HiddenContentTypes hctParent = getOrCompute(superType.typeInfo(), shallow, cycleProtection);
        if (hctParent != null) {
            if (!hctParent.isEmpty()) {
                Map<NamedType, ParameterizedType> fromMeToParent = superType.initialTypeParameterMap(runtime);
                assert fromMeToParent != null;
                // the following include all recursively computed
                for (Map.Entry<NamedType, Integer> e : hctParent.getTypeToIndex().entrySet()) {
                    NamedType typeInParent = hctParent.getIndexToType().get(e.getValue()); // this step is necessary for recursively computed...
                    ParameterizedType typeHere = fromMeToParent.get(typeInParent);
                    if (typeHere != null) {
                        NamedType namedTypeHere = typeHere.namedType();
                        Integer indexHere = fromThis.get(namedTypeHere);
                        if (indexHere == null && !shallow) {
                            // no field with this type, but we must still add it, as it exists in the parent
                            // if shallow, it has already been added, there is no check on fields
                            indexHere = fromThis.size();
                            fromThis.put(namedTypeHere, indexHere);
                        }
                        if (indexHere != null) {
                            superTypeToIndex.put(e.getKey(), indexHere);
                        }
                    } // see e.g. resolve.Constructor_2
                }
            }
        } //running against the cycle protection... bail out
    }

    private HiddenContentTypes getOrCompute(TypeInfo enclosing,
                                            boolean shallow,
                                            Set<TypeInfo> cycleProtection) {
        HiddenContentTypes hct = enclosing.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        if (hct != NO_VALUE) {
            return hct;
        }
        if (cycleProtection != null && !cycleProtection.add(enclosing)) {
            return null; // cannot compute anymore!!
        }
        Set<TypeInfo> cp = cycleProtection == null ? new HashSet<>() : cycleProtection;
        return compute(enclosing, shallow, cp);
    }

    private HiddenContentTypes getOrCompute(MethodInfo enclosing,
                                            boolean shallow,
                                            Set<TypeInfo> cycleProtection) {
        HiddenContentTypes hct = enclosing.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        if (hct != NO_VALUE) {
            return hct;
        }
        HiddenContentTypes hctTypeInfo = getOrCompute(enclosing.typeInfo(), shallow, cycleProtection);
        return compute(hctTypeInfo, enclosing);
    }

    private Stream<TypeParameter> typeParameterStream(ParameterizedType type) {
        if (type.isTypeParameter()) return Stream.of(type.typeParameter());
        return type.parameters().stream().flatMap(this::typeParameterStream);
    }

}
