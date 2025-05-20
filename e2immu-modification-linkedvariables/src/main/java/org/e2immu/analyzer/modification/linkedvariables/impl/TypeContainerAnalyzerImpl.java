package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.TypeContainerAnalyzer;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TypeContainerAnalyzerImpl extends CommonAnalyzerImpl implements TypeContainerAnalyzer {

    private record OutputImpl(Set<MethodInfo> externalWaitForCannotCauseCycles) implements Output {

        @Override
        public List<Throwable> problemsRaised() {
            return List.of();
        }
    }

    @Override
    public Output go(TypeInfo typeInfo) {
        Value.Bool container = typeInfo.analysis().getOrNull(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.class);
        if (container == null) {
            Set<MethodInfo> externalWaitForCannotCauseCycles = new HashSet<>();
            boolean isContainer = true;
            for (MethodInfo methodInfo : typeInfo.constructorsAndMethods()) {
                for (ParameterInfo pi : methodInfo.parameters()) {
                    Value.Bool unmodified = pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER,
                            ValueImpl.BoolImpl.class);
                    if (unmodified == null) {
                        externalWaitForCannotCauseCycles.add(methodInfo);
                    } else if (unmodified.isFalse()) {
                        isContainer = false;
                        break;
                    }
                }
                if (!isContainer) break;
            }
            if (externalWaitForCannotCauseCycles.isEmpty()) {
                typeInfo.analysis().set(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.from(isContainer));
                DECIDE.debug("Decide container of type {} = {}", typeInfo, isContainer);
            } else {
                UNDECIDED.debug("Container of type {} undecided: {}", typeInfo, externalWaitForCannotCauseCycles);
            }
            return new OutputImpl(externalWaitForCannotCauseCycles);
        }
        return new OutputImpl(Set.of());
    }
}
