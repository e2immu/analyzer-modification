package org.e2immu.analyzer.modification.io;

import org.e2immu.annotation.*;
import org.e2immu.annotation.rare.AllowsInterrupt;
import org.e2immu.annotation.rare.Finalizer;
import org.e2immu.annotation.rare.IgnoreModifications;
import org.e2immu.annotation.type.UtilityClass;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.element.Comment;
import org.e2immu.language.cst.api.element.ImportStatement;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;

public class DecoratorImpl implements Qualification.Decorator {
    private final Runtime runtime;
    private final AnnotationExpression notModifiedAnnotation;
    private final AnnotationExpression modifiedAnnotation;
    private final TypeInfo immutableTi;
    private final TypeInfo independentTi;
    private final TypeInfo immutableContainerTi;
    private final TypeInfo notNullTi;
    private final AnnotationExpression ignoreModifications;
    private final AnnotationExpression identityAnnotation;
    private final AnnotationExpression fluentAnnotation;
    private final AnnotationExpression finalAnnotation;
    private final AnnotationExpression containerAnnotation;
    private final AnnotationExpression utilityClassAnnotation;
    private final AnnotationExpression allowInterruptAnnotation;
    private final AnnotationExpression finalizerAnnotation;

    private final Set<Class<?>> importsNeeded = new HashSet<>();

    private final Map<Info, Info> translationMap;

    public DecoratorImpl(Runtime runtime) {
        this(runtime, null);
    }

    public DecoratorImpl(Runtime runtime, Map<Info, Info> translationMap) {
        this.runtime = runtime;
        TypeInfo notModifiedTi = runtime.getFullyQualified(NotModified.class, true);
        notModifiedAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(notModifiedTi).build();
        TypeInfo modifiedTi = runtime.getFullyQualified(Modified.class, true);
        modifiedAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(modifiedTi).build();
        independentTi = runtime.getFullyQualified(Independent.class, true);
        immutableTi = runtime.getFullyQualified(Immutable.class, true);
        TypeInfo finalTi = runtime.getFullyQualified(Final.class, true);
        TypeInfo containerTi = runtime.getFullyQualified(Container.class, true);
        immutableContainerTi = runtime.getFullyQualified(ImmutableContainer.class, true);
        finalAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(finalTi).build();
        containerAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(containerTi).build();
        TypeInfo identityTi = runtime.getFullyQualified(Identity.class, true);
        identityAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(identityTi).build();
        TypeInfo fluentTi = runtime.getFullyQualified(Fluent.class, true);
        fluentAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(fluentTi).build();
        notNullTi = runtime.getFullyQualified(NotNull.class, true);
        this.translationMap = translationMap;
        TypeInfo utilityClassTi = runtime.getFullyQualified(UtilityClass.class, true);
        utilityClassAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(utilityClassTi).build();
        TypeInfo ignoreModsTi = runtime.getFullyQualified(IgnoreModifications.class, true);
        ignoreModifications = runtime.newAnnotationExpressionBuilder().setTypeInfo(ignoreModsTi).build();
        TypeInfo allowInterruptTi = runtime.getFullyQualified(AllowsInterrupt.class, true);
        allowInterruptAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(allowInterruptTi).build();
        TypeInfo finalizerTi = runtime.getFullyQualified(Finalizer.class, true);
        finalizerAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(finalizerTi).build();
    }

    @Override
    public List<Comment> comments(Info info) {
        Value.Message errorMessage = info.analysis().getOrDefault(PropertyImpl.ANALYZER_ERROR, ValueImpl.MessageImpl.EMPTY);
        if (!errorMessage.isEmpty()) {
            return List.of(runtime.newSingleLineComment(errorMessage.message()));
        }
        return List.of();
    }

    protected record AnnotationProperty(AnnotationExpression annotationExpression, Property property) {
    }

    @Override
    public List<AnnotationExpression> annotations(Info info) {
        return annotationAndProperties(info).stream().map(AnnotationProperty::annotationExpression).toList();
    }

    protected boolean isAnnotated(Info info, Property property) {
        return false;
    }

