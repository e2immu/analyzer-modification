package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record StaticValuesImpl(ParameterizedType type,
                               Expression expression,
                               boolean multipleExpressions,
                               Map<Variable, Expression> values) implements StaticValues {

    public StaticValuesImpl {
        assert !multipleExpressions || expression == null;
        assert expression == null || expression.source() != null;
    }

    public static final StaticValues NONE = new StaticValuesImpl(null, null, false, Map.of());
    public static final StaticValues NOT_COMPUTED = new StaticValuesImpl(null, null, true, Map.of());

    public static final Property STATIC_VALUES_METHOD = new PropertyImpl("staticValuesMethod", NONE);
    public static final Property STATIC_VALUES_FIELD = new PropertyImpl("staticValuesField", NONE);
    public static final Property STATIC_VALUES_PARAMETER = new PropertyImpl("staticValuesParameter", NONE);

    public static StaticValues of(Expression e) {
        return new StaticValuesImpl(null, e, false, Map.of());
    }

    @Override
    public boolean isEmpty() {
        return type == null && expression == null && values.isEmpty() && !multipleExpressions;
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        if (isEmpty()) return null;
        Codec.EncodedValue encodedType = codec.encodeType(context, type);
        Codec.EncodedValue encodedExpression = codec.encodeExpression(context, expression);
        Map<Codec.EncodedValue, Codec.EncodedValue> mapOfEncoded = values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        e -> codec.encodeVariable(context, e.getKey()),
                        e -> codec.encodeExpression(context, e.getValue())));
        Codec.EncodedValue encodedMap = codec.encodeMapAsList(context, mapOfEncoded);
        return codec.encodeList(context, List.of(encodedType, encodedExpression, encodedMap));
    }

    public static Value decode(Codec codec, Codec.Context context, Codec.EncodedValue ev) {
        List<Codec.EncodedValue> list = codec.decodeList(context, ev);
        ParameterizedType type = codec.decodeType(context, list.get(0));
        Expression expression = codec.decodeExpression(context, list.get(1));
        Map<Codec.EncodedValue, Codec.EncodedValue> mapOfDecoded = codec.decodeMapAsList(context, list.get(2));
        Map<Variable, Expression> values = mapOfDecoded.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                e -> codec.decodeVariable(context, e.getKey()),
                e -> codec.decodeExpression(context, e.getValue())));
        return new StaticValuesImpl(type, expression, false, values);
    }

    @Override
    public StaticValues merge(StaticValues other) {
        if (this == NONE) return other;
        if (other == NONE) return this;

        ParameterizedType newType = type == null ? other.type() : type;
        boolean newMultipleExpressions;
        Expression newExpression;
        Expression otherExpression = other.expression();
        if (multipleExpressions || other.multipleExpressions()) {
            newMultipleExpressions = true;
            newExpression = null;
        } else if (expression == null) {
            newExpression = otherExpression;
            newMultipleExpressions = false;
        } else if (otherExpression == null) {
            newExpression = expression;
            newMultipleExpressions = false;
        } else if (expression.equals(otherExpression)) {
            newMultipleExpressions = false;
            newExpression = expression;
        } else {
            newMultipleExpressions = true;
            newExpression = null;
        }

        Map<Variable, Expression> newMap;
        if (other.values().isEmpty()) newMap = values;
        else if (values.isEmpty()) newMap = other.values();
        else {
            Map<Variable, Expression> map = new HashMap<>(values);
            other.values().forEach(map::putIfAbsent);
            newMap = Map.copyOf(map);
        }
        return new StaticValuesImpl(newType, newExpression, newMultipleExpressions, newMap);
    }

    @Override
    public StaticValues remove(Predicate<Variable> predicate) {
        return new StaticValuesImpl(type, expression, multipleExpressions, values.entrySet().stream()
                .filter(e -> !predicate.test(e.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (type != null) sb.append(type).append(" ");
        if (expression != null) sb.append("E=").append(expression).append(" ");
        if (!values.isEmpty()) {
            sb.append(values.entrySet().stream()
                    .map(e -> simpleNameWithScope(e.getKey()) + "=" + e.getValue())
                    .sorted()
                    .collect(Collectors.joining(", ")));
        }
        return sb.toString().trim();
    }

    private static String simpleNameWithScope(Variable v) {
        if (v instanceof FieldReference fr && fr.scopeVariable() != null) {
            return simpleNameWithScope(fr.scopeVariable()) + "." + v.simpleName();
        }
        return v.simpleName();
    }

    public static StaticValues from(VariableData variableDataPrevious, Stage stageOfPrevious, Variable v) {
        if (variableDataPrevious != null) {
            VariableInfoContainer vicVar = variableDataPrevious.variableInfoContainerOrNull(v.fullyQualifiedName());
            if (vicVar != null) {
                VariableInfo viVar = vicVar.best(stageOfPrevious);
                StaticValues staticValues = viVar.staticValues();
                if (staticValues != null) {
                    return staticValues;
                }
            }
        }
        return NONE;
    }
}
