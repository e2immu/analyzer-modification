package org.e2immu.analyzer.modification.io;

import org.e2immu.annotation.*;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.element.Comment;
import org.e2immu.language.cst.api.element.ImportStatement;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;

public class DecoratorImpl implements Qualification.Decorator {
    private final Runtime runtime;
    private final AnnotationExpression notModifiedAnnotation;
    private final TypeInfo notModifiedTi;
    private final TypeInfo immutableTi;
    private final TypeInfo independentTi;
    private final TypeInfo finalTi;
    private final TypeInfo immutableContainerTi;
    private final TypeInfo notNullTi;
    private final AnnotationExpression identityAnnotation;
    private final AnnotationExpression fluentAnnotation;
    private final AnnotationExpression finalAnnotation;
    private final AnnotationExpression containerAnnotation;

    private boolean needContainerImport;
    private boolean needUnmodifiedImport;
    private boolean needImmutableImport;
    private boolean needIndependentImport;
    private boolean needFinalImport;
    private boolean needImmutableContainerImport;
    private boolean needIdentityImport;
    private boolean needFluentImport;
    private boolean needNotNullImport;

    private final Map<Info, Info> translationMap;

    public DecoratorImpl(Runtime runtime) {
        this(runtime, null);
    }

    public DecoratorImpl(Runtime runtime, Map<Info, Info> translationMap) {
        this.runtime = runtime;
        notModifiedTi = runtime.getFullyQualified(NotModified.class, true);
        notModifiedAnnotation = runtime.newAnnotationExpressionBuilder().setTypeInfo(notModifiedTi).build();
        independentTi = runtime.getFullyQualified(Independent.class, true);
        immutableTi = runtime.getFullyQualified(Immutable.class, true);
        finalTi = runtime.getFullyQualified(Final.class, true);
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

    protected List<AnnotationProperty> annotationAndProperties(Info infoIn) {
        Info info = translationMap == null ? infoIn : translationMap.getOrDefault(infoIn, infoIn);
        Property propertyUnmodified;
        Value.Immutable immutable;
        Property propertyImmutable;
        Value.Independent independent;
        Property propertyIndependent;
        Property propertyFinalField;
        Property propertyContainer;
        Property propertyIdentity;
        Property propertyFluent;
        Value.NotNull notNull;
        Property propertyNotNull;
        PropertyValueMap analysis = info.analysis();
        switch (info) {
            case MethodInfo methodInfo -> {
                propertyUnmodified = !methodInfo.isConstructor() && analysis.getOrDefault(NON_MODIFYING_METHOD, FALSE).isTrue()
                        ? NON_MODIFYING_METHOD : null;
                immutable = null;
                propertyImmutable = null;
                independent = independent(analysis.getOrDefault(INDEPENDENT_METHOD, DEPENDENT), methodInfo.returnType());
                propertyIndependent = INDEPENDENT_METHOD;
                propertyFinalField = null;
                propertyIdentity = methodInfo.isIdentity() ? IDENTITY_METHOD : null;
                propertyContainer = null;
                propertyFluent = methodInfo.isFluent() ? FLUENT_METHOD : null;
                notNull = analysis.getOrDefault(NOT_NULL_METHOD, ValueImpl.NotNullImpl.NULLABLE);
                propertyNotNull = NOT_NULL_METHOD;
            }
            case FieldInfo fieldInfo -> {
                propertyUnmodified = !fieldInfo.type().isPrimitiveStringClass()
                                     && analysis.getOrDefault(UNMODIFIED_FIELD, FALSE).isTrue() ? UNMODIFIED_FIELD : null;
                immutable = null;
                propertyImmutable = null;
                independent = independent(analysis.getOrDefault(INDEPENDENT_FIELD, DEPENDENT), fieldInfo.type());
                propertyIndependent = INDEPENDENT_FIELD;
                propertyFinalField = !fieldInfo.isFinal() && fieldInfo.isPropertyFinal() ? FINAL_FIELD : null;
                propertyContainer = null;
                propertyIdentity = null;
                propertyFluent = null;
                propertyNotNull = NOT_NULL_FIELD;
                notNull = analysis.getOrDefault(NOT_NULL_FIELD, ValueImpl.NotNullImpl.NULLABLE);
            }
            case ParameterInfo pi -> {
                propertyUnmodified = !pi.parameterizedType().isPrimitiveStringClass() &&
                                     analysis.getOrDefault(UNMODIFIED_PARAMETER, FALSE).isTrue() ? UNMODIFIED_PARAMETER : null;
                immutable = null;
                propertyImmutable = null;
                independent = independent(analysis.getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT), pi.parameterizedType());
                propertyIndependent = INDEPENDENT_PARAMETER;
                propertyFinalField = null;
                propertyContainer = null;
                propertyIdentity = null;
                propertyFluent = null;
                propertyNotNull = NOT_NULL_PARAMETER;
                notNull = analysis.getOrDefault(NOT_NULL_PARAMETER, ValueImpl.NotNullImpl.NULLABLE);
            }
            case TypeInfo typeInfo -> {
                propertyUnmodified = null;
                immutable = analysis.getOrDefault(IMMUTABLE_TYPE, MUTABLE);
                propertyImmutable = IMMUTABLE_TYPE;
                independent = nonTrivialIndependentType(analysis.getOrDefault(INDEPENDENT_TYPE, DEPENDENT), immutable);
                propertyIndependent = INDEPENDENT_TYPE;
                propertyContainer = analysis.getOrDefault(CONTAINER_TYPE, FALSE).isTrue() ? CONTAINER_TYPE : null;
                propertyFinalField = null;
                propertyIdentity = null;
                propertyFluent = null;
                notNull = null;
                propertyNotNull = null;
            }
            default -> throw new UnsupportedOperationException();
        }

        List<AnnotationProperty> list = new ArrayList<>();
        if (propertyFinalField != null) {
            needFinalImport = true;
            list.add(new AnnotationProperty(finalAnnotation, propertyFinalField));
        }
        if (propertyIdentity != null) {
            needIdentityImport = true;
            list.add(new AnnotationProperty(identityAnnotation, propertyIdentity));
        }
        if (propertyFluent != null) {
            needFluentImport = true;
            list.add(new AnnotationProperty(fluentAnnotation, propertyFluent));
        }
        if (immutable != null && !immutable.isMutable()) {
            TypeInfo ti;
            if (propertyContainer != null) {
                ti = immutableContainerTi;
                this.needImmutableContainerImport = true;
            } else {
                ti = immutableTi;
                this.needImmutableImport = true;
            }
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(ti);
            if (immutable.isImmutableHC()) {
                b.addKeyValuePair("hc", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyImmutable));
        } else if (propertyContainer != null) {
            needContainerImport = true;
            list.add(new AnnotationProperty(containerAnnotation, propertyContainer));
        }
        if (independent != null && !independent.isDependent()) {
            this.needIndependentImport = true;
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(independentTi);
            if (independent.isIndependentHc()) {
                b.addKeyValuePair("hc", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyIndependent));
        }
        if (propertyUnmodified != null) {
            this.needUnmodifiedImport = true;
            list.add(new AnnotationProperty(notModifiedAnnotation, propertyUnmodified));
        }
        if (notNull != null && !notNull.isNullable()) {
            this.needNotNullImport = true;
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(notNullTi);
            if (notNull.equals(ValueImpl.NotNullImpl.CONTENT_NOT_NULL)) {
                b.addKeyValuePair("content", runtime.constantTrue());
            }
            list.add(new AnnotationProperty(b.build(), propertyNotNull));
        }
        return list;
    }

