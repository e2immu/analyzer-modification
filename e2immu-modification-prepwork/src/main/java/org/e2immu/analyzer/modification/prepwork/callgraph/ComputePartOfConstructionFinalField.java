package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;

import java.util.*;

/*
given the call graph, compute the linearization, and all supporting information to deal with cycles.
 */
public class ComputePartOfConstructionFinalField {
    public static final Value.ListOfInfo EMPTY_PART_OF_CONSTRUCTION = new ValueImpl.ListOfInfoImpl(List.of());
    public static final Property PART_OF_CONSTRUCTION = new PropertyImpl("partOfConstructionType", EMPTY_PART_OF_CONSTRUCTION);

    public void go(TypeInfo primaryType, G<Info> callGraph) {
        assert primaryType.isPrimaryType() : "Only call on primary types, and " + primaryType + " is not one!";
        Value.ListOfInfo list = primaryType.analysis().getOrNull(PART_OF_CONSTRUCTION, ValueImpl.ListOfInfoImpl.class);
        if (list != null) {
            return; // we're going to assume that all FINAL_FIELDS are set as well
        }
        Set<MethodInfo> partOfConstruction = computePartOfConstruction(callGraph);
        List<Info> sorted = partOfConstruction.stream().map(mi -> (Info) mi).sorted().toList();
        primaryType.analysis().set(PART_OF_CONSTRUCTION, new ValueImpl.ListOfInfoImpl(sorted));
        Map<FieldInfo, Boolean> effectivelyFinalFieldMap = computeEffectivelyFinalFields(callGraph, partOfConstruction);
        for (Map.Entry<FieldInfo, Boolean> entry : effectivelyFinalFieldMap.entrySet()) {
            FieldInfo fieldInfo = entry.getKey();
            if (!fieldInfo.analysis().haveAnalyzedValueFor(PropertyImpl.FINAL_FIELD)) {
                boolean isEffectivelyFinal = entry.getValue();
                fieldInfo.analysis().set(PropertyImpl.FINAL_FIELD, ValueImpl.BoolImpl.from(isEffectivelyFinal));
            }
        }
    }

    private Map<FieldInfo, Boolean> computeEffectivelyFinalFields(G<Info> callGraph, Set<MethodInfo> partOfConstruction) {
        Map<FieldInfo, Boolean> effectivelyFinalFieldMap = new HashMap<>();
        for (V<Info> v : callGraph.vertices()) {
            if (v.t() instanceof FieldInfo fieldInfo) {
                boolean isFinal = fieldInfo.isPropertyFinal() || fieldInfo.access().isPrivate();
                effectivelyFinalFieldMap.put(fieldInfo, isFinal);
            } else if (v.t() instanceof MethodInfo methodInfo && !methodInfo.methodBody().isEmpty()) {
                Map<V<Info>, Long> edges = callGraph.edges(v);
                if (edges != null) {
                    for (Map.Entry<V<Info>, Long> entry : edges.entrySet()) {
                        if (entry.getKey().t() instanceof FieldInfo toField
                            && notInConstructionOfSameStaticType(methodInfo, toField, partOfConstruction)) {
                            // so methodInfo references toField... check whether that is an assignment, or simply a read
                            boolean isAssigned = isAssigned(methodInfo, toField);
                            if (isAssigned) {
                                effectivelyFinalFieldMap.put(toField, false);
                            }
                        }
                    }
                }
            }
        }
        return effectivelyFinalFieldMap;
    }

    /*
    only in the part of construction of the same static type, we can ignore assignments.
     */
    private boolean notInConstructionOfSameStaticType(MethodInfo methodInfo, FieldInfo toField, Set<MethodInfo> partOfConstruction) {
        if (!partOfConstruction.contains(methodInfo)) return true;
        TypeInfo firstStaticOfMethod = firstStatic(methodInfo.typeInfo());
        TypeInfo firstStaticOfField = firstStatic(toField.owner());
        return firstStaticOfField != firstStaticOfMethod;
    }

    private static TypeInfo firstStatic(TypeInfo typeInfo) {
        if (typeInfo.isPrimaryType() || typeInfo.isStatic()) return typeInfo;
        if (typeInfo.enclosingMethod() != null) return firstStatic(typeInfo.enclosingMethod().typeInfo());
        return firstStatic(typeInfo.compilationUnitOrEnclosingType().getRight());
    }

    private boolean isAssigned(MethodInfo methodInfo, FieldInfo fieldInfo) {
        Statement lastStatement = methodInfo.methodBody().lastStatement();
        VariableData vd = lastStatement.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;
        return vd.variableInfoContainerStream()
                .filter(vic -> vic.variable() instanceof FieldReference fr && fr.fieldInfo() == fieldInfo)
                .map(VariableInfoContainer::best)
                .anyMatch(vi -> vi.assignments().size() > 0);
    }

    private Set<MethodInfo> computePartOfConstruction(G<Info> callGraph) {
        Set<MethodInfo> candidates = new HashSet<>();
        callGraph.vertices().forEach(v -> {
            if (v.t() instanceof MethodInfo mi && canBePartOfConstruction(mi)) {
                candidates.add(mi);
            }
        });
        boolean changes = true;
        while (changes) {
            changes = false;
            for (V<Info> v : callGraph.vertices()) {
                if (v.t() instanceof MethodInfo methodInfo) {
                    if (!canBePartOfConstruction(methodInfo)) {
                        Map<V<Info>, Long> edges = callGraph.edges(v);
                        if (edges != null) {
                            for (Map.Entry<V<Info>, Long> entry : edges.entrySet()) {
                                if (entry.getKey().t() instanceof MethodInfo toMethod) {
                                    changes |= candidates.remove(toMethod);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Set.copyOf(candidates);
    }

    private boolean canBePartOfConstruction(MethodInfo mi) {
        return mi.isConstructor() || mi.access().isPrivate() && mi.typeInfo().enclosingMethod() == null;
    }
}
