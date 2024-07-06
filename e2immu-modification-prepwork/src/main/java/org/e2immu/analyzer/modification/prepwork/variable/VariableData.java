package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.language.cst.api.analysis.Value;

import java.util.Set;
import java.util.stream.Stream;

public interface VariableData extends Value {
    Set<String> knownVariableNames();

    boolean isKnown(String fullyQualifiedName);

    VariableInfo variableInfo(String fullyQualifiedName);

    VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName);

    Stream<VariableInfoContainer> variableInfoContainerStream();

    Stream<VariableInfo> variableInfoStream();
}
