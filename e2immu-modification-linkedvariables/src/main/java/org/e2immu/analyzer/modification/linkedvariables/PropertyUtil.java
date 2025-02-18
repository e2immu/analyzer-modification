package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.Map;

public class PropertyUtil {

    public static boolean mergeBool(Map<Variable, Value.Bool> source, Map<Variable, Value.Bool> target) {
        boolean change = false;
        for (Map.Entry<Variable, Value.Bool> entry : source.entrySet()) {
            change |= mergeBool(target, entry.getKey(), entry.getValue());
        }
        return change;
    }

    public static boolean mergeBool(Map<Variable, Value.Bool> map, Variable variable, Value.Bool value) {
        Value.Bool current = map.get(variable);
        if (current == null) {
            map.put(variable, value);
            return true;
        }
        Value.Bool and = current.and(value);
        if (!and.equals(current)) {
            map.put(variable, and);
            return true;
        }
        return false;
    }
}
