package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.impl.Assignments;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestAssignmentsSwitch extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static int method(char c, boolean b) {
                    int i;
                    switch (c) {
                        case 'a':
                            if (b) i = 3;
                            else i = 1;
                            break;
                        case 'b':
                            i = 2;
                            break;
                        default:
                            i = 4;
                    }
                    return i;
                }
            }
            """;

    @DisplayName("clean old-style switch")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(char,boolean), a.b.X.method(char,boolean):0:c, a.b.X.method(char,boolean):1:b, i",
                vdMethod.knownVariableNamesToString());

        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("c", inVi.variable().simpleName());
        assertEquals("1-E", inVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo bVi = vdMethod.variableInfo(method.parameters().get(1).fullyQualifiedName());
        assertEquals("b", bVi.variable().simpleName());
        assertEquals("1.0.0-E", bVi.readId()); // last time read in method
        assertEquals("D:-, A:[]", bVi.assignments().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.readId());
        Assignments cA = iVi.assignments();
        assertEquals("D:0, A:[1:M=[1.0.0.0.0, 1.0.0.1.0, 1.0.2, 1.0.4]]", cA.toString());
    }

}
