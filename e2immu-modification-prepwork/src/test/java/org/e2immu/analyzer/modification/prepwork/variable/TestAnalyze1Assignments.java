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

public class TestAnalyze1Assignments extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                int method(String in) {
                    int i;
                    int j;
                    for(i=0; i<in.toCharArray(); i++) {
                        System.out.println(i);
                        for(j=i; j<in.length(); j++) {
                            if(j % 3 == 0) return i+j;
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
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        MethodInfo method = X.findUniqueMethod("method", 1);
        Analyze analyze = new Analyze(runtime);
        analyze.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals(6, vdMethod.knownVariableNames().size());

        assertEquals("a.b.X.method(String), a.b.X.method(String):0:in, a.b.X.this, i, j, java.lang.System.out",
                vdMethod.knownVariableNamesToString());
        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("in", inVi.variable().simpleName());
        assertEquals("3", inVi.readId()); // last time read in method
        assertEquals("[]", inVi.assignments().toString());

        VariableInfo thisVi = vdMethod.variableInfo(X.fullyQualifiedName()+".this");
        assertEquals("this", thisVi.variable().simpleName());
        assertEquals("-", thisVi.readId());
        assertEquals("[]", thisVi.assignments().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("i", iVi.variable().simpleName());
        Assignments iA = iVi.assignments();
        assertEquals("[I[index=0, actualAssignmentIndices=[0]], I[index=2:M, actualAssignmentIndices=[0, 2]], I[index=4:M, actualAssignmentIndices=[0, 2, 4]]]", iA.assignments().toString());

        VariableInfo jVi = vdMethod.variableInfo("j");
        assertEquals("j", jVi.variable().simpleName());
        Assignments jA = jVi.assignments();
        assertEquals("[I[index=0, actualAssignmentIndices=[0]], I[index=2:M, actualAssignmentIndices=[0, 2]], I[index=3:M, actualAssignmentIndices=[0, 2, 3]]]", jA.assignments().toString());
    }
}
