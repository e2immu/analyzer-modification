package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.Analyzer;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Language("java")
    private static final String INPUT1b = """
            package a.b;
            class X {
                static int method(char c, boolean b) {
                    int i;
                    switch (c) {
                        case 'a':
                            if (b) {
                                i = 3;
                            } else {
                                i = 1;
                            }
                            break;
                        case 'b':
                            i = 2;
                            break;
                        default:
                            i = 4;
                            break;
                    }
                    return i;
                }
            }
            """;

    @DisplayName("clean old-style switch")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        test1Code(X);
    }

    @DisplayName("clean old-style switch, unnecessary break after default")
    @Test
    public void test1b() {
        TypeInfo X = javaInspector.parse(INPUT1b);
        test1Code(X);
    }

    private void test1Code(TypeInfo X) {
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(char,boolean), a.b.X.method(char,boolean):0:c, a.b.X.method(char,boolean):1:b, i",
                vdMethod.knownVariableNamesToString());

        VariableInfo inVi = vdMethod.variableInfo(method.parameters().get(0).fullyQualifiedName());
        assertEquals("c", inVi.variable().simpleName());
        assertEquals("1-E", inVi.reads().toString()); 
        assertEquals("D:-, A:[]", inVi.assignments().toString());

        VariableInfo bVi = vdMethod.variableInfo(method.parameters().get(1).fullyQualifiedName());
        assertEquals("b", bVi.variable().simpleName());
        assertEquals("1.0.0-E", bVi.reads().toString()); 
        assertEquals("D:-, A:[]", bVi.assignments().toString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString());
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M, 1.0.2, 1.0.4, 1=M]", iVi.assignments().toString());

        Statement ifElse = method.methodBody().statements().get(1).block().statements().get(0);
        VariableData vdIfElse = ifElse.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo iViIfElse = vdIfElse.variableInfo("i");
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M]", iViIfElse.assignments().toString());
    }


    @Language("java")
    private static final String INPUT1c = """
            package a.b;
            class X {
                static int method(char c, boolean b) {
                    int i;
                    switch (c) {
                        case 'a':
                            if (b) {
                                i = 3;
                                break;
                            } else {
                                i = 1;
                                break;
                            }
                        case 'b':
                            break;
                        default:
                            i = 4;
                            break;
                    }
                    return i;
                }
            }
            """;


    @DisplayName("clean old-style switch, break hidden in if-statement")
    @Test
    public void test1c() {
        TypeInfo X = javaInspector.parse(INPUT1c);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(char,boolean), a.b.X.method(char,boolean):0:c, a.b.X.method(char,boolean):1:b, i",
                vdMethod.knownVariableNamesToString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString()); 
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M, 1.0.2]", iVi.assignments().toString());
        assertFalse(iVi.hasBeenDefined("2"));
    }


    @Language("java")
    private static final String INPUT2 = """
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
                            return -1;
                        default:
                            i = 4;
                    }
                    return i;
                }
            }
            """;


    @DisplayName("clean old-style switch, break and return")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);
        assertEquals("a.b.X.method(char,boolean), a.b.X.method(char,boolean):0:c, a.b.X.method(char,boolean):1:b, i",
                vdMethod.knownVariableNamesToString());

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString()); 
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M, 1.0.3, 1=M]", iVi.assignments().toString());
        assertTrue(iVi.hasBeenDefined("2"));
    }

    @Language("java")
    private static final String INPUT3 = """
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
                            System.out.println("b!!");
                        default:
                            i = 4;
                    }
                    return i;
                }
            }
            """;


    @DisplayName("old-style switch with irrelevant fall-through")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString()); 
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M, 1.0.3, 1=M]", iVi.assignments().toString());
        assertTrue(iVi.hasBeenDefined("2"));
    }

    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            class X {
                static int method(char c, boolean b) {
                    int i;
                    switch (c) {
                        case 'a':
                            if (b) i = 3;
                            else i = 1;
                        case 'b':
                            System.out.println("b!!");
                            break;
                        default:
                            i = 4;
                    }
                    return i; // i has not been defined! illegal java
                }
            }
            """;

    @DisplayName("old-style switch with real fall-through and overwrite")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        MethodInfo method = X.findUniqueMethod("method", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);

        VariableInfo iVi = vdMethod.variableInfo("i");
        assertEquals("2", iVi.reads().toString()); 
        assertEquals("D:0, A:[1.0.0.0.0, 1.0.0.1.0, 1.0.0=M, 1.0.3]", iVi.assignments().toString());
        assertFalse(iVi.hasBeenDefined("2"));
    }


    @Language("java")
    public static final String INPUT5 = """
            package a.b;
            import java.io.IOException;
            import java.io.Reader;
            class X {
                 private String parse(Reader in, int state) throws IOException {
                     in.mark(1);
                     int c = in.read();
                     StringBuilder str = new StringBuilder();
                     switch(c) {
                        case -1:
                            switch(state) {
                                case 0:
                                    return null;
                                default:
                                    return str.toString();
                            }
                        case '\\n':
                            return str.toString();
                        default:
                            switch(state) {
                                case 2:
                                case 3:
                                    in.reset();
                                    return str.toString();
                                default:
                                    state = 1;
                                    str.append((char) c);
                            }
                    }
                    return "?";
                }
            }
            """;


    @DisplayName("nested old-style switches, almost return everywhere")
    @Test
    public void test5() {
        TypeInfo X = javaInspector.parse(INPUT5);
        MethodInfo method = X.findUniqueMethod("parse", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(method);
        VariableData vdMethod = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdMethod);

        VariableInfo iVi = vdMethod.variableInfo(method.fullyQualifiedName());
        assertEquals("D:-, A:[3.0.0.0.0, 3.0.0.0.1, 3.0.0=M, 3.0.1, 3.0.2.0.1, 4]", iVi.assignments().toString());
        assertFalse(iVi.hasBeenDefined("3"));
        assertTrue(iVi.hasBeenDefined("4"));
        assertTrue(iVi.hasBeenDefined("5"));
        assertEquals("[3.0.0.0.0, 3.0.0.0.1, 3.0.0, 3.0.1, 3.0.2.0.1, 4]",
                iVi.assignments().allIndicesStripStage().toString());
    }
}
