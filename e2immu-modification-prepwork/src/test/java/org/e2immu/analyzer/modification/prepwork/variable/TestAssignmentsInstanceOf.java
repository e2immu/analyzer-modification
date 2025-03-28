package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestAssignmentsInstanceOf extends CommonTest {

    @Language("java")
    public static final String INPUT1 = """
            import java.io.IOException;
            public class X {
                public static void method(Exception exception) {
                    if (exception instanceof RuntimeException e) {
                        throw e;
                    } else {
                        String e = exception.getMessage();
                        System.out.println("e = " + e.toLowerCase());
                    }
                }
            }
            """;

    @DisplayName("positive instanceof")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        IfElseStatement ifElse = (IfElseStatement) method.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(ifElse);
        assertEquals("X.method(Exception), X.method(Exception):0:exception, e, java.lang.System.out",
                vd0.knownVariableNamesToString());
        VariableInfo vi0 = vd0.variableInfo("e");
        assertEquals("D:0-E, A:[0-E]", vi0.assignments().toString());
        assertEquals("Type RuntimeException", vi0.variable().parameterizedType().toString());

        VariableData vd000 = VariableDataImpl.of(ifElse.block().statements().get(0));
        assertEquals("X.method(Exception), X.method(Exception):0:exception, e",
                vd000.knownVariableNamesToString());
        VariableInfo vi000 = vd0.variableInfo("e");
        assertEquals("D:0-E, A:[0-E]", vi000.assignments().toString());
        assertEquals("Type RuntimeException", vi000.variable().parameterizedType().toString());

        VariableData vd010 = VariableDataImpl.of(ifElse.elseBlock().statements().get(0));
        assertEquals("X.method(Exception):0:exception, e", vd010.knownVariableNamesToString());
        VariableInfo vi010 = vd010.variableInfo("e");
        assertEquals("D:0.1.0, A:[0.1.0]", vi010.assignments().toString());
        assertEquals("Type String", vi010.variable().parameterizedType().toString());

        VariableData vd011 = VariableDataImpl.of(ifElse.elseBlock().statements().get(1));
        assertEquals("X.method(Exception):0:exception, e, java.lang.System.out", vd011.knownVariableNamesToString());
        VariableInfo vi011 = vd011.variableInfo("e");
        assertEquals("D:0.1.0, A:[0.1.0]", vi011.assignments().toString());
        assertEquals("Type String", vi011.variable().parameterizedType().toString());
    }

    @Language("java")
    public static final String INPUT2 = """
            import java.io.IOException;
            public class X {
                public static void method(Exception exception) {
                    if (!(exception instanceof RuntimeException e)) {
                        String e = exception.getMessage();
                        System.out.println("e = " + e.toLowerCase());
                    } else {
                        throw e;
                    }
                }
            }
            """;

    @DisplayName("negative instanceof")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        IfElseStatement ifElse = (IfElseStatement) method.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(ifElse);
        assertEquals("X.method(Exception), X.method(Exception):0:exception, e, java.lang.System.out",
                vd0.knownVariableNamesToString());
        VariableInfo vi0 = vd0.variableInfo("e");
        assertEquals("D:0-E, A:[0-E]", vi0.assignments().toString());
        assertEquals("Type RuntimeException", vi0.variable().parameterizedType().toString());

        VariableData vd010 = VariableDataImpl.of(ifElse.elseBlock().statements().get(0));
        assertEquals("X.method(Exception), X.method(Exception):0:exception, e",
                vd010.knownVariableNamesToString());
        VariableInfo vi010 = vd0.variableInfo("e");
        assertEquals("D:0-E, A:[0-E]", vi010.assignments().toString());
        assertEquals("Type RuntimeException", vi010.variable().parameterizedType().toString());

        VariableData vd001 = VariableDataImpl.of(ifElse.block().statements().get(1));
        assertEquals("X.method(Exception):0:exception, e, java.lang.System.out", vd001.knownVariableNamesToString());
        VariableInfo vi001 = vd001.variableInfo("e");
        assertEquals("D:0.0.0, A:[0.0.0]", vi001.assignments().toString());
        assertEquals("Type String", vi001.variable().parameterizedType().toString());
    }
}
