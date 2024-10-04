package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestLinkMerge extends CommonTest {

    public TestLinkMerge() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class X {
                static <T> boolean setAdd(Set<T> set, T s) {
                    if(s != null) {
                        return set.add(s);
                    }
                    return false;
                }
            }
            """;

    @DisplayName("simple merge")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 2);
        VariableData vd = setAdd.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd);
        ParameterInfo set = setAdd.parameters().get(0);
        Statement s0 = setAdd.methodBody().statements().get(0);
        Statement s000 = s0.block().statements().get(0);

        VariableData vd000 = s000.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo viSet000 = vd000.variableInfo(set);
        assertTrue(viSet000.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
        assertEquals("0-4-*:s", viSet000.linkedVariables().toString());

        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo viSet0 = vd0.variableInfo(set);
        assertEquals("0-4-*:s", viSet0.linkedVariables().toString());
        assertTrue(viSet0.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            class X {
                static <T> boolean setAdd(Set<T> set, T t) {
                    Set<T> i;
                    if(t == null) {
                        i = null;
                    } else {
                        i = set;
                    }
                    if(i != null) {
                        i.add(t);
                    }
                    return false;
                }
            }
            """;

    @DisplayName("simple merge, one intermediate local variable")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 2);
        VariableData vd = setAdd.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd);
        ParameterInfo set = setAdd.parameters().get(0);
        {
            IfElseStatement s1 = (IfElseStatement) setAdd.methodBody().statements().get(1);
            {
                Statement s110 = s1.elseBlock().statements().get(0);
                VariableData vd110 = s110.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
                VariableInfo viI100 = vd110.variableInfo("i");
                assertEquals("-1-:set", viI100.linkedVariables().toString());
                VariableInfo viSet100 = vd110.variableInfo(set);
                assertEquals("-1-:i", viSet100.linkedVariables().toString());
            }
            {
                VariableData vd1 = s1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
                VariableInfo viI1 = vd1.variableInfo("i");
                assertEquals("-1-:set", viI1.linkedVariables().toString());
                VariableInfo viSet1 = vd1.variableInfo(set);
                assertEquals("-1-:i", viSet1.linkedVariables().toString());
            }
        }
        {
            IfElseStatement s2 = (IfElseStatement) setAdd.methodBody().statements().get(2);
            {
                Statement s200 = s2.block().statements().get(0);
                VariableData vd200 = s200.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
                VariableInfo viI200 = vd200.variableInfo("i");
                assertEquals("-1-:set,0-4-*:t", viI200.linkedVariables().toString());
                assertTrue(viI200.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE,
                        ValueImpl.BoolImpl.FALSE).isTrue());

                VariableInfo viSet200 = vd200.variableInfo(set);
                assertEquals("-1-:i,0-4-*:t", viSet200.linkedVariables().toString());

                // test the propagation of the @Modified property via clustering
                assertTrue(viSet200.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE,
                        ValueImpl.BoolImpl.FALSE).isTrue());
            }
            {
                VariableData vd2 = s2.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
                VariableInfo viI2 = vd2.variableInfo("i");
                assertEquals("-1-:set,0-4-*:t", viI2.linkedVariables().toString());
                assertTrue(viI2.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE,
                        ValueImpl.BoolImpl.FALSE).isTrue());

                VariableInfo viSet2 = vd2.variableInfo(set);
                assertEquals("-1-:i,0-4-*:t", viSet2.linkedVariables().toString());

                // test the propagation of the @Modified property via merge
                assertTrue(viSet2.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE,
                        ValueImpl.BoolImpl.FALSE).isTrue());
            }
        }
    }
}