    protected List<AnnotationProperty> annotationAndProperties(Info infoIn) {
        Info info = translationMap == null ? infoIn : translationMap.getOrDefault(infoIn, infoIn);
        Property propertyUnmodified;
        Property propertyModifiedAnnotated;
        Value.Immutable immutable;
        Property propertyImmutable;
        Value.Independent independent;
        Map<Integer, Integer> linkToParametersReturnValue;
        Property propertyIndependent;
        Property propertyFinalField;
        Property propertyContainer;
        Property propertyIdentity;
        Property propertyFluent;
        Property propertyFinalizer;
        Value.NotNull notNull;
        Property propertyNotNull;
        PropertyValueMap analysis = info.analysis();
        Property propertyUtilityClass;
        Property propertyIgnoreModifications;
        Property propertyAllowInterrupt;
        switch (info) {
            case MethodInfo methodInfo -> {
                boolean noReturn = methodInfo.isConstructor() || !methodInfo.hasReturnValue();
                if (noReturn) {
                    immutable = null;
                    independent = null;
                    notNull = null;
                    linkToParametersReturnValue = null;
                } else {
                    immutable = immutable(analysis.getOrDefault(IMMUTABLE_METHOD, MUTABLE),
                            methodInfo.returnType());
                    Value.Independent independentValue = analysis.getOrDefault(INDEPENDENT_METHOD, DEPENDENT);
                    independent = independent(independentValue, methodInfo.returnType());
                    linkToParametersReturnValue = independentValue.linkToParametersReturnValue();
                    notNull = notNull(analysis.getOrDefault(NOT_NULL_METHOD, ValueImpl.NotNullImpl.NULLABLE),
                            methodInfo.returnType());
                }
                propertyIndependent = INDEPENDENT_METHOD;
                propertyNotNull = NOT_NULL_METHOD;
                propertyImmutable = IMMUTABLE_METHOD;
                propertyUnmodified = !methodInfo.isConstructor() && analysis.getOrDefault(NON_MODIFYING_METHOD, FALSE).isTrue()
                        ? NON_MODIFYING_METHOD : null;
                propertyModifiedAnnotated = null;
                propertyIdentity = methodInfo.isIdentity() ? IDENTITY_METHOD : null;
                propertyFluent = methodInfo.isFluent() ? FLUENT_METHOD : null;
                propertyIgnoreModifications = methodInfo.isIgnoreModification() ? IGNORE_MODIFICATION_METHOD : null;
                propertyAllowInterrupt = methodInfo.allowsInterrupts() ? METHOD_ALLOWS_INTERRUPTS : null;
                propertyFinalizer = methodInfo.analysis().getOrDefault(FINALIZER_METHOD, FALSE).isTrue()
                        ? FINALIZER_METHOD : null;
                propertyContainer = null;
                propertyFinalField = null;
                propertyUtilityClass = null;
            }
            case FieldInfo fieldInfo -> {
                propertyUnmodified = !fieldInfo.type().isPrimitiveStringClass()
                                     && analysis.getOrDefault(UNMODIFIED_FIELD, FALSE).isTrue() ? UNMODIFIED_FIELD : null;
                propertyModifiedAnnotated = null;
                immutable = immutable(analysis.getOrDefault(IMMUTABLE_FIELD, MUTABLE), fieldInfo.type());
                propertyImmutable = IMMUTABLE_FIELD;
                independent = independent(analysis.getOrDefault(INDEPENDENT_FIELD, DEPENDENT), fieldInfo.type());
                propertyIndependent = INDEPENDENT_FIELD;
                propertyFinalField = !fieldInfo.isFinal() && fieldInfo.isPropertyFinal() ? FINAL_FIELD : null;
                propertyIgnoreModifications = fieldInfo.isIgnoreModifications() ? IGNORE_MODIFICATIONS_FIELD : null;
                propertyContainer = null;
                propertyIdentity = null;
                propertyFluent = null;
                propertyAllowInterrupt = null;
                propertyNotNull = NOT_NULL_FIELD;
                notNull = notNull(analysis.getOrDefault(NOT_NULL_FIELD, ValueImpl.NotNullImpl.NULLABLE), fieldInfo.type());
                linkToParametersReturnValue = null;
                propertyUtilityClass = null;
                propertyFinalizer = null;
            }
            case ParameterInfo pi -> {
                boolean isUnmodified = analysis.getOrDefault(UNMODIFIED_PARAMETER, FALSE).isTrue();
                propertyUnmodified = !pi.parameterizedType().isPrimitiveStringClass()
                                     && isUnmodified ? UNMODIFIED_PARAMETER : null;
                propertyModifiedAnnotated = !pi.parameterizedType().isPrimitiveStringClass()
                                            && !isUnmodified
                                            && isAnnotated(pi, UNMODIFIED_PARAMETER) ? UNMODIFIED_PARAMETER : null;
                immutable = immutable(analysis.getOrDefault(IMMUTABLE_PARAMETER, MUTABLE), pi.parameterizedType());
                propertyImmutable = IMMUTABLE_PARAMETER;
                Value.Independent independentValue = analysis.getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT);
                independent = independent(independentValue, pi.parameterizedType());
                linkToParametersReturnValue = independentValue.linkToParametersReturnValue();
                propertyIndependent = INDEPENDENT_PARAMETER;
                propertyFinalField = null;
                propertyContainer = null;
                propertyIdentity = null;
                propertyAllowInterrupt = null;
                propertyFluent = null;
                propertyNotNull = NOT_NULL_PARAMETER;
                notNull = notNull(analysis.getOrDefault(NOT_NULL_PARAMETER, ValueImpl.NotNullImpl.NULLABLE), pi.parameterizedType());
                propertyUtilityClass = null;
                propertyIgnoreModifications = pi.isIgnoreModifications() ? IGNORE_MODIFICATIONS_PARAMETER : null;
                propertyFinalizer = null;
            }
            case TypeInfo typeInfo -> {
                propertyUnmodified = null;
                propertyModifiedAnnotated = null;
                immutable = analysis.getOrDefault(IMMUTABLE_TYPE, MUTABLE);
                independent = nonTrivialIndependentType(analysis.getOrDefault(INDEPENDENT_TYPE, DEPENDENT), immutable);
                boolean utilityClass = analysis.getOrDefault(UTILITY_CLASS, FALSE).isTrue();
                if (utilityClass) {
                    propertyUtilityClass = UTILITY_CLASS;
                    propertyImmutable = null;
                    propertyIndependent = null;
                } else {
                    propertyUtilityClass = null;
                    propertyImmutable = IMMUTABLE_TYPE;
                    propertyIndependent = INDEPENDENT_TYPE;
                }
                propertyContainer = analysis.getOrDefault(CONTAINER_TYPE, FALSE).isTrue() ? CONTAINER_TYPE : null;
                propertyFinalField = null;
                propertyIdentity = null;
                propertyFluent = null;
                notNull = null;
                propertyNotNull = null;
                linkToParametersReturnValue = null;
                propertyIgnoreModifications = null;
                propertyAllowInterrupt = null;
                propertyFinalizer = null;
            }
            default -> throw new UnsupportedOperationException();
        }

