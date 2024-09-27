package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.HashMap;
import java.util.Map;

public record StaticValuesImpl(ParameterizedType type,
                               Expression expression,
                               Map<Variable, Expression> values) implements StaticValues {

    public static final StaticValues NONE = new StaticValuesImpl(null, null, Map.of());

    @Override
    public StaticValues merge(StaticValues other) {
        if (this == NONE) return other;
        if (other == NONE) return this;

        ParameterizedType newType = type == null ? other.type() : type;
        Expression newExpression = expression == null ? other.expression() : expression;
        Map<Variable, Expression> newMap;
        if (other.values().isEmpty()) newMap = values;
        else if (values.isEmpty()) newMap = other.values();
        else {
            Map<Variable, Expression> map = new HashMap<>(values);
            other.values().forEach(map::putIfAbsent);
            newMap = Map.copyOf(map);
        }
        return new StaticValuesImpl(newType, newExpression, newMap);
    }
}
