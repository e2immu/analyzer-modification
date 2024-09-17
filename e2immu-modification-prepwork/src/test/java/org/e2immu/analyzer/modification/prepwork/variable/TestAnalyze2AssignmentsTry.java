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

public class TestAnalyze2AssignmentsTry extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
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
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
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
        assertEquals("D:0, A:[0=[0]]", cA.toString());

    }


}
