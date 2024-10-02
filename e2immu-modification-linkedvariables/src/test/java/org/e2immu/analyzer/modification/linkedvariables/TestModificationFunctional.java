package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestModificationFunctional extends CommonTest {

    public TestModificationFunctional() {
        super(true);
    }

    /*
    the convention is that modification on a functional interface type indicates that the SAM (single abstract method)
    has been called.

    note: the methods are placed out of order, for AnalysisOrder to kick in.
     */
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            import java.util.function.Function;
            class X {
                int j;
                int goWithIndirection(String in) {
                    return indirection(in, this::parse);
                }
                int run(String s, Function<String, Integer> function) {
                    System.out.println("Applying function on "+s);
                    return function.apply(s);
                }
                int parse(String t) {
                    j = Integer.parseInt(t);
                    return j;
                }
                int go(String in) {
                    return run(in, this::parse);
                }
                int indirection(String s, Function<String, Integer> function) {
                    Function<String, Integer> f = function;
                    return run(s, f);
                }
            }
            """;

    @DisplayName("propagate modification via functional interface parameter")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo parse = X.findUniqueMethod("parse", 1);
        assertSame(TRUE, parse.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));

        MethodInfo run = X.findUniqueMethod("run", 2);
        ParameterInfo s = run.parameters().get(0);
        ParameterInfo function = run.parameters().get(1);
        {
            Statement s0 = run.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi0S = vd0.variableInfo(s);
            assertSame(FALSE, vi0S.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, FALSE));
        }
        {
            Statement s1 = run.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi1S = vd1.variableInfo(s);
            assertSame(FALSE, vi1S.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, FALSE));
            VariableInfo vi1Function = vd1.variableInfo(function);
            assertSame(TRUE, vi1Function.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, FALSE));
        }
        assertSame(FALSE, run.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        assertSame(TRUE, function.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
        assertSame(FALSE, s.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));

        // finally, we copy the modification status of 'parse' onto 'this'
        MethodInfo go = X.findUniqueMethod("go", 1);
        {
            Statement s0 = go.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi0This = vd0.variableInfo(runtime.newThis(X));
            assertSame(TRUE, vi0This.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, FALSE));
            // as a consequence, 'go' becomes modified
            assertSame(TRUE, go.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        }
        {
            MethodInfo indirection = X.findUniqueMethod("indirection", 2);
            ParameterInfo iP1 = indirection.parameters().get(1);
            assertSame(TRUE, iP1.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
        }
        {
            MethodInfo goIndirection = X.findUniqueMethod("goWithIndirection", 1);
            assertSame(TRUE, goIndirection.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        }
    }
}
