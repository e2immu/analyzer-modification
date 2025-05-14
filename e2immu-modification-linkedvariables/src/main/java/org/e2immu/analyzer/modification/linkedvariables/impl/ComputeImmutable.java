package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.*;

public class ComputeImmutable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ComputeImmutable.class);
    private final AnalysisHelper analysisHelper = new AnalysisHelper();

    public void go(TypeInfo typeInfo) {
        if (typeInfo.analysis().haveAnalyzedValueFor(IMMUTABLE_TYPE)) return;
        Value.Immutable immutable = computeImmutableType(typeInfo);
        if (immutable != null) {
            LOGGER.debug("Decide on {} for type {}", immutable, typeInfo);
            typeInfo.analysis().set(IMMUTABLE_TYPE, immutable);
        }
    }

    private Value.Immutable computeImmutableType(TypeInfo typeInfo) {
        TypeInfo parent = typeInfo.parentClass().typeInfo();
        Value.Immutable immutableParent = parent.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
        if (MUTABLE.equals(immutableParent)) return MUTABLE;
        Value.Immutable worst = immutableParent;

        for (ParameterizedType superType : typeInfo.interfacesImplemented()) {
            Value.Immutable immutableSuper = superType.typeInfo().analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
            if (MUTABLE.equals(immutableSuper)) return MUTABLE;
            worst = worst.min(immutableSuper);
        }
        boolean fieldsAssignableFromOutside = typeInfo.fields().stream()
                .anyMatch(fi -> !fi.isPropertyFinal() && !fi.access().isPrivate());
        if (fieldsAssignableFromOutside) return MUTABLE;

        // are any of the fields modified outside of construction?
        // are any of the fields exposed?
        boolean isImmutable = isImmutable(typeInfo);

        if (isImmutable && worst.isAtLeastImmutableHC()) {
            HiddenContentTypes hct = typeInfo.analysis().getOrNull(HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
            assert hct != null;
            return hct.hasHiddenContent() ? IMMUTABLE_HC : IMMUTABLE;
        }
        boolean allFieldsFinal = typeInfo.fields().stream().allMatch(FieldInfo::isPropertyFinal);
        Value.Immutable mine = allFieldsFinal ? FINAL_FIELDS : MUTABLE;
        return mine.min(worst);
    }

    private boolean isImmutable(TypeInfo typeInfo) {
        SetOfInfo poc = typeInfo.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION);
        for (MethodInfo methodInfo : typeInfo.methods()) {
            if (!poc.infoSet().contains(methodInfo)) {
                if (!methodInfo.methodBody().isEmpty() && modifiesOrExposesField(typeInfo, methodInfo)) {
                    return false;
                }
                if (methodInfo.isModifying()) return false;
                if (methodInfo.hasReturnValue()) {
                    Value.Independent independentResult = methodInfo.analysis()
                            .getOrDefault(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT);
                    if (independentResult.isDependent()) return false;
                }
                for (ParameterInfo parameterInfo : methodInfo.parameters()) {
                    Value.Independent independentParameter = parameterInfo.analysis()
                            .getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.DEPENDENT);
                    if (independentParameter.isDependent()) return false;
                }
            }
        }
        // fields should be private, or immutable for the type to be immutable
        for (FieldInfo fieldInfo : typeInfo.fields()) {
            if (!fieldInfo.access().isPrivate()) {
                Immutable immutable = analysisHelper.typeImmutable(fieldInfo.type());
                if (!immutable.isAtLeastImmutableHC()) return false;
            }
        }
        return true;
    }

    private boolean modifiesOrExposesField(TypeInfo typeInfo, MethodInfo methodInfo) {
        VariableData vd = VariableDataImpl.of(methodInfo.methodBody().lastStatement());
        return vd.variableInfoStream().anyMatch(vi -> {
            // modified
            if (vi.variable() instanceof FieldReference fr && inHierarchy(typeInfo, fr.fieldInfo().owner()) && vi.isModified()) {
                return true;
            }
            // exposed via the return variable
            if ((vi.variable() instanceof ReturnVariable || vi.variable() instanceof ParameterInfo)
                && vi.linkedVariables() != null
                && vi.linkedVariables().assignedOrDependentVariables()
                        .anyMatch(v -> v instanceof FieldReference fr && inHierarchy(typeInfo, fr.fieldInfo().owner()))) {
                Immutable immutable = analysisHelper.typeImmutable(vi.variable().parameterizedType());
                return !immutable.isAtLeastImmutableHC();
            }
            return false;
        });
    }

    private boolean inHierarchy(TypeInfo typeInfo, TypeInfo fieldOwner) {
        if (typeInfo == fieldOwner) return true;
        if (typeInfo.parentClass() != null) return inHierarchy(typeInfo.parentClass().typeInfo(), fieldOwner);
        return false;
    }

}
