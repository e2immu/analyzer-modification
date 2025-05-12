package org.e2immu.analyzer.modification.io;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.annotation.*;
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
    private final AnalysisHelper analysisHelper;
    private final AnnotationExpression notModifiedAnnotation;
    private final TypeInfo notModifiedTi;
    private final TypeInfo immutableTi;
    private final TypeInfo independentTi;
    private final TypeInfo finalTi;
    private final TypeInfo immutableContainerTi;
    private final AnnotationExpression identityAnnotation;
    private final AnnotationExpression finalAnnotation;
    private final AnnotationExpression containerAnnotation;

    private boolean needContainerImport;
    private boolean needUnmodifiedImport;
    private boolean needImmutableImport;
    private boolean needIndependentImport;
    private boolean needFinalImport;
    private boolean needImmutableContainerImport;
    private boolean needIdentityImport;

    private final Map<Info, Info> translationMap;

    public DecoratorImpl(Runtime runtime) {
        this(runtime, null);
    }

    public DecoratorImpl(Runtime runtime, Map<Info, Info> translationMap) {
        this.runtime = runtime;
        analysisHelper = new AnalysisHelper();
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

    @Override
    public List<AnnotationExpression> annotations(Info infoIn) {
        Info info = translationMap == null ? infoIn : translationMap.getOrDefault(infoIn, infoIn);
        boolean unmodified;
        Value.Immutable immutable;
        Value.Independent independent;
        boolean isFinal;
        boolean isContainer;
        boolean isIdentity;
        PropertyValueMap analysis = info.analysis();
        switch (info) {
            case MethodInfo methodInfo -> {
                unmodified = !methodInfo.isConstructor() && analysis.getOrDefault(NON_MODIFYING_METHOD, FALSE).isTrue();
                immutable = null;
                independent = nonTrivialIndependent(analysis.getOrDefault(INDEPENDENT_METHOD, DEPENDENT), methodInfo.typeInfo(),
                        methodInfo.returnType());
                isFinal = false;
                isIdentity = methodInfo.isIdentity();
                isContainer = false;
            }
            case FieldInfo fieldInfo -> {
                unmodified = analysis.getOrDefault(UNMODIFIED_FIELD, FALSE).isTrue();
                immutable = null;
                independent = nonTrivialIndependent(analysis.getOrDefault(INDEPENDENT_FIELD, DEPENDENT),
                        fieldInfo.owner(), fieldInfo.type());
                isFinal = !fieldInfo.isFinal() && fieldInfo.isPropertyFinal();
                isContainer = false;
                isIdentity = false;
            }
            case ParameterInfo pi -> {
                unmodified = analysis.getOrDefault(UNMODIFIED_PARAMETER, FALSE).isTrue();
                immutable = null;
                independent = nonTrivialIndependent(analysis.getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT), pi.typeInfo(),
                        pi.parameterizedType());
                isFinal = false;
                isContainer = false;
                isIdentity = false;
            }
            case TypeInfo typeInfo -> {
                unmodified = false;
                immutable = analysis.getOrDefault(IMMUTABLE_TYPE, MUTABLE);
                independent = nonTrivialIndependentType(analysis.getOrDefault(INDEPENDENT_TYPE, DEPENDENT), immutable);
                isContainer = analysis.getOrDefault(CONTAINER_TYPE, FALSE).isTrue();
                isFinal = false;
                isIdentity = false;
            }
            default -> throw new UnsupportedOperationException();
        }

        List<AnnotationExpression> list = new ArrayList<>();
        if (isFinal) {
            needFinalImport = true;
            list.add(finalAnnotation);
        }
        if(isIdentity) {
            needIdentityImport = true;
            list.add(identityAnnotation);
        }
        if (immutable != null && !immutable.isMutable()) {
            TypeInfo ti;
            if (isContainer) {
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
            list.add(b.build());
        } else if (isContainer) {
            needContainerImport = true;
            list.add(containerAnnotation);
        }
        if (independent != null && !independent.isDependent()) {
            this.needIndependentImport = true;
            AnnotationExpression.Builder b = runtime.newAnnotationExpressionBuilder().setTypeInfo(independentTi);
            if (independent.isIndependentHc()) {
                b.addKeyValuePair("hc", runtime.constantTrue());
            }
            list.add(b.build());
        }
        if (unmodified) {
            this.needUnmodifiedImport = true;
            list.add(notModifiedAnnotation);
        }
        return list;
    }

    // we're only showing INDEPENDENT when both the type and the current type are not immutable (hc or not).
    private Value.Independent nonTrivialIndependent(Value.Independent independent, TypeInfo currentType, ParameterizedType parameterizedType) {
        if (parameterizedType.isVoidOrJavaLangVoid() || parameterizedType.isPrimitiveStringClass()) return null;
        Value.Immutable immutable = analysisHelper.typeImmutable(currentType, parameterizedType);
        if (immutable.isAtLeastImmutableHC()) return null; // no need
        Value.Immutable immutableCurrent = currentType.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
        if (immutableCurrent.isAtLeastImmutableHC()) return null; // no need
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
        if(needIdentityImport) {
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
        if (needUnmodifiedImport) {
            list.add(runtime.newImportStatementBuilder().setImport(notModifiedTi.fullyQualifiedName()).build());
        }
        return list;
    }
}
