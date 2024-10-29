package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.ExpressionAsStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestArrayVariable extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.lang.reflect.Array;
            public class C {
                public static void put(Object array, Object element, int index) {
                    if (array instanceof Object[]) {
                        ((Object[]) array)[index] = element;
                    } else {
                        Array.set(array, index, element);
                    }
                }
            }
            """;

    @DisplayName("array variable")
    @Test
    public void test1() {
        TypeInfo C = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(C);
        analyzer.doPrimaryType(C, ao);
        MethodInfo put = C.findUniqueMethod("put", 3);
        ParameterInfo put0 = put.parameters().get(0);

        Statement s000 = put.methodBody().statements().get(0).block().statements().get(0);
        VariableData vd000 = VariableDataImpl.of(s000);

        Variable av = ((DependentVariable) ((Assignment) s000.expression()).variableTarget()).arrayVariable();
        assertEquals("av-5-13", av.fullyQualifiedName());

        VariableInfo viAv = vd000.variableInfo(av);
        assertEquals("-1-:array, 0-4-*:av-5-13[index], 0-4-*:element", viAv.linkedVariables().toString());
        assertTrue(viAv.isModified());
        VariableInfo viArray = vd000.variableInfo(put0);
        assertEquals("-1-:av-5-13, 0-4-*:av-5-13[index], 0-4-*:element", viArray.linkedVariables().toString());
        assertTrue(viArray.isModified());

        assertEquals("0-4-*:av-5-13[index], 0-4-*:element", put0.analysis().getOrNull(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER,
                LinkedVariablesImpl.class).toString());
        assertTrue(put0.isModified());
    }
}
