package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.Stage;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.support.SetOnceMap;

import java.util.stream.Stream;

public class VariableDataImpl implements VariableData, Value {
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData", new VariableDataImpl());

    private final SetOnceMap<String, VariableInfoContainer> vicByFqn = new SetOnceMap<>();

    @Override
    public Codec.EncodedValue encode(Codec codec) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VariableInfo getLatestVariableInfo(String fqn) {
        return vicByFqn.get(fqn).best(Stage.MERGE);
    }

    @Override
    public Stream<VariableInfo> variableInfoStream() {
        return vicByFqn.valueStream().map(VariableInfoContainer::best);
    }
}
