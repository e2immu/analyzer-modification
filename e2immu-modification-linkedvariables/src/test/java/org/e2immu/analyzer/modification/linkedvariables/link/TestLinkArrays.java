package org.e2immu.analyzer.modification.linkedvariables.link;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestLinkArrays extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static <T> void method(T t, T[] array) {
                    T t0 = array[0];
                    array[1] = t;
                }
            }
            """;

    @DisplayName("basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 2);
        ParameterInfo t = method.parameters().get(0);
        ParameterInfo array = method.parameters().get(1);
        {
            VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().get(0));
            VariableInfo vi0T0 = vd0.variableInfo("t0");
            assertEquals("*-4-0:array, -1-:array[0]", vi0T0.linkedVariables().toString());
            VariableInfo vi0Array = vd0.variableInfo(array);
            assertEquals("0-4-*:array[0], 0-4-*:t0", vi0Array.linkedVariables().toString());
            assertFalse(vi0Array.isModified());
        }
        {
            VariableData vd1 = VariableDataImpl.of(method.methodBody().statements().get(1));
            VariableInfo vi1T0 = vd1.variableInfo("t0");
            assertEquals("*-4-0:array, -1-:array[0]", vi1T0.linkedVariables().toString());
            VariableInfo vi1T = vd1.variableInfo(t);
            assertEquals("*-4-0:array, -1-:array[1]", vi1T.linkedVariables().toString());
            VariableInfo vi1Array = vd1.variableInfo(array);
            assertEquals("0-4-*:array[0], 0-4-*:array[1], 0-4-*:t, 0-4-*:t0", vi1Array.linkedVariables().toString());
            assertTrue(vi1Array.isModified());
        }
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            class X {
                interface I<T> {
                    @GetSet("variables")
                    T get(int i);
                    @GetSet("variables")
                    void set(int i, T t);
                }
                static <T> void method(T t, I<T> i) {
                    T t0 = i.get(0);
                    i.set(1, t);
                }
            }
            """;

    @DisplayName("getter/setter")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 2);
        testCommon23(method);
    }

    private void testCommon23(MethodInfo method) {
        ParameterInfo t = method.parameters().get(0);
        ParameterInfo array = method.parameters().get(1);
        {
            VariableData vd0 = VariableDataImpl.of(method.methodBody().statements().get(0));
            VariableInfo vi0T0 = vd0.variableInfo("t0");
            assertEquals("E=i.variables[0]", vi0T0.staticValues().toString());
            assertEquals("*-4-0:i, *-4-0:variables, -1-:variables[0]", vi0T0.linkedVariables().toString());
            VariableInfo vi0Array = vd0.variableInfo(array);
            assertEquals("0-4-*:t0, 0-4-0:variables, 0-4-*:variables[0]", vi0Array.linkedVariables().toString());
            assertFalse(vi0Array.isModified());
        }
        {
            VariableData vd1 = VariableDataImpl.of(method.methodBody().statements().get(1));
            VariableInfo vi1T0 = vd1.variableInfo("t0");
            assertEquals("*-4-0:i, *-4-0:variables, -1-:variables[0]", vi1T0.linkedVariables().toString());
            assertFalse(vi1T0.isModified());

            VariableInfo vi1T = vd1.variableInfo(t);
            assertEquals("*-4-0:i, *-4-0:variables", vi1T.linkedVariables().toString());
            assertFalse(vi1T.isModified());

            VariableInfo vi1Array = vd1.variableInfo(array);
            assertEquals("0-4-*:t, 0-4-*:t0, 0-4-0:variables, 0-4-*:variables[0]", vi1Array.linkedVariables().toString());
            assertTrue(vi1Array.isModified());
        }
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            class X {
                interface I {
                    @GetSet("variables")
                    Object get(int i);
                    @GetSet("variables")
                    void set(int i, Object t);
                }
                static void method1(Object t, I i) {
                    Object t0 = i.get(0);
                    i.set(1, t);
                }
                static void method2(int t, I i) {
                    int t0 = (int) i.get(0);
                    i.set(1, t);
                }
            }
            """;

    @DisplayName("getter/setter, Object, cast in between")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method1 = X.findUniqueMethod("method1", 2);
        testCommon23(method1);

        MethodInfo method2 = X.findUniqueMethod("method2", 2);
        ParameterInfo t = method2.parameters().get(0);
        ParameterInfo array = method2.parameters().get(1);
        {
            VariableData vd0 = VariableDataImpl.of(method2.methodBody().statements().get(0));
            VariableInfo vi0T0 = vd0.variableInfo("t0");
            assertEquals("E=i.variables[0]", vi0T0.staticValues().toString());
            assertEquals("-1-:variables[0]", vi0T0.linkedVariables().toString());
            VariableInfo vi0Array = vd0.variableInfo(array);
            assertEquals("", vi0Array.linkedVariables().toString());
            assertFalse(vi0Array.isModified());
        }
        {
            VariableData vd1 = VariableDataImpl.of(method2.methodBody().statements().get(1));
            VariableInfo vi1T0 = vd1.variableInfo("t0");
            assertEquals("-1-:variables[0]", vi1T0.linkedVariables().toString());
            assertFalse(vi1T0.isModified());

            VariableInfo vi1T = vd1.variableInfo(t);
            assertEquals("", vi1T.linkedVariables().toString());
            assertFalse(vi1T.isModified());

            VariableInfo vi1Array = vd1.variableInfo(array);
            assertEquals("", vi1Array.linkedVariables().toString());
            assertTrue(vi1Array.isModified());
        }
    }

    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            class B {
                static class M { private int i; public int get() { return i; } public void set(int i) { this.i = i; } }
                public static M[][] transpose(M[][] a) {
                    int m = a.length;
                    int n = a[0].length;
                    M[][] t = new M[n][m];
                    for (int i = 0; i < m; i++) {
                        for (int j = 0; j < n; j++) {
                            t[j][i] = a[i][j];
                        }
                    }
                    return t;
                }
            }
            """;

    @DisplayName("double array of mutable objects")
    @Test
    public void test4() {
        TypeInfo B = javaInspector.parse(INPUT4);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
        MethodInfo transpose = B.findUniqueMethod("transpose", 1);
        ParameterInfo a = transpose.parameters().get(0);
        {
            VariableData vd = VariableDataImpl.of(transpose.methodBody().statements().get(0));
            VariableInfo viA = vd.variableInfo(a);
            assertEquals("", viA.linkedVariables().toString());
            VariableInfo viM = vd.variableInfo("m");
            assertEquals("", viM.linkedVariables().toString());
        }
        {
            VariableData vd = VariableDataImpl.of(transpose.methodBody().statements().get(1));
            assertEquals("a.b.B.transpose(a.b.B.M[][]):0:a, a.b.B.transpose(a.b.B.M[][]):0:a[0], m, n",
                    vd.knownVariableNamesToString());
            VariableInfo viN = vd.variableInfo("n");
            assertEquals("", viN.linkedVariables().toString());

            // FIXME should we already link a and a[0] ??
            VariableInfo viA = vd.variableInfo(a);
            assertEquals("", viA.linkedVariables().toString());
            VariableInfo viA0 = vd.variableInfo("a.b.B.transpose(a.b.B.M[][]):0:a[0]");
            assertEquals("", viA0.linkedVariables().toString());
        }

        {
            VariableData vd = VariableDataImpl.of(transpose.methodBody().statements().get(2));
            assertEquals("a.b.B.transpose(a.b.B.M[][]):0:a, a.b.B.transpose(a.b.B.M[][]):0:a[0], m, n, t",
                    vd.knownVariableNamesToString());
            VariableInfo viN = vd.variableInfo("n");
            assertEquals("", viN.linkedVariables().toString());

            VariableInfo viT = vd.variableInfo("t");
            assertEquals("", viT.linkedVariables().toString());

            VariableInfo viA = vd.variableInfo(a);
            assertEquals("", viA.linkedVariables().toString());
            VariableInfo viA0 = vd.variableInfo("a.b.B.transpose(a.b.B.M[][]):0:a[0]");
            assertEquals("", viA0.linkedVariables().toString());
        }
        {
            VariableData vd = VariableDataImpl.of(transpose.methodBody().statements().get(3).block()
                    .statements().get(0).block().statements().get(0));
            assertEquals("""
                            a.b.B.transpose(a.b.B.M[][]):0:a, a.b.B.transpose(a.b.B.M[][]):0:a[0], \
                            a.b.B.transpose(a.b.B.M[][]):0:a[i], a.b.B.transpose(a.b.B.M[][]):0:a[i][j], \
                            i, j, m, n, t, t[j], t[j][i]\
                            """,
                    vd.knownVariableNamesToString());
            VariableInfo viTJI = vd.variableInfo("t[j][i]");
            assertEquals("*M-2-0M|*-0.0:a, *M-2-0M|*-0:a[i], -1-:a[i][j], *M-2-0M|*-0.0:t, *M-2-0M|*-0:t[j]",
                    viTJI.linkedVariables().toString());

            // FIXME why 0-2-0:a[i] and not 0M-2-0M??
            VariableInfo viT = vd.variableInfo("t");
            assertEquals("0M-2-0M:a, 0-2-0:a[i], 0M-2-*M|0.0-*:a[i][j], 0M-2-*M|0-*:t[j], 0M-2-*M|0.0-*:t[j][i]",
                    viT.linkedVariables().toString());

            // should we already link a and a[0] ??
            VariableInfo viA = vd.variableInfo(a);
            assertEquals("0M-2-*M|0-*:a[i], 0M-2-*M|0.0-*:a[i][j], 0M-2-0M:t, 0-2-0:t[j], 0M-2-*M|0.0-*:t[j][i]",
                    viA.linkedVariables().toString());

            VariableInfo viA0 = vd.variableInfo("a.b.B.transpose(a.b.B.M[][]):0:a[0]");
            assertEquals("", viA0.linkedVariables().toString());
        }
    }
}