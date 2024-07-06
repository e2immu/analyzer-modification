package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.support.SetOnceMap;

import java.util.stream.Stream;

public class VariableDataImpl implements VariableData {
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData", new VariableDataImpl());

    private final SetOnceMap<String, VariableInfoContainer> vicByFqn = new SetOnceMap<>();

    @Override
    public Codec.EncodedValue encode(Codec codec) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VariableInfo variableInfo(String fqn) {
        return vicByFqn.get(fqn).best(Stage.MERGE);
    }

    public void put(Variable v, VariableInfoContainer vic) {
        vicByFqn.put(v.fullyQualifiedName(), vic);
    }

    @Override
    public Stream<VariableInfo> variableInfoStream() {
        return vicByFqn.valueStream().map(VariableInfoContainer::best);
    }
}
