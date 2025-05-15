package org.e2immu.analyzer.modification.common.defaults;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.annotation.Independent;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.e2immu.analyzer.modification.common.defaults.ShallowAnalyzer.AnnotationOrigin.*;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE_HC;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.NotNullImpl.NOT_NULL;

public class ShallowTypeAnalyzer extends AnnotationToProperty {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShallowTypeAnalyzer.class);
    private final AnalysisHelper analysisHelper = new AnalysisHelper();
    private final AtomicInteger warnings = new AtomicInteger();

    public ShallowTypeAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        super(runtime, annotationProvider);
    }

    public Map<Info, ShallowAnalyzer.InfoData> analyze(TypeInfo typeInfo) {
        LOGGER.debug("Analyzing type {}", typeInfo);
        if (typeInfo.analysis().getOrDefault(DEFAULTS_ANALYZER, FALSE).isTrue()) {
            return Map.of(); // already done
        }
        typeInfo.analysis().set(DEFAULTS_ANALYZER, TRUE);
        Map<Info, ShallowAnalyzer.InfoData> dataMap = new HashMap<>();
        boolean isExtensible = typeInfo.isExtensible();
        List<AnnotationExpression> annotations = annotationProvider.annotations(typeInfo);
        Map<Property, Value> map = annotationsToMap(typeInfo, annotations);

        map.putIfAbsent(CONTAINER_TYPE, FALSE);
        Value.Immutable imm = (Value.Immutable) map.get(IMMUTABLE_TYPE);
        if (imm != null && imm.isImmutable() && isExtensible) {
            map.put(IMMUTABLE_TYPE, IMMUTABLE_HC);
        } else if (imm == null) {
            map.put(IMMUTABLE_TYPE, MUTABLE);
        }
        Value.Independent ind = (Value.Independent) map.get(INDEPENDENT_TYPE);
        if (ind == null) {
            map.put(INDEPENDENT_TYPE, DEPENDENT);
        }
        map.forEach((p, v) -> {
            // not writing out default values, we may want to overwrite in AbstractInfoAnalyzer
            if (!v.isDefault()) typeInfo.analysis().set(p, v);
        });

        boolean immutableDeterminedByTypeParameters = typeInfo.typeParameters().stream()
                .anyMatch(tp -> tp.annotations().stream().anyMatch(ae ->
                        Independent.class.getCanonicalName().equals(ae.typeInfo().fullyQualifiedName())));
        if (immutableDeterminedByTypeParameters) {
            typeInfo.analysis().set(IMMUTABLE_TYPE_DETERMINED_BY_PARAMETERS, TRUE);
        }
        return dataMap;
    }

    public Map<Info, ShallowAnalyzer.InfoData> analyzeFields(TypeInfo typeInfo) {
        boolean isEnum = typeInfo.typeNature().isEnum();
        Value.Immutable ownerImmutable = analysisHelper.typeImmutable(typeInfo.asParameterizedType());
        Map<Info, ShallowAnalyzer.InfoData> dataMap = new HashMap<>();
        for (FieldInfo fieldInfo : typeInfo.fields()) {
            if (!fieldInfo.access().isPublic()) continue;
            if (fieldInfo.analysis().getOrDefault(DEFAULTS_ANALYZER, FALSE).isTrue()) {
                continue; // already done
            }
            fieldInfo.analysis().set(DEFAULTS_ANALYZER, TRUE);

            List<AnnotationExpression> fieldAnnotations = annotationProvider.annotations(fieldInfo);
            Map<Property, Value> annotatedMap = annotationsToMap(fieldInfo, fieldAnnotations);
            Map<Property, ValueOrigin> fieldMap = new HashMap<>();
            annotatedMap.forEach((p, v) -> fieldMap.put(p, new ValueOrigin(v, ANNOTATED)));

            boolean enumField = isEnum && fieldInfo.isSynthetic();

            ValueOrigin ff = fieldMap.get(FINAL_FIELD);
            if (ff == null || ff.valueAsBool().isFalse()) {
                if (enumField || ownerImmutable.isAtLeastImmutableHC()) {
                    fieldMap.put(FINAL_FIELD, FROM_OWNER_TRUE);
                } else if (fieldInfo.isFinal()) {
                    fieldMap.put(FINAL_FIELD, FROM_FIELD_TRUE);
                } else if (ff == null) {
                    fieldMap.put(FINAL_FIELD, DEFAULT_FALSE);
                }
            }
            ValueOrigin nn = fieldMap.get(NOT_NULL_FIELD);
            if (nn == null || ((Value.NotNull) nn.value()).isNullable()) {
                if (enumField) {
                    fieldMap.put(NOT_NULL_FIELD, new ValueOrigin(NOT_NULL, FROM_OWNER));
                } else if (fieldInfo.type().isPrimitiveExcludingVoid()) {
                    fieldMap.put(NOT_NULL_FIELD, new ValueOrigin(NOT_NULL, FROM_TYPE));
                } else if (nn == null) {
                    fieldMap.put(NOT_NULL_FIELD, NULLABLE_DEFAULT);
                }
            }
            ValueOrigin c = fieldMap.get(CONTAINER_FIELD);
            if (c == null || c.valueAsBool().isFalse()) {
                if (typeIsContainer(fieldInfo.type())) {
                    fieldMap.put(CONTAINER_FIELD, FROM_TYPE_TRUE);
                } else if (c == null) {
                    fieldMap.put(CONTAINER_FIELD, DEFAULT_FALSE);
                }
            }
            Value.Immutable formallyImmutable = analysisHelper.typeImmutable(fieldInfo.type());
            if (formallyImmutable == null) {
                LOGGER.warn("Have no @Immutable value for {}", fieldInfo.type());
                formallyImmutable = MUTABLE;
            }

            ValueOrigin um = fieldMap.get(UNMODIFIED_FIELD);
            if (um == null || um.valueAsBool().isFalse()) {
                if (formallyImmutable.isAtLeastImmutableHC()) {
                    fieldMap.put(UNMODIFIED_FIELD, FROM_TYPE_TRUE);
                } else if (ownerImmutable.isAtLeastImmutableHC()) {
                    fieldMap.put(UNMODIFIED_FIELD, FROM_OWNER_TRUE);
                } else if (um == null) {
                    fieldMap.put(UNMODIFIED_FIELD, DEFAULT_FALSE);
                }
            }
            ValueOrigin imm = fieldMap.get(IMMUTABLE_FIELD);
            if (imm == null) {
                if (!ValueImpl.ImmutableImpl.MUTABLE.equals(formallyImmutable)) {
                    fieldMap.put(IMMUTABLE_FIELD, new ValueOrigin(formallyImmutable, FROM_TYPE));
                }
            } else {
                Value.Immutable v = (Value.Immutable) imm.value();
                Value.Immutable max = formallyImmutable.max(v);
                if (!ValueImpl.ImmutableImpl.MUTABLE.equals(max) && !max.equals(v)) {
                    fieldMap.put(IMMUTABLE_FIELD, new ValueOrigin(max, FROM_TYPE));
                }
            }
            Value.Independent formallyIndependent = analysisHelper.typeIndependent(fieldInfo.type());
            if (formallyIndependent == null) {
                LOGGER.warn("Have no @Independent value for {}", fieldInfo.type());
                formallyIndependent = DEPENDENT;
            }
            ValueOrigin ind = fieldMap.get(INDEPENDENT_FIELD);
            if (ind == null) {
                if (!formallyIndependent.isDependent()) {
                    fieldMap.put(INDEPENDENT_FIELD, new ValueOrigin(formallyIndependent, FROM_TYPE));
                }
            } else {
                Value.Independent v = (Value.Independent) ind.value();
                Value.Independent max = formallyIndependent.max(v);
                if (!max.isDependent()) {
                    fieldMap.put(INDEPENDENT_FIELD, new ValueOrigin(formallyIndependent, FROM_TYPE));
                }
            }

            // copy into relevant place
            ShallowAnalyzer.InfoData infoData = new ShallowAnalyzer.InfoData(new HashMap<>());
            fieldMap.forEach((p, vo) -> {
                if (!vo.value().isDefault()) fieldInfo.analysis().set(p, vo.value());
                infoData.put(p, vo.origin());
            });
            dataMap.put(fieldInfo, infoData);
        }
        return dataMap;
    }

    private boolean typeIsContainer(ParameterizedType type) {
        TypeInfo best = type.bestTypeInfo();
        if (best == null) return true;
        return best.analysis().getOrDefault(CONTAINER_TYPE, FALSE).isTrue();
    }

    public void check(TypeInfo typeInfo) {
        Value.Immutable immutable = typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
        if (immutable.isAtLeastImmutableHC()) {
            Value.Immutable least = leastOfHierarchy(typeInfo, IMMUTABLE_TYPE, MUTABLE, IMMUTABLE_HC);
            if (!least.isAtLeastImmutableHC()) {
                LOGGER.warn("@Immutable inconsistency in hierarchy: have {} for {}, but:", immutable, typeInfo);
                typeInfo.recursiveSuperTypeStream().filter(TypeInfo::isPublic).distinct()
                        .forEach(ti -> {
                            LOGGER.warn("  -- {}: {}", ti, ti.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
                        });
                warnings.incrementAndGet();
            }
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                if (fieldInfo.analysis().getOrDefault(UNMODIFIED_FIELD, FALSE).isFalse()) {
                    LOGGER.warn("Have @Modified field {} in @Immutable type {}", fieldInfo.name(), typeInfo);
                    warnings.incrementAndGet();
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.analysis().getOrDefault(NON_MODIFYING_METHOD, FALSE).isFalse()) {
                    LOGGER.warn("Have @Modified method {} in @Immutable type {}", methodInfo.name(), typeInfo);
                    warnings.incrementAndGet();
                }
            }
        }
        Value.Bool container = typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE);
        if (container.isTrue()) {
            Value least = leastOfHierarchy(typeInfo, CONTAINER_TYPE, FALSE, TRUE);
            if (least.lt(container)) {
                LOGGER.warn("@Container inconsistency in hierarchy: true for {}, but not for all types in its hierarchy: {}",
                        typeInfo, least);
                typeInfo.recursiveSuperTypeStream().filter(TypeInfo::isPublic).distinct()
                        .forEach(ti -> {
                            LOGGER.warn("  -- {}: {}", ti, ti.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
                        });
                warnings.incrementAndGet();
            }
        }
        Value.Independent independent = typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
        if (independent.isAtLeastIndependentHc()) {
            Value least = leastOfHierarchy(typeInfo, INDEPENDENT_TYPE, DEPENDENT, INDEPENDENT);
            if (least.lt(independent)) {
                LOGGER.warn("@Independent inconsistency in hierarchy: value for {} is {}, but not for all types in its hierarchy: {}",
                        typeInfo, independent, least);
                typeInfo.recursiveSuperTypeStream().filter(TypeInfo::isPublic).distinct()
                        .forEach(ti -> {
                            LOGGER.warn("  -- {}: {}", ti, ti.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
                        });
                warnings.incrementAndGet();
            }
        }
        if (immutable.isImmutable() && !independent.isIndependent()
            || immutable.isAtLeastImmutableHC() && !independent.isAtLeastIndependentHc()) {
            LOGGER.warn("Inconsistency between @Independent and @Immutable for type {}: {}, {}", typeInfo,
                    immutable, independent);
            warnings.incrementAndGet();
        }
    }


}
