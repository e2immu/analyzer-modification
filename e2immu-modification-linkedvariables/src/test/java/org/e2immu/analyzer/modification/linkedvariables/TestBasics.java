package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestBasics extends CommonTest {

    public TestBasics() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class X {
                static boolean setAdd(Set<String> set, String s) {
                    return set.add(s);
                }
            }
            """;

    @DisplayName("modification already done by prep-work, check no links")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        prepWork(X);
        MethodInfo setAdd = X.findUniqueMethod("setAdd", 2);
        analyzer.doMethod(setAdd);
        VariableData vd = setAdd.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd);
        ParameterInfo set = setAdd.parameters().get(0);
        Statement s0 = setAdd.methodBody().statements().get(0);
        MethodCall mc = (MethodCall) s0.expression();
        assertTrue(mc.methodInfo().analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue());

        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo viSet0 = vd0.variableInfo(set);
        assertTrue(viSet0.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
        assertEquals(LinkedVariablesImpl.EMPTY, viSet0.linkedVariables());

        // this should have reached the method
        assertTrue(set.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.List;
            class X {
                static <T> T get(List<T> list, int i) {
                    return list.get(i);
                }
            }
            """;

    @DisplayName("return list.get(index)")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        prepWork(X);
        MethodInfo listGet = X.findUniqueMethod("get", 2);
        analyzer.doMethod(listGet);

        Statement s0 = listGet.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd0);
        VariableInfo viRv = vd0.variableInfo(listGet.fullyQualifiedName());
        assertEquals("*-4-0:list", viRv.linkedVariables().toString());

        assertEquals(viRv.linkedVariables(), listGet.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY));
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.List;
            class X {
                static <T> void listAdd(List<T> list, T t) {
                    list.add(t);
                }
            }
            """;

    @DisplayName("list.add(t)")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        prepWork(X);
        MethodInfo listAdd = X.findUniqueMethod("listAdd", 2);
        analyzer.doMethod(listAdd);

        Statement s0 = listAdd.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd0);
        ParameterInfo listAdd0 = listAdd.parameters().get(0);
        VariableInfo vi0 = vd0.variableInfo(listAdd0);
        assertEquals("0-4-*:t", vi0.linkedVariables().toString());
        assertEquals(vi0.linkedVariables(),
                listAdd0.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER, LinkedVariablesImpl.EMPTY));
    }

    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                  final static class M {
                      private int i;
                      public int getI() { return i; }
                      public void setI(int i) { this.i = i; }
                  }
                static <T> List<T> copy(List<T> list) {
                    return new ArrayList<>(list);
                }
                static List<M> copyM(List<M> list) {
                    return new ArrayList<>(list);
                }
                static List<String> copyS(List<String> list) {
                    return new ArrayList<>(list);
                }
            }
            """;

    @DisplayName("return new ArrayList<>(list)")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        prepWork(X);

        MethodInfo methodInfo = X.findUniqueMethod("copy", 1);
        analyzer.doMethod(methodInfo);
        LinkedVariables lvRv = methodInfo.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY);
        assertEquals("0-4-0:list", lvRv.toString());

        TypeInfo M = X.findSubType("M");
        assertTrue(M.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isMutable());
        assertFalse(M.isExtensible());
        assertTrue(M.isStatic());

        MethodInfo methodInfoM = X.findUniqueMethod("copyM", 1);
        analyzer.doMethod(methodInfoM);
        assertSame(M, methodInfoM.returnType().parameters().get(0).typeInfo());
        LinkedVariables lvRvM = methodInfoM.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY);
        // for this to be correct, M must be modifying
        assertEquals("0M-4-0M:list", lvRvM.toString());

        MethodInfo methodInfoS = X.findUniqueMethod("copyS", 1);
        analyzer.doMethod(methodInfoS);
        assertSame(runtime.stringTypeInfo(), methodInfoS.returnType().parameters().get(0).typeInfo());
        LinkedVariables lvRvS = methodInfoS.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY);
        assertTrue(lvRvS.isEmpty());
    }


    @Language("java")
    private static final String INPUT5 = """
            package a.b;
            import java.util.List;
            class X {
                static <T> void listAdd(List<T> list, T t) {
                    List<T> l = list.subList(0, 10);
                    l.add(t);
                }
            }
            """;

    @DisplayName("subList, direct assignment")
    @Test
    public void test5() {
        TypeInfo X = javaInspector.parse(INPUT5);
        prepWork(X);
        MethodInfo listAdd = X.findUniqueMethod("listAdd", 2);
        analyzer.doMethod(listAdd);

        Statement s0 = listAdd.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd0);
        VariableInfo vi0 = vd0.variableInfo("l");
        assertEquals("0-2-0:list", vi0.linkedVariables().toString());

        Statement s1 = listAdd.methodBody().statements().get(1);
        VariableData vd1 = s1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        ParameterInfo listAdd0 = listAdd.parameters().get(0);
        ParameterInfo t1 = listAdd.parameters().get(1);

        VariableInfo vi1 = vd1.variableInfo("l");
        assertEquals("0-2-0:list,0-4-*:t", vi1.linkedVariables().toString());

        // this one can only be computed through the graph algorithm
        VariableInfo vi1p0 = vd1.variableInfo(listAdd0);
        assertEquals("0-2-0:l,0-4-*:t", vi1p0.linkedVariables().toString());

        VariableInfo vi1p1 = vd1.variableInfo(t1);
        assertEquals("*-4-0:l,*-4-0:list", vi1p1.linkedVariables().toString());

        // in the parameter's list, the local variable has been filtered out
        LinkedVariables lvP0 = listAdd0.analysis().getOrNull(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER,
                LinkedVariablesImpl.class);
        assertEquals("0-4-*:t", lvP0.toString());
        LinkedVariables lvP1 = t1.analysis().getOrNull(LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER,
                LinkedVariablesImpl.class);
        assertEquals("*-4-0:list", lvP1.toString());
    }

}
