package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;

public class VariableDataImpl implements VariableData, Value {
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData", new VariableDataImpl());

    @Override
    public Codec.EncodedValue encode(Codec codec) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VariableInfo getLatestVariableInfo(String s) {
        return null;
    }
}
