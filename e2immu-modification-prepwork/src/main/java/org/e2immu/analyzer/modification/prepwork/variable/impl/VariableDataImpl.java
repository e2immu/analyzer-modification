package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.Stage;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.support.SetOnceMap;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VariableDataImpl implements VariableData {
    public static final PropertyImpl VARIABLE_DATA = new PropertyImpl("variableData", new VariableDataImpl());

    private final SetOnceMap<String, VariableInfoContainer> vicByFqn = new SetOnceMap<>();

    @Override
    public boolean isDefault() {
        return vicByFqn.isEmpty();
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        return null;// not yet streamed
    }

    public void putIfAbsent(Variable v, VariableInfoContainer vic) {
        if (!vicByFqn.isSet(v.fullyQualifiedName())) {
            vicByFqn.put(v.fullyQualifiedName(), vic);
        }
    }

    @Override
    public boolean isKnown(String fullyQualifiedName) {
        return vicByFqn.isSet(fullyQualifiedName);
    }

    @Override
    public VariableInfoContainer variableInfoContainerOrNull(String fullyQualifiedName) {
        return vicByFqn.getOrDefaultNull(fullyQualifiedName);
    }

    @Override
    public VariableInfo variableInfo(String fqn) {
        return vicByFqn.get(fqn).best(Stage.MERGE);
    }

    public void put(Variable v, VariableInfoContainer vic) {
        vicByFqn.put(v.fullyQualifiedName(), vic);
    }

    @Override
    public Stream<VariableInfoContainer> variableInfoContainerStream() {
        return vicByFqn.valueStream();
    }

    @Override
    public Set<String> knownVariableNames() {
        return vicByFqn.keyStream().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String knownVariableNamesToString() {
        return knownVariableNames().stream().map(Object::toString).sorted().collect(Collectors.joining(", "));
    }

    @Override
    public Iterable<VariableInfo> variableInfoIterable() {
        Stream<VariableInfo> stream = vicByFqn.valueStream().map(vic -> vic.best(Stage.MERGE));
        return stream::iterator;
    }

    @Override
    public Stream<VariableInfo> variableInfoStream(Stage stage) {
        return vicByFqn.valueStream().map(vic -> vic.best(stage));
    }

    @Override
    public VariableInfo variableInfo(Variable variable, Stage stage) {
        return vicByFqn.get(variable.fullyQualifiedName()).best(stage);
    }

    public static VariableData of(Element element) {
        return element.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
    }
}