    // we're only showing INDEPENDENT when both the type and the current type are not immutable (hc or not).
    private Value.Independent independent(Value.Independent independent, ParameterizedType parameterizedType) {
        if (parameterizedType.isVoidOrJavaLangVoid() || parameterizedType.isPrimitiveStringClass()) return null;
        return independent;
    }

    private Value.Independent nonTrivialIndependentType(Value.Independent independent, Value.Immutable immutable) {
        if (immutable.isAtLeastImmutableHC()) return null;
        return independent;
    }


    @Override
    public List<ImportStatement> importStatements() {
        List<ImportStatement> list = new ArrayList<>();
        if (needContainerImport) {
            list.add(runtime.newImportStatementBuilder().setImport(containerAnnotation.typeInfo().fullyQualifiedName()).build());
        }
        if (needFinalImport) {
            list.add(runtime.newImportStatementBuilder().setImport(finalTi.fullyQualifiedName()).build());
        }
        if (needFluentImport) {
            list.add(runtime.newImportStatementBuilder().setImport(fluentAnnotation.typeInfo().fullyQualifiedName()).build());
        }
        if (needIdentityImport) {
            list.add(runtime.newImportStatementBuilder().setImport(identityAnnotation.typeInfo().fullyQualifiedName()).build());
        }
        if (needIndependentImport) {
            list.add(runtime.newImportStatementBuilder().setImport(independentTi.fullyQualifiedName()).build());
        }
        if (needImmutableImport) {
            list.add(runtime.newImportStatementBuilder().setImport(immutableTi.fullyQualifiedName()).build());
        }
        if (needImmutableContainerImport) {
            list.add(runtime.newImportStatementBuilder().setImport(immutableContainerTi.fullyQualifiedName()).build());
        }
        if (needNotNullImport) {
            list.add(runtime.newImportStatementBuilder().setImport(notNullTi.fullyQualifiedName()).build());
        }
        if (needUnmodifiedImport) {
            list.add(runtime.newImportStatementBuilder().setImport(notModifiedTi.fullyQualifiedName()).build());
        }
        return list;
    }
}
