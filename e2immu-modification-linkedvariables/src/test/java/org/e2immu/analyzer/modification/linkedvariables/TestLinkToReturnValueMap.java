package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLinkToReturnValueMap extends CommonTest {

    public TestLinkToReturnValueMap() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            class X {
                final static class M {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
                static <X, Y> Map<X, Y> copy(Map<X, Y> map) {
                    return new HashMap<>(map);
                }
                static <X> Map<X, M> copy2(Map<X, M> map) {
                    return new HashMap<>(map);
                }
                static <Y> Map<M, Y> copy3(Map<M, Y> map) {
                    return new HashMap<>(map);
                }
                static Map<M, String> copy4(Map<M, String> map) {
                    return new HashMap<>(map);
                }
                static <Y> Map<String, Y> copy5(Map<String, Y> map) {
                    return new HashMap<>(map);
                }
            }
            """;

    @DisplayName("return new HashMap<>(map)")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        prepWork(X);

        MethodInfo copy = X.findUniqueMethod("copy", 1);
        analyzer.doMethod(copy);

        assertEquals("0,1-4-0,1:map", copy.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo copy2 = X.findUniqueMethod("copy2", 1);
        analyzer.doMethod(copy2);
        assertEquals("0,1M-4-0,1M:map", copy2.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo copy3 = X.findUniqueMethod("copy3", 1);
        analyzer.doMethod(copy3);
        assertEquals("0M,1-4-0M,1:map", copy3.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo copy4 = X.findUniqueMethod("copy4", 1);
        analyzer.doMethod(copy4);
        assertEquals("0M-4-0M:map", copy4.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo copy5 = X.findUniqueMethod("copy5", 1);
        analyzer.doMethod(copy5);
        assertEquals("1-4-1:map", copy5.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());
    }


}