        List<AnnotationProperty> list = new ArrayList<>();
        if (propertyFinalField != null) {
            importsNeeded.add(Final.class);
            list.add(new AnnotationProperty(finalAnnotation, propertyFinalField));
        }
        if (propertyIdentity != null) {
            importsNeeded.add(Identity.class);
            list.add(new AnnotationProperty(identityAnnotation, propertyIdentity));
        }
        if (propertyFluent != null) {
            importsNeeded.add(Fluent.class);
            list.add(new AnnotationProperty(fluentAnnotation, propertyFluent));
        }
        if (propertyAllowInterrupt != null) {
            importsNeeded.add(AllowsInterrupt.class);
            list.add(new AnnotationProperty(allowInterruptAnnotation, propertyAllowInterrupt));
        }
        if (propertyIgnoreModifications != null) {
            importsNeeded.add(IgnoreModifications.class);
            list.add(new AnnotationProperty(ignoreModifications, propertyIgnoreModifications));
        }
        if (propertyImmutable != null && immutable != null && !immutable.isMutable()) {
            TypeInfo ti;
            if (propertyContainer != null) {
                ti = immutableContainerTi;
                importsNeeded.add(ImmutableContainer.class);
            } else {
                ti = immutableTi;
                importsNeeded.add(Immutable.class);
            }
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(ti);
            if (immutable.isImmutableHC()) {
                b.addKeyValuePair("hc", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyImmutable));
        } else if (propertyContainer != null) {
            importsNeeded.add(Container.class);
            list.add(new AnnotationProperty(containerAnnotation, propertyContainer));
        }
        if (propertyIndependent != null && independent != null && !independent.isDependent()) {
            importsNeeded.add(Independent.class);
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(independentTi);

            if (linkToParametersReturnValue != null && !linkToParametersReturnValue.isEmpty()) {
                Integer level = linkToParametersReturnValue.get(-1);
                if (level != null) {
                    if (level == 0) b.addKeyValuePair("dependentReturnValue", runtime.constantTrue());
                    else if (level == 1) b.addKeyValuePair("hcReturnValue", runtime.constantTrue());
                    else throw new UnsupportedOperationException("Level " + level);
                }
                List<Expression> keysAt0 = linkToParametersReturnValue.entrySet().stream()
                        .filter(e -> e.getValue() == 0 && e.getKey() >= 0)
                        .map(e -> (Expression) runtime.newInt(e.getKey())).toList();
                if (!keysAt0.isEmpty()) {
                    b.addKeyValuePair("dependentParameters", runtime.newArrayInitializerBuilder()
                            .setCommonType(runtime.intParameterizedType())
                            .setExpressions(keysAt0).build());
                }
                List<Expression> keysAt1 = linkToParametersReturnValue.entrySet().stream()
                        .filter(e -> e.getValue() == 1 && e.getKey() >= 0)
                        .map(e -> (Expression) runtime.newInt(e.getKey())).toList();
                if (!keysAt1.isEmpty()) {
                    b.addKeyValuePair("hcParameters", runtime.newArrayInitializerBuilder()
                            .setCommonType(runtime.intParameterizedType())
                            .setExpressions(keysAt1).build());
                }
            } else if (independent.isIndependentHc()) {
                b.addKeyValuePair("hc", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyIndependent));
        }
        if (propertyUnmodified != null) {
            importsNeeded.add(NotModified.class);
            list.add(new AnnotationProperty(notModifiedAnnotation, propertyUnmodified));
        }
        if (propertyModifiedAnnotated != null) {
            importsNeeded.add(Modified.class);
            list.add(new AnnotationProperty(modifiedAnnotation, propertyModifiedAnnotated));
        }
        if (notNull != null && !notNull.isNullable()) {
            importsNeeded.add(NotNull.class);
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(notNullTi);
            if (notNull.equals(ValueImpl.NotNullImpl.CONTENT_NOT_NULL)) {
                b.addKeyValuePair("content", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyNotNull));
        }
        if (propertyUtilityClass != null) {
            importsNeeded.add(UtilityClass.class);
            list.add(new AnnotationProperty(utilityClassAnnotation, propertyUtilityClass));
        }
        if (propertyFinalizer != null) {
            importsNeeded.add(Finalizer.class);
            list.add(new AnnotationProperty(finalizerAnnotation, propertyFinalizer));
        }
        return list;
    }

    private Value.NotNull notNull(Value.NotNull notNull, ParameterizedType parameterizedType) {
        if (parameterizedType.isPrimitiveExcludingVoid()) return null;
        return notNull;
    }

    // we're only showing IMMUTABLE in non-trivial cases
    private Value.Immutable immutable(Value.Immutable immutable, ParameterizedType parameterizedType) {
        if (parameterizedType.isPrimitiveStringClass() || parameterizedType.isUnboundTypeParameter()) return null;
        return immutable;
    }

    // we're only showing INDEPENDENT when both the type and the current type are not immutable (hc or not).
    private Value.Independent independent(Value.Independent independent, ParameterizedType parameterizedType) {
        if (parameterizedType.isVoidOrJavaLangVoid() || parameterizedType.isPrimitiveStringClass()) return null;
        return independent;
    }

    private Value.Independent nonTrivialIndependentType(Value.Independent independent, Value.Immutable immutable) {
        if (immutable.isImmutable() || immutable.isImmutableHC() && independent.isIndependentHc()) return null;
        return independent;
    }

    @Override
    public List<ImportStatement> importStatements() {
        return importsNeeded.stream().map(Class::getCanonicalName).sorted()
                .map(s -> runtime.newImportStatementBuilder().setImport(s).build())
                .toList();
    }
}
