package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.impl.Assignments;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestAnalyze1Assignments extends CommonTest {


    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static int method(String in) {
                    int i = in.length();
                    int j;
                    int k;
                    if(i > 0) {
                        j = 1;
                        k = 2;
                        if(i > 1) {
                            return k;
                        }
                    } else {
                        j = 3;
                    }
                    return j;
                }
            }
            """;

    @DisplayName("basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 1);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals(5, vdMethod.knownVariableNames().size());

        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in, i, j, k",
                vdMethod.knownVariableNamesToString());
        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("0", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("3.0.2-E", iVi.readId());
        assertEquals("i", iVi.variable().simpleName());
        Assignments iA = iVi.assignments();
        assertEquals("D:0, A:[0=[0]]", iA.toString());

        IfElseStatement ifElseStatement = (IfElseStatement) method.methodBody().statements().get(3);
        Statement j1 = ifElseStatement.block().statements().get(0);
        VariableData vdJ1 = j1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo jViJ1 = vdJ1.variableInfo("j");
        assertEquals("-", jViJ1.readId());
        assertEquals("D:1, A:[3.0.0=[3.0.0]]", jViJ1.assignments().toString());

        Statement j3 = ifElseStatement.elseBlock().statements().get(0);
        VariableData vdJ3 = j3.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo jViJ3 = vdJ3.variableInfo("j");
        assertEquals("-", jViJ3.readId());
        assertEquals("D:1, A:[3.1.0=[3.1.0]]", jViJ3.assignments().toString());

        VariableData vdJIf = ifElseStatement.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo jViJIf = vdJIf.variableInfo("j");
        assertEquals("-", jViJIf.readId());
        assertEquals("D:1, A:[3:M=[3.0.0, 3.1.0]]", jViJIf.assignments().toString());

        VariableInfo jVi = vdMethod.variableInfo("j");
        assertEquals("j", jVi.variable().simpleName());
        assertEquals("4", jVi.readId());
        Assignments jA = jVi.assignments();
        assertEquals("D:1, A:[3:M=[3.0.0, 3.1.0]]", jA.toString());

        VariableInfo kVi = vdMethod.variableInfo("k");
        assertEquals("k", kVi.variable().simpleName());
        assertEquals("3.0.2.0.0", kVi.readId());
        Assignments kA = kVi.assignments();
        assertEquals("D:2, A:[3.0.1=[3.0.1]]", kA.toString());

        VariableInfo rv = vdMethod.variableInfo(method.fullyQualifiedName());
        assertEquals("-", rv.readId());
        assertEquals("D:-, A:[3.0.2.0.0=[3.0.2.0.0], 4=[3.0.2.0.0, 4]]", rv.assignments().toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                static int method(String in) {
                    for(int i=0; i<in.length(); i++) {
                        if(in.charAt(i) == '0') {
                            return i;
                        }
                    }
                    return -1;
                }
            }
            """;

    @DisplayName("for-loop, local definition")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        MethodInfo method = X.findUniqueMethod("method", 1);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals(2, vdMethod.knownVariableNames().size());
        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in", vdMethod.knownVariableNamesToString());

        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("0.0.0-E", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        Statement s0 = method.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo iVi = vd0.variableInfo("i");
        assertEquals("i", iVi.variable().simpleName());
        assertFalse(vd0.variableInfoContainerOrNull("i").hasMerge());
        assertEquals("0-E", iVi.readId()); // is not a merge, so we cannot see the read in 'return i'
        Assignments iA = iVi.assignments();
        assertEquals("D:0-E, A:[0-E=[0-E]]", iA.toString());

        Statement s000 = s0.block().statements().get(0);
        VariableData vd000 = s000.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertTrue(vd000.variableInfoContainerOrNull("i").hasMerge());
        VariableInfo iVi000 = vd000.variableInfo("i");
        assertEquals("0.0.0.0.0", iVi000.readId());

        VariableInfo rvVi = vdMethod.variableInfo(method.fullyQualifiedName());
        assertEquals("return method", rvVi.variable().simpleName());
        Assignments rvA = rvVi.assignments();
        assertEquals("D:-, A:[0.0.0.0.0=[0.0.0.0.0], 1=[0.0.0.0.0, 1]]", rvA.toString());
    }


    @Language("java")
    private static final String INPUT2b = """
            package a.b;
            class X {
                static int method(String in) {
                    for(int i=0; i<in.length(); i++) {
                        System.out.println(i);
                        for(int j=i; j<in.length(); j++) {
                            if(in.charAt(j) == '?') return i+j;
                        }
                    }
                    return -1;
                }
            }
            """;

    @DisplayName("nested for-loops, local definition")
    @Test
    public void test2b() {
        TypeInfo X = javaInspector.parse(INPUT2b);
        MethodInfo method = X.findUniqueMethod("method", 1);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals(3, vdMethod.knownVariableNames().size());

        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in, java.lang.System.out",
                vdMethod.knownVariableNamesToString());
        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("0.0.1.0.0-E", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        assertFalse(vdMethod.isKnown("i"));

        Statement s0 = method.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo iVi = vd0.variableInfo("i");
        assertEquals("i", iVi.variable().simpleName());
        Assignments iA = iVi.assignments();
        assertEquals("D:0-E, A:[0-E=[0-E]]", iA.toString());

        Statement s001 = s0.block().statements().get(1);
        VariableData vd001 = s001.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in, i, j, java.lang.System.out", vd001.knownVariableNamesToString());
        VariableInfo jVi = vd001.variableInfo("j");
        assertEquals("j", jVi.variable().simpleName());
        Assignments jA = jVi.assignments();
        assertEquals("D:0.0.1-E, A:[0.0.1-E=[0.0.1-E]]", jA.toString());
    }

    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            class X {
                int method(String in) {
                    int i;
                    int j;
                    for(i=0; i<in.length(); i++) {
                        System.out.println(i);
                        for(j=i; j<in.length(); j++) {
                            if(in.charAt(j) == '!') return i+j;
                        }
                    }
                    j=in.length();
                    for(i=0; i<j; i++) {
                        System.out.println(i+" "+j);
                    }
                    return -1;
                }
            }
            """;

    @DisplayName("re-use of variables")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        MethodInfo method = X.findUniqueMethod("method", 1);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals(5, vdMethod.knownVariableNames().size());

        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in, i, j, java.lang.System.out",
                vdMethod.knownVariableNamesToString());
        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("3", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("i", iVi.variable().simpleName());
        Assignments iA = iVi.assignments();
        assertEquals("D:0, A:[2-E=[2-E], 4-E=[2-E, 4-E]]", iA.toString());

        VariableInfo jVi = vdMethod.variableInfo("j");
        assertEquals("j", jVi.variable().simpleName());
        Assignments jA = jVi.assignments();
        assertEquals("D:1, A:[2.0.1-E=[2.0.1-E], 3=[2.0.1-E, 3]]", jA.toString());
    }
}
