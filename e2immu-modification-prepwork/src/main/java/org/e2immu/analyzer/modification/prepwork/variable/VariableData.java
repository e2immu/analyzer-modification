package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.Set;
import java.util.stream.Stream;

public interface VariableData extends Value {
    Set<String> knownVariableNames();

    boolean isKnown(String fullyQualifiedName);

    VariableInfo variableInfo(String fullyQualifiedName);

    VariableInfo variableInfo(Variable variable, Stage stage);

    VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName);

    Stream<VariableInfoContainer> variableInfoContainerStream();

    default Stream<VariableInfo> variableInfoStream() {
        return variableInfoStream(Stage.MERGE);
    }

    Stream<VariableInfo> variableInfoStream(Stage stage);
}
