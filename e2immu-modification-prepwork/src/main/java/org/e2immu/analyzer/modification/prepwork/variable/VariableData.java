package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.language.cst.api.analysis.Value;

import java.util.stream.Stream;

public interface VariableData extends Value {
    VariableInfo variableInfo(String s);

    Stream<VariableInfo> variableInfoStream();
}
