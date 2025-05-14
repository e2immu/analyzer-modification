package org.e2immu.analyzer.modification.linkedvariables.impl;


import org.e2immu.analyzer.modification.linkedvariables.FieldAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;

public class FieldAnalyzerImpl implements FieldAnalyzer {
    @Override
    public Output go(FieldInfo fieldInfo) {
        return null;
    }

    private void fieldModified() {
        // fields should not be modified outside of construction
        Value.SetOfInfo poc = typeInfo.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION);
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
    }

    private boolean modifiesOrExposesField(TypeInfo typeInfo, MethodInfo methodInfo) {
        VariableData vd = VariableDataImpl.of(methodInfo.methodBody().lastStatement());
        return vd.variableInfoStream().anyMatch(vi -> {
            // modified
            if (vi.variable() instanceof FieldReference fr && inHierarchy(typeInfo, fr.fieldInfo().owner())
                && vi.isComputedModified()) {
                return true;
            }
            // exposed via the return variable
            if ((vi.variable() instanceof ReturnVariable || vi.variable() instanceof ParameterInfo)
                && vi.linkedVariables() != null
                && vi.linkedVariables().assignedOrDependentVariables()
                        .anyMatch(v -> v instanceof FieldReference fr && inHierarchy(typeInfo, fr.fieldInfo().owner()))) {
                Value.Immutable immutable = analysisHelper.typeImmutable(vi.variable().parameterizedType());
                return !immutable.isAtLeastImmutableHC();
            }
            return false;
        });
    }

    private boolean inHierarchy(TypeInfo typeInfo, TypeInfo fieldOwner) {
        if (typeInfo == fieldOwner) return true;
        if (typeInfo.parentClass() != null) {
            return inHierarchy(typeInfo.parentClass().typeInfo(), fieldOwner);
        }
        return false;
    }
}
