package org.e2immu.analyzer.modification.prepwork.variable;

import java.util.stream.Stream;

public interface VariableData {
    VariableInfo getLatestVariableInfo(String s);

    Stream<VariableInfo> variableInfoStream();
}
