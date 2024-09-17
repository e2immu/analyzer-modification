package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.impl.Assignments;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.statement.TryStatement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestAssignmentsTry extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static char method(String in, int i) {
                    char c;
                    try {
                        c = in.charAt(i);
                    } catch (IndexOutOfBoundsException e) {
                        c = '2';
                    }
                    System.out.println("c is "+c);
                    return c;
                }
            }
            """;

    @DisplayName("basics of try-catch")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(String,int), a.b.X.method(String,int):0:in, a.b.X.method(String,int):1:i, c, java.lang.System.out",
                vdMethod.knownVariableNamesToString());

        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("1.0.0", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo cVi = vdMethod.variableInfo("c");
        assertEquals("3", cVi.readId());
        Assignments cA = cVi.assignments();
        assertEquals("D:0, A:[1:M=[1.0.0, 1.1.0]]", cA.toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                static char method(String in, int i) {
                    char c;
                    char d;
                    try {
                        c = in.charAt(i);
                    } catch (IndexOutOfBoundsException e) {
                        c = '2';
                    } finally {
                        d = '9';
                    }
                    System.out.println("d is "+d+", c is "+c);
                    return c;
                }
            }
            """;

    @DisplayName("basics of try-catch-finally")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(String,int), a.b.X.method(String,int):0:in, a.b.X.method(String,int):1:i, c, d, java.lang.System.out",
                vdMethod.knownVariableNamesToString());

        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("2.0.0", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo cVi = vdMethod.variableInfo("c");
        assertEquals("4", cVi.readId());
        Assignments cA = cVi.assignments();
        assertEquals("D:0, A:[2:M=[2.0.0, 2.1.0]]", cA.toString());

        VariableInfo dVi = vdMethod.variableInfo("d");
        assertEquals("3", dVi.readId());
        Assignments dA = dVi.assignments();
        assertEquals("D:1, A:[2:M=[2.2.0]]", dA.toString());
    }



    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.io.*;
            public class X {
                public void method(File file, String in) {
                    try(FileWriter fw = new FileWriter(file); Writer w = new BufferedWriter(fw)) {
                        w.append("input: ").append(in);
                    } catch (IOException ioe) {
                        System.out.println("Caught io exception!");
                        throw new RuntimeException(ioe.getMessage());
                    } finally {
                        System.out.println("end of writing");
                    }
                }
            }
            """;

    @DisplayName("try-catch-finally, exit in catch")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        MethodInfo method = X.findUniqueMethod("method", 2);
        TryStatement ts = (TryStatement)method.methodBody().statements().get(0);
        TryStatement.CatchClause cc = ts.catchClauses().get(0);
        Statement cc0 = cc.block().statements().get(0);
        assertEquals("0.2.0", cc0.source().index());

        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);

        VariableInfo rvVi = vdMethod.variableInfo(method.fullyQualifiedName());
        assertEquals("D:-, A:[0.2.1=[0.2.1]]", rvVi.assignments().toString());
        assertFalse(rvVi.hasBeenDefined("1"));
    }

}
