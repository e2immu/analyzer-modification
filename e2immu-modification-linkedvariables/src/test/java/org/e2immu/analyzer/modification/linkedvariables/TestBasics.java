package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestBasics extends CommonTest {

    public TestBasics() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;class X {
                static boolean setAdd(Set<String> set, String s) {
                    return set.add(s);
                }
            }
            """;

    @DisplayName("modification already done by prep-work, check no links")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        prepWork(X);
        MethodInfo setAdd = X.findUniqueMethod("setAdd", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(setAdd);
        VariableData vd = setAdd.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd);
        ParameterInfo set = setAdd.parameters().get(0);
        Statement s0 = setAdd.methodBody().statements().get(0);
        MethodCall mc = (MethodCall) s0.expression();
        assertTrue(mc.methodInfo().analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue());

        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo viSet0 = vd0.variableInfo(set);
        assertTrue(viSet0.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
        assertNull(viSet0.linkedVariables());

        // this should have reached the method
        assertTrue(set.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.List;
            class X {
                static <T> T get(List<T> list, int i) {
                    return list.get(i);
                }
            }
            """;

    @DisplayName("return list.get(index)")
    @Test
    public void test2() {
        TypeInfo list = javaInspector.compiledTypesManager().get(List.class);
        MethodInfo get = list.findUniqueMethod("get", 1);
        assertEquals("java.util.List.get(int)", get.fullyQualifiedName());
        HiddenContentSelector hcsGet = get.analysis().getOrDefault(HiddenContentSelector.HCS_METHOD, HiddenContentSelector.NONE);
        assertEquals("definitely not X", hcsGet.toString());

        TypeInfo X = javaInspector.parse(INPUT2);
        prepWork(X);
        MethodInfo setAdd = X.findUniqueMethod("get", 2);
        Analyzer analyzer = new Analyzer(runtime);
        analyzer.doMethod(setAdd);
        VariableData vd = setAdd.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vd);
        ParameterInfo set = setAdd.parameters().get(0);
        Statement s0 = setAdd.methodBody().statements().get(0);
        MethodCall mc = (MethodCall) s0.expression();
        assertTrue(mc.methodInfo().analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue());

        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);

        VariableInfo viSet0 = vd0.variableInfo(set);
        assertTrue(viSet0.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
        assertEquals("s:0-4-*", viSet0.linkedVariables().toString());

        VariableInfo viS0 = vd0.variableInfo(set);
        assertTrue(viS0.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue());
        assertEquals("set:*-4-0", viSet0.linkedVariables().toString());

        // this should have reached the method
        assertTrue(set.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue());
    }
}
