package org.e2immu.analyzer.modification.common.defaults;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.language.cst.api.analysis.Message;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.MessageImpl;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.NotNullImpl.NOT_NULL;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.NotNullImpl.NULLABLE;

public class ShallowMethodAnalyzer extends CommonAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShallowMethodAnalyzer.class);
    private final AnalysisHelper analysisHelper;
    private final Map<TypeInfo, Set<TypeInfo>> hierarchyProblems = new HashMap<>();
    private final List<Message> messages = new LinkedList<>();

    public ShallowMethodAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        super(runtime, annotationProvider);
        this.analysisHelper = new AnalysisHelper();
    }

    public void analyze(MethodInfo methodInfo) {
        analyze(methodInfo, false, false);
    }

    public void analyze(MethodInfo methodInfo, boolean defaultModifiedMethod, boolean defaultModifiedParameter) {
        if (methodInfo.analysis().getOrDefault(SHALLOW_ANALYZER, FALSE).isTrue()) {
            return; // already done
        }
        methodInfo.analysis().set(SHALLOW_ANALYZER, TRUE);

        boolean explicitlyEmpty = methodInfo.explicitlyEmptyMethod();

        List<AnnotationExpression> annotations = annotationProvider.annotations(methodInfo);
        Map<Property, Value> map = annotationsToMap(methodInfo, annotations);

        methodPropertiesBeforeParameters(methodInfo, map, explicitlyEmpty, defaultModifiedMethod);

        for (ParameterInfo parameterInfo : methodInfo.parameters()) {
            handleParameter(parameterInfo, map, explicitlyEmpty, defaultModifiedParameter);
        }

        methodPropertiesAfterParameters(methodInfo, map);

        map.forEach((p, v) -> {
            if (!methodInfo.analysis().haveAnalyzedValueFor(p)) {
                methodInfo.analysis().set(p, v);
            }
        });
    }

    private Value.Bool computeMethodContainer(MethodInfo methodInfo) {
        ParameterizedType returnType = methodInfo.returnType();
        if (returnType.arrays() > 0 || returnType.isPrimitiveExcludingVoid() || returnType.isVoid()) {
            return TRUE;
        }
        if (returnType.isReturnTypeOfConstructor()) {
            return ValueImpl.BoolImpl.NO_VALUE;
        }
        TypeInfo bestType = returnType.bestTypeInfo();
        if (bestType == null) {
            return FALSE; // unbound type parameter
        }

        // check formal return type
        Value.Bool fromReturnType = bestType.analysis().getOrDefault(CONTAINER_TYPE, FALSE);
        Value.Bool bestOfOverrides = methodInfo.overrides().stream()
                .map(mi -> mi.analysis().getOrDefault(CONTAINER_TYPE, FALSE))
                .reduce(FALSE, Value.Bool::or);
        Value.Bool formal = bestOfOverrides.or(fromReturnType);
        if (formal.isTrue()) return formal;

        // check identity and parameter contract
        if (methodInfo.analysis().getOrDefault(IDENTITY_METHOD, FALSE).isTrue()) {
            ParameterInfo p0 = methodInfo.parameters().get(0);
            return p0.analysis().getOrDefault(CONTAINER_PARAMETER, FALSE);
        }
        return FALSE;
    }

    private void methodPropertiesAfterParameters(MethodInfo methodInfo, Map<Property, Value> map) {
        Value.Bool c = (Value.Bool) map.get(CONTAINER_METHOD);
        if (c == null) {
            map.put(CONTAINER_METHOD, computeMethodContainer(methodInfo));
        }
        Value.Immutable imm = (Value.Immutable) map.get(IMMUTABLE_METHOD);
        if (imm == null) {
            map.put(IMMUTABLE_METHOD, computeMethodImmutable(methodInfo));
        }
        Value.Independent ind = (Value.Independent) map.get(INDEPENDENT_METHOD);
        if (ind == null) {
            map.put(INDEPENDENT_METHOD, computeMethodIndependent(methodInfo, map));
        }
        Value.NotNull nn = (Value.NotNull) map.get(NOT_NULL_METHOD);
        if (nn == null) {
            map.put(NOT_NULL_METHOD, computeMethodNotNull(methodInfo, map));
        }
    }

    private Value.NotNull computeMethodNotNull(MethodInfo methodInfo, Map<Property, Value> map) {
        if (methodInfo.isConstructor() || methodInfo.isVoid()) return ValueImpl.NotNullImpl.NO_VALUE;
        if (methodInfo.returnType().isPrimitiveExcludingVoid()) {
            return NOT_NULL;
        }
        Value.Bool fluent = (Value.Bool) map.get(FLUENT_METHOD);
        if (fluent.isTrue()) return NOT_NULL;
        return methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.analysis().getOrDefault(NOT_NULL_METHOD, NULLABLE))
                .reduce(NULLABLE, Value.NotNull::max);
    }

    private Value.Immutable computeMethodImmutable(MethodInfo methodInfo) {
        ParameterizedType returnType = methodInfo.returnType();
        Immutable immutable = analysisHelper.typeImmutable(returnType);
        if (immutable == null) {
            LOGGER.warn("No immutable value for {}", returnType);
            return ValueImpl.ImmutableImpl.MUTABLE;
        }
        return immutable;
    }


    private Value.Independent computeMethodIndependent(MethodInfo methodInfo, Map<Property, Value> map) {
        Value.Independent returnValueIndependent = computeMethodIndependentReturnValue(methodInfo, map);

        // typeIndependent is set by hand in AnnotatedAPI files
        Value.Independent typeIndependent = methodInfo.typeInfo().analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
        Value.Independent bestOfOverrides = methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT))
                .reduce(DEPENDENT, Value.Independent::max);
        Value.Independent result = returnValueIndependent.max(bestOfOverrides).max(typeIndependent);

        if (result.isIndependentHc() && methodInfo.isFactoryMethod()) {
            // at least one of the parameters must be independent HC!!
            boolean hcParam = methodInfo.parameters().stream()
                    .anyMatch(pa -> pa.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT).isIndependentHc());
            if (!hcParam) {
                LOGGER.warn("@Independent(hc=true) factory method must have at least one @Independent(hc=true) parameter");
            }
        }
        return result;
    }

    private Value.Independent computeMethodIndependentReturnValue(MethodInfo methodInfo, Map<Property, Value> map) {
        if (methodInfo.isConstructor() || methodInfo.isVoid()) {
            return INDEPENDENT;
        }
        if (methodInfo.isStatic() && !methodInfo.isFactoryMethod()) {
            // if factory method, we link return value to parameters, otherwise independent by default
            return INDEPENDENT;
        }
        Value.Bool identity = (Value.Bool) map.get(IDENTITY_METHOD);
        Value.Bool modified = (Value.Bool) map.get(MODIFIED_METHOD);
        if (identity.isTrue() && modified.isFalse()) {
            return INDEPENDENT; // @Identity + @NotModified -> must be @Independent
        }
        // from here on we're assuming the result is linked to the fields.

        ParameterizedType pt = methodInfo.returnType();
        if (pt.arrays() > 0) {
            // array type, like int[]
            return DEPENDENT;
        }
        TypeInfo bestType = pt.bestTypeInfo();
        if (bestType == null || bestType.isJavaLangObject()) {
            // unbound type parameter T, or unbound with array T[], T[][]
            return INDEPENDENT_HC;
        }
        if (bestType.isPrimitiveExcludingVoid()) {
            return INDEPENDENT;
        }
        Value.Immutable imm = (Immutable) map.get(IMMUTABLE_METHOD);
        if (imm.isAtLeastImmutableHC()) {
            return imm.toCorrespondingIndependent();
        }
        return DEPENDENT;
    }

    private void methodPropertiesBeforeParameters(MethodInfo methodInfo, Map<Property, Value> map,
                                                  boolean explicitlyEmpty,
                                                  boolean defaultModifiedMethod) {
        if (methodInfo.isConstructor()) {
            map.put(FLUENT_METHOD, FALSE);
            map.put(IDENTITY_METHOD, FALSE);
            map.put(MODIFIED_METHOD, TRUE);
            map.putIfAbsent(METHOD_ALLOWS_INTERRUPTS, FALSE);
        } else {
            Value.Bool fluent = (Value.Bool) map.get(FLUENT_METHOD);
            if (fluent != null) {
                if (fluent.isTrue() && explicitlyEmpty) {
                    LOGGER.warn("Impossible! how can a method without statements be @Fluent?");
                }
            } else {
                map.put(FLUENT_METHOD, explicitlyEmpty ? FALSE : computeMethodFluent(methodInfo));
            }
            Value.Bool identity = (Value.Bool) map.get(IDENTITY_METHOD);
            if (identity != null) {
                if (identity.isTrue() && explicitlyEmpty) {
                    LOGGER.warn("Impossible! how can a method without statements be @Identity?");
                }
                if (identity.isTrue() && (methodInfo.parameters().isEmpty()
                                          || !methodInfo.returnType().equals(methodInfo.parameters().get(0).parameterizedType()))) {
                    LOGGER.warn("@Identity method must have return type identical to formal type of first parameter");
                }
            } else {
                map.put(IDENTITY_METHOD, explicitlyEmpty || methodInfo.parameters().isEmpty()
                        ? FALSE : computeMethodIdentity(methodInfo));
            }

            Value.Bool staticSideEffects = (Value.Bool) map.get(STATIC_SIDE_EFFECTS_METHOD);
            if (staticSideEffects != null) {
                if (staticSideEffects.isTrue() && explicitlyEmpty) {
                    messages.add(MessageImpl.warn(methodInfo,
                            "Impossible! how can a method without statements be @StaticSideEffects?"));
                }
            } else {
                map.put(STATIC_SIDE_EFFECTS_METHOD, explicitlyEmpty ? FALSE : computeStaticSideEffects(methodInfo));
            }

            Value.Bool modified = (Value.Bool) map.get(MODIFIED_METHOD);
            if (modified != null) {
                if (modified.isTrue() && explicitlyEmpty) {
                    messages.add(MessageImpl.warn(methodInfo,
                            "Impossible! how can a method without statements be @Modified?"));
                }
            } else {
                map.put(MODIFIED_METHOD, explicitlyEmpty ? FALSE
                        : computeMethodModified(methodInfo, map, defaultModifiedMethod));
            }
            Value.Bool allowsInterrupt = (Value.Bool) map.get(METHOD_ALLOWS_INTERRUPTS);
            if (allowsInterrupt != null) {
                if (allowsInterrupt.isTrue() && explicitlyEmpty) {
                    messages.add(MessageImpl.warn(methodInfo,
                            "Impossible! how can a method without statements be @AllowInterrupt?"));
                }
            } else {
                map.put(METHOD_ALLOWS_INTERRUPTS, explicitlyEmpty ? FALSE : computeAllowInterrupt(methodInfo));
            }
        }
    }

    private void handleParameter(ParameterInfo parameterInfo,
                                 Map<Property, Value> methodMap,
                                 boolean explicitlyEmpty,
                                 boolean defaultModifiedParameter) {
        List<AnnotationExpression> annotations = annotationProvider.annotations(parameterInfo);
        Map<Property, Value> map = annotationsToMap(parameterInfo, annotations);
        if (explicitlyEmpty) {
            Value.Independent ind = (Value.Independent) map.get(INDEPENDENT_PARAMETER);
            if (ind != null && ind != INDEPENDENT) {
                LOGGER.warn("Parameter {} must be independent", parameterInfo);
            }
            map.put(INDEPENDENT_PARAMETER, INDEPENDENT);
            Value.Bool modified = (Value.Bool) map.get(MODIFIED_PARAMETER);
            if (modified != null && modified.isTrue()) {
                LOGGER.warn("Parameter {} cannot be @Modified", parameterInfo);
            }
            map.put(MODIFIED_PARAMETER, FALSE);
            map.put(NOT_NULL_PARAMETER, analysisHelper.notNullOfType(parameterInfo.parameterizedType()));
            map.putIfAbsent(IGNORE_MODIFICATIONS_PARAMETER, FALSE);
        } else {
            Value.Immutable imm = (Value.Immutable) map.get(IMMUTABLE_PARAMETER);
            if (imm == null) {
                Immutable value = analysisHelper.typeImmutable(parameterInfo.parameterizedType());
                if (value == null) {
                    LOGGER.warn("Have no @Immutable value for {}", parameterInfo.parameterizedType());
                    value = ValueImpl.ImmutableImpl.MUTABLE;
                }
                map.put(IMMUTABLE_PARAMETER, value);
            }
            Value.Independent ind = (Value.Independent) map.get(INDEPENDENT_PARAMETER);
            if (ind == null) {
                map.put(INDEPENDENT_PARAMETER, computeParameterIndependent(parameterInfo, methodMap, map));
            }
            Value.Bool mod = (Value.Bool) map.get(MODIFIED_PARAMETER);
            if (mod == null) {
                map.put(MODIFIED_PARAMETER, computeParameterModified(parameterInfo, defaultModifiedParameter));
            }
            Value.NotNull nn = (NotNull) map.get(NOT_NULL_PARAMETER);
            if (nn == null) {
                map.put(NOT_NULL_PARAMETER, computeParameterNotNull(parameterInfo));
            }
            Value.Bool ign = (Bool) map.get(IGNORE_MODIFICATIONS_PARAMETER);
            if (ign == null) {
                map.put(IGNORE_MODIFICATIONS_PARAMETER, computeParameterIgnoreModifications(parameterInfo));
            }
            Value.Bool c = (Bool) map.get(CONTAINER_PARAMETER);
            if (c == null) {
                map.put(CONTAINER_PARAMETER, computeParameterContainer(parameterInfo));
            }
        }
        map.forEach(parameterInfo.analysis()::set);
    }

    private Value computeParameterContainer(ParameterInfo parameterInfo) {
        return analysisHelper.typeContainer(parameterInfo.parameterizedType());
    }

    private Value computeParameterIgnoreModifications(ParameterInfo parameterInfo) {
        ParameterizedType pt = parameterInfo.parameterizedType();
        return ValueImpl.BoolImpl.from(pt.isFunctionalInterface()
                                       && "java.util.function".equals(pt.typeInfo().packageName()));
    }

    private Value.NotNull computeParameterNotNull(ParameterInfo parameterInfo) {
        ParameterizedType pt = parameterInfo.parameterizedType();
        if (pt.isPrimitiveExcludingVoid()) return NOT_NULL;
        MethodInfo methodInfo = parameterInfo.methodInfo();
        return methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .filter(pi -> pi.analysis().haveAnalyzedValueFor(NOT_NULL_PARAMETER, () -> {
                    if (hierarchyProblems.computeIfAbsent(methodInfo.typeInfo(), t -> new HashSet<>()).add(pi.methodInfo().typeInfo())) {
                        LOGGER.warn("Have no @NotNull value for the parameters of {}, overridden by {}", pi, methodInfo);
                    }
                }))
                .map(pi -> pi.analysis().getOrDefault(NOT_NULL_PARAMETER, NULLABLE))
                .reduce(NULLABLE, Value.NotNull::max);
    }

    private Value.Bool computeParameterModified(ParameterInfo parameterInfo, boolean defaultParameterModified) {
        MethodInfo methodInfo = parameterInfo.methodInfo();
        Value.Bool typeContainer = methodInfo.typeInfo().analysis().getOrDefault(CONTAINER_TYPE, FALSE);
        if (typeContainer.isTrue()) {
            return FALSE;
        }
        ParameterizedType type = parameterInfo.parameterizedType();
        if (type.isPrimitiveStringClass()) {
            return FALSE;
        }
        Value.Immutable typeImmutable = analysisHelper.typeImmutable(type);
        if (typeImmutable != null && typeImmutable.isAtLeastImmutableHC()) {
            return FALSE;
        }
        Value.Bool override = methodInfo.overrides().stream()
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .map(pi -> pi.analysis().getOrDefault(MODIFIED_PARAMETER, ValueImpl.BoolImpl.NO_VALUE))
                .reduce(ValueImpl.BoolImpl.NO_VALUE, Value.Bool::or);
        if (override.hasAValue()) {
            return override;
        }
        return defaultParameterModified ? TRUE : FALSE;
    }


    private Value.Independent computeParameterIndependent(ParameterInfo parameterInfo,
                                                          Map<Property, Value> methodMap,
                                                          Map<Property, Value> map) {
        ParameterizedType type = parameterInfo.parameterizedType();
        Value.Immutable immutable = (Value.Immutable) map.get(PropertyImpl.IMMUTABLE_PARAMETER);
        MethodInfo methodInfo = parameterInfo.methodInfo();

        Value.Independent value;
        if (type.isPrimitiveExcludingVoid() || immutable.isImmutable()) {
            value = INDEPENDENT;
        } else {
            // @Modified needs to be marked explicitly
            Value.Bool modifiedMethod = (Value.Bool) methodMap.get(PropertyImpl.MODIFIED_METHOD);
            if (modifiedMethod.isTrue() || methodInfo.isFactoryMethod()) {
                // note that an unbound type parameter is by default @Dependent, not @Independent1!!
                Value.Independent independentFromImmutable = immutable.toCorrespondingIndependent();
                TypeInfo ownerType = parameterInfo.methodInfo().typeInfo();
                Value.Independent independentType = ownerType.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
                value = independentType.max(independentFromImmutable);
            } else {
                value = INDEPENDENT;
            }
        }
        Value.Independent override = methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .map(mi -> mi.parameters().get(parameterInfo.index()))
                .filter(pi -> pi.analysis().haveAnalyzedValueFor(INDEPENDENT_PARAMETER, () -> {
                    if (hierarchyProblems.computeIfAbsent(methodInfo.typeInfo(), t -> new HashSet<>()).add(pi.methodInfo().typeInfo())) {
                        LOGGER.warn("Have no @Independent value for the parameters of {}, overridden by {}", pi, methodInfo);
                    }
                }))
                .map(pi -> pi.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT))
                .reduce(DEPENDENT, Value.Independent::max);
        return override.max(value);
    }


    private Value computeMethodModified(MethodInfo methodInfo,
                                        Map<Property, Value> map,
                                        boolean defaultModifiedMethod) {
        if (methodInfo.isConstructor()) return TRUE;
        Value.Bool sse = (Value.Bool) map.get(STATIC_SIDE_EFFECTS_METHOD);
        if (sse != null && sse.isTrue()) return FALSE;
        Value.Bool fluent = (Value.Bool) map.get(FLUENT_METHOD);
        if (fluent != null && fluent.isTrue()) return TRUE;
        boolean nonStaticVoid = !methodInfo.isStatic() && methodInfo.noReturnValue();
        if (nonStaticVoid) return TRUE;
        Boolean fromOverride = methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .filter(m -> m.analysis().haveAnalyzedValueFor(MODIFIED_METHOD, () -> {
                    if (hierarchyProblems.computeIfAbsent(methodInfo.typeInfo(), t -> new HashSet<>()).add(m.typeInfo())) {
                        LOGGER.warn("Have no modification value for {}, overridden by {}", m, methodInfo);
                    }
                }))
                .map(m -> m.analysis().getOrNull(MODIFIED_METHOD, ValueImpl.BoolImpl.class))
                .map(v -> v == null ? null : v.isTrue())
                .reduce(null, ShallowMethodAnalyzer::nullOr);
        if (fromOverride == null) return defaultModifiedMethod ? TRUE : FALSE;
        return ValueImpl.BoolImpl.from(fromOverride);
    }

    private static Boolean nullOr(Boolean b1, Boolean b2) {
        if (b1 == null) return b2;
        if (b2 == null) return b1;
        return b1 || b2;
    }

    private Value computeMethodFluent(MethodInfo methodInfo) {
        if (methodInfo.returnType().typeInfo() != methodInfo.typeInfo()) return FALSE;
        return commonBooleanFromOverride(FLUENT_METHOD, methodInfo);
    }

    private Value computeMethodIdentity(MethodInfo methodInfo) {
        return commonBooleanFromOverride(IDENTITY_METHOD, methodInfo);
    }

    private Value computeStaticSideEffects(MethodInfo methodInfo) {
        return commonBooleanFromOverride(STATIC_SIDE_EFFECTS_METHOD, methodInfo);
    }

    private Value computeAllowInterrupt(MethodInfo methodInfo) {
        return commonBooleanFromOverride(METHOD_ALLOWS_INTERRUPTS, methodInfo);
    }

    private Value commonBooleanFromOverride(Property property, MethodInfo methodInfo) {
        return methodInfo.overrides().stream()
                .filter(MethodInfo::isPublic)
                .filter(m -> m.analysis().haveAnalyzedValueFor(property, () -> {
                    if (hierarchyProblems.computeIfAbsent(methodInfo.typeInfo(), t -> new HashSet<>()).add(m.typeInfo())) {
                        LOGGER.warn("Have no {} value for {}, overridden by {}", property.key(), m, methodInfo);
                    }
                }))
                .map(m -> m.analysis().getOrDefault(property, FALSE))
                .reduce(FALSE, Value.Bool::or);
    }

    public Map<TypeInfo, Set<TypeInfo>> getHierarchyProblems() {
        return hierarchyProblems;
    }

    public List<Message> messages() {
        return messages;
    }
}
