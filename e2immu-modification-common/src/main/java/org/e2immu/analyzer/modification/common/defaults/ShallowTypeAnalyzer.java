package org.e2immu.analyzer.modification.common.defaults;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.annotation.Independent;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE_HC;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.NotNullImpl.NOT_NULL;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.NotNullImpl.NULLABLE;

public class ShallowTypeAnalyzer extends CommonAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShallowTypeAnalyzer.class);
    private final AnalysisHelper analysisHelper = new AnalysisHelper();
    private final AtomicInteger warnings = new AtomicInteger();

    public ShallowTypeAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        super(runtime, annotationProvider);
    }

    public void analyze(TypeInfo typeInfo) {
        LOGGER.debug("Analyzing type {}", typeInfo);
        if (typeInfo.analysis().getOrDefault(SHALLOW_ANALYZER, FALSE).isTrue()) {
            return; // already done
        }
        typeInfo.analysis().set(SHALLOW_ANALYZER, TRUE);

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
        map.forEach(typeInfo.analysis()::set);

        boolean immutableDeterminedByTypeParameters = typeInfo.typeParameters().stream()
                .anyMatch(tp -> tp.annotations().stream().anyMatch(ae ->
                        Independent.class.getCanonicalName().equals(ae.typeInfo().fullyQualifiedName())));
        typeInfo.analysis().set(IMMUTABLE_TYPE_DETERMINED_BY_PARAMETERS,
                ValueImpl.BoolImpl.from(immutableDeterminedByTypeParameters));
    }

    public void analyzeFields(TypeInfo typeInfo) {
        boolean isEnum = typeInfo.typeNature().isEnum();
        for (FieldInfo fieldInfo : typeInfo.fields()) {
            if (!fieldInfo.access().isPublic()) continue;
            if (fieldInfo.analysis().getOrDefault(SHALLOW_ANALYZER, FALSE).isTrue()) {
                continue; // already done
            }
            fieldInfo.analysis().set(SHALLOW_ANALYZER, TRUE);

            List<AnnotationExpression> fieldAnnotations = annotationProvider.annotations(fieldInfo);
            Map<Property, Value> fieldMap = annotationsToMap(fieldInfo, fieldAnnotations);
            boolean enumField = isEnum && fieldInfo.isSynthetic();

            Value.Bool ff = (Value.Bool) fieldMap.get(FINAL_FIELD);
            if (ff == null || ff.isFalse()) {
                if (enumField || fieldInfo.isFinal()) {
                    fieldMap.put(FINAL_FIELD, TRUE);
                } else if (ff == null) {
                    fieldMap.put(FINAL_FIELD, FALSE);
                }
            }
            Value.NotNull nn = (Value.NotNull) fieldMap.get(NOT_NULL_FIELD);
            if (nn == null || nn.isNullable()) {
                if (enumField || fieldInfo.type().isPrimitiveExcludingVoid()) {
                    fieldMap.put(NOT_NULL_FIELD, NOT_NULL);
                } else if (nn == null) {
                    fieldMap.put(NOT_NULL_FIELD, NULLABLE);
                }
            }
            Value.Bool c = (Value.Bool) fieldMap.get(CONTAINER_FIELD);
            if (c == null || c.isFalse()) {
                if (typeIsContainer(fieldInfo.type())) {
                    fieldMap.put(CONTAINER_FIELD, TRUE);
                } else if (c == null) {
                    fieldMap.put(CONTAINER_FIELD, FALSE);
                }
            }
            Value.Immutable imm = (Value.Immutable) fieldMap.get(IMMUTABLE_FIELD);
            if (imm == null || !imm.isImmutable()) {
                Value.Immutable formally = analysisHelper.typeImmutable(fieldInfo.type());
                if (formally == null) {
                    LOGGER.warn("Have no @Immutable value for {}", fieldInfo.type());
                    formally = MUTABLE;
                }
                fieldMap.put(IMMUTABLE_FIELD, formally.max(imm));
            }
            Value.Independent ind = (Value.Independent) fieldMap.get(INDEPENDENT_FIELD);
            if (ind == null || !ind.isIndependent()) {
                Value.Independent formally = analysisHelper.typeIndependent(fieldInfo.type());
                if (formally == null) {
                    LOGGER.warn("Have no @Independent value for {}", fieldInfo.type());
                    formally = DEPENDENT;
                }
                fieldMap.put(INDEPENDENT_FIELD, formally.max(ind));
            }
            fieldMap.forEach(fieldInfo.analysis()::set);
        }
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
                if (fieldInfo.analysis().getOrDefault(MODIFIED_FIELD, FALSE).isTrue()) {
                    LOGGER.warn("Have @Modified field {} in @Immutable type {}", fieldInfo.name(), typeInfo);
                    warnings.incrementAndGet();
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.analysis().getOrDefault(MODIFIED_METHOD, FALSE).isTrue()) {
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
