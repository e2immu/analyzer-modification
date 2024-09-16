package org.e2immu.analyzer.modification.prepwork.variable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.impl.ReturnVariableImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestVariableData extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class C {
                void method1(String s) {
                    assert s != null;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo typeInfo = javaInspector.parseReturnAll(INPUT).get(0);
        MethodInfo method1 = typeInfo.findUniqueMethod("method1", 1);
        Analyze analyze = new Analyze(javaInspector.runtime());
        analyze.doMethod(method1);
        VariableData vd = method1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;

        // there is no This present
        assertEquals("a.b.C.method1(String):0:s", vd.knownVariableNamesToString());
        List<VariableInfo> vis = vd.variableInfoStream().toList();
        assertEquals(1, vis.size());

        VariableData statementVd = method1.methodBody().statements().get(0).analysis()
                .getOrNull(VariableDataImpl.VARIABLE_DATA, VariableData.class);
        assertSame(vd, statementVd);

        ParameterInfo pi = method1.parameters().get(0);
        VariableInfo vi0 = vd.variableInfo(pi.fullyQualifiedName());
        assertSame("0", vi0.readId());
        assertTrue(vi0.assignments().hasNotYetBeenAssigned());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class C {
                void method1(String s) {
                    assert s != null;
                    System.out.println(s);
                }
            }
            """;

    @Test
    public void test2() {
        TypeInfo typeInfo = javaInspector.parseReturnAll(INPUT2).get(0);
        MethodInfo method1 = typeInfo.findUniqueMethod("method1", 1);
        Analyze analyze = new Analyze(javaInspector.runtime());
        analyze.doMethod(method1);

        VariableData vd = method1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);

        assertEquals("a.b.C.method1(String):0:s, java.lang.System.out",                vd.knownVariableNamesToString());

        List<VariableInfo> vis = vd.variableInfoStream().toList();
        assertEquals(2, vis.size());

        Statement s0 = method1.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableData.class);
        assertNotNull(vd0);
        Statement s1 = method1.methodBody().statements().get(1);
        VariableData vd1 = s1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableData.class);
        assertSame(vd, vd1);

        ParameterInfo pi = method1.parameters().get(0);
        VariableInfo vi0 = vd.variableInfo(pi.fullyQualifiedName());
        assertEquals("1", vi0.readId());
        assertTrue(vi0.assignments().hasNotYetBeenAssigned());
        VariableInfoContainer vic0 = vd.variableInfoContainerOrNull(pi.fullyQualifiedName());
        assertSame(vi0, vic0.best());
        assertSame(vi0, vic0.best(Stage.MERGE));
        assertSame(vi0, vic0.best(Stage.EVALUATION));
        VariableInfo vii = vic0.best(Stage.INITIAL);
        assertNotSame(vi0, vii);

        VariableInfoContainer vic00 = vd0.variableInfoContainerOrNull(pi.fullyQualifiedName());
        assertSame(vii, vic00.best());
        assertSame(vii, vic00.best(Stage.EVALUATION));
        assertNotSame(vii, vic00.getPreviousOrInitial());
        assertTrue(vic00.isInitial());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            class C {
                int j;
                void method1(String s) {
                    assert s != null;
                    if(j > 0) {
                        System.out.println(s);
                    }
                }
            }
            """;

    @Test
    public void test3() {
        TypeInfo typeInfo = javaInspector.parseReturnAll(INPUT3).get(0);
        MethodInfo method1 = typeInfo.findUniqueMethod("method1", 1);
        Analyze analyze = new Analyze(javaInspector.runtime());
        analyze.doMethod(method1);

        VariableData vd0 = method1.methodBody().statements().get(0).analysis().getOrNull(VariableDataImpl.VARIABLE_DATA,
                VariableData.class);

        VariableData vd = method1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;
        assertEquals("a.b.C.j, a.b.C.method1(String):0:s, a.b.C.this, java.lang.System.out", vd.knownVariableNamesToString());

        List<VariableInfo> vis = vd.variableInfoStream().toList();
        assertEquals(4, vis.size());

        VariableInfoContainer vicJ = vd.variableInfoContainerOrNull("a.b.C.j");
        assertNotNull(vicJ);
        assertEquals("1-E", vicJ.best().readId());
        assertFalse(vd0.isKnown(vicJ.variable().fullyQualifiedName()));

        VariableInfoContainer vicThis = vd.variableInfoContainerOrNull("a.b.C.this");
        assertNotNull(vicThis);
        VariableInfo this1E = vicThis.best(Stage.EVALUATION);
        assertEquals("1-E", this1E.readId());
        VariableInfo this1M = vicThis.best();
        assertNotSame(this1E, this1M);
        assertEquals("1-E", this1M.readId());
        // check that This is not yet present in statement 0
        assertFalse(vd0.isKnown(vicThis.variable().fullyQualifiedName()));

        VariableInfoContainer vicOut = vd.variableInfoContainerOrNull("java.lang.System.out");
        assertNotNull(vicOut);
        VariableInfo out1E = vicOut.best(Stage.EVALUATION);
        VariableInfo out1M = vicOut.best();
        assertNotSame(out1M, out1E);
        assertEquals("-", out1E.readId());
        assertEquals("1.0.0", out1M.readId());
        assertFalse(vd0.isKnown(vicOut.variable().fullyQualifiedName()));

        ReturnVariable rv = new ReturnVariableImpl(method1);
        assertFalse(vd.isKnown(rv.fullyQualifiedName()));
    }


    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            class C {
                int j;
                int method1(String s) {
                    assert s != null;
                    if(j > 0) {
                        System.out.println(s);
                    } else {
                        //noinspection ALL
                        int c = s.length();
                        j = c;
                    }
                    return j+1;
                }
            }
            """;

    @Test
    public void test4() {
        TypeInfo typeInfo = javaInspector.parseReturnAll(INPUT4).get(0);
        MethodInfo method1 = typeInfo.findUniqueMethod("method1", 1);
        Analyze analyze = new Analyze(javaInspector.runtime());
        analyze.doMethod(method1);

        VariableData vd = method1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;

        Statement s111 = method1.methodBody().statements().get(1)
                .otherBlocksStream().findFirst().orElseThrow().statements().get(1);
        assertEquals("1.1.1", s111.source().index());

        VariableInfoContainer vicJ = vd.variableInfoContainerOrNull("a.b.C.j");
        assertNotNull(vicJ);
        VariableInfo viJ = vicJ.best();
        assertEquals("2", viJ.readId());
        assertEquals("D:-, A:[1.1.1=[1.1.1]]", viJ.assignments().toString());

        ReturnVariable rv = new ReturnVariableImpl(method1);

        Statement s2 = method1.methodBody().statements().get(2);
        VariableData vd2 = s2.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rvVi2 = vd2.variableInfo(rv.fullyQualifiedName());

        assertTrue(vd.isKnown(rv.fullyQualifiedName()));
        VariableInfo viRv = vd.variableInfo(rv.fullyQualifiedName());
        assertEquals("-", viRv.readId());
        assertEquals("D:-, A:[2=[2]]", viRv.assignments().toString());

    }

}
