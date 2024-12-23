package org.e2immu.analyzer.modification.linkedvariables.modification;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_FI_COMPONENTS_PARAMETER;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.VariableBooleanMapImpl.EMPTY;
import static org.junit.jupiter.api.Assertions.*;

public class TestModificationFunctional extends CommonTest {

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
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0S = vd0.variableInfo(s);
            assertSame(FALSE, vi0S.analysis().getOrDefault(VariableInfoImpl.MODIFIED_VARIABLE, FALSE));
        }
        {
            Statement s1 = run.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
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
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0This = vd0.variableInfo(runtime.newThis(X.asParameterizedType()));
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

    /*
    what happens?

    In run:
    instead of making first 'r.function', then 'r' modified,
    we'll add a MODIFIED_FI_COMPONENTS_VARIABLE map to r, marking function as "propagate".
    this travels to the parameter r, as MFI_COMPONENTS_PARAMETER.

    In go: the "propagate" is executed from parse to this, in the run(in, r) call.
    the static values act as an intermediary.
     */
    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            import java.util.function.Function;
            class X {
                record R(Function<String, Integer> function) {}
                int j;
            
                int go(String in) {
                    R r = new R(this::parse);
                    return run(in, r);
                }
                int run(String s, R r) {
                    System.out.println("Applying function on "+s);
                    return r.function.apply(s);
                }
                int parse(String t) {
                    j = Integer.parseInt(t);
                    return j;
                }
            }
            """;

    @DisplayName("propagate modification via functional interface component")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo R = X.findSubType("R");

        MethodInfo parse = X.findUniqueMethod("parse", 1);
        assertSame(TRUE, parse.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));

        MethodInfo run = X.findUniqueMethod("run", 2);
        ParameterInfo runS = run.parameters().get(0);
        ParameterInfo runR = run.parameters().get(1);
        {
            Statement s1 = run.methodBody().lastStatement();
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1R = vd1.variableInfo(runR);
            assertEquals("r.function=true", vi1R.analysis()
                    .getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE, EMPTY).toString());
        }
        {
            assertSame(FALSE, runS.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
            assertEquals("this.function=true", runR.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.class).toString());
            assertSame(FALSE, runR.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
            assertSame(FALSE, run.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        }
        MethodInfo go = X.findUniqueMethod("go", 1);
        assertSame(TRUE, go.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        ParameterInfo goIn = go.parameters().get(0);
        assertSame(FALSE, goIn.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
    }

    @Language("java")
    private static final String INPUT2b = """
            package a.b;
            import java.util.Set;
            import java.util.function.Function;
            class X {
                record R(Function<String, Integer> function) {}
                record S(R r) {}
                int j;
            
                int go(String in) {
                    R r = new R(this::parse);
                    S s = new S(r);
                    return run(in, s);
                }
                int run(String string, S s) {
                    System.out.println("Applying function on "+string);
                    return s.r().function().apply(string);
                }
                int parse(String t) {
                    j = Integer.parseInt(t);
                    return j;
                }
            }
            """;

    @DisplayName("propagate modification via functional interface component, accessor")
    @Test
    public void test2b() {
        TypeInfo X = javaInspector.parse(INPUT2b);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo R = X.findSubType("R");

        MethodInfo parse = X.findUniqueMethod("parse", 1);
        assertSame(TRUE, parse.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));

        MethodInfo run = X.findUniqueMethod("run", 2);
        ParameterInfo runS = run.parameters().get(1);
        {
            Statement s1 = run.methodBody().lastStatement();
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1S = vd1.variableInfo(runS);
            assertEquals("s.r.function=true", vi1S.analysis()
                    .getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE, EMPTY).toString());
        }

        assertSame(FALSE, runS.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
        assertEquals("this.r.function=true", runS.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER,
                ValueImpl.VariableBooleanMapImpl.class).toString());
        assertSame(FALSE, run.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));

        MethodInfo go = X.findUniqueMethod("go", 1);
        {
            Statement s0 = go.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0R = vd0.variableInfo("r");
            assertEquals("Type a.b.X.R E=new R(this::parse) this.function=this::parse",
                    vi0R.staticValues().toString());
        }
        {
            Statement s1 = go.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);

            VariableInfo vi1R = vd1.variableInfo("r");
            assertEquals("Type a.b.X.R E=new R(this::parse) this.function=this::parse",
                    vi1R.staticValues().toString());
            VariableInfo vi1S = vd1.variableInfo("s");
            assertEquals("Type a.b.X.S E=new S(r) this.r=r", vi1S.staticValues().toString());
        }

        assertSame(TRUE, go.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        ParameterInfo goIn = go.parameters().get(0);
        assertSame(FALSE, goIn.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            import java.util.Set;
            import java.util.function.Function;
            class X {
                interface R {
                    @GetSet Function<String, Integer> function();
                }
                record RImpl(Function<String, Integer> function) implements R {}
    
                interface S {
                    @GetSet R r();
                }
                record SImpl(R r) implements S {}
    
                int j;
            
                int go(String in) {
                    R r = new RImpl(this::parse);
                    S s = new SImpl(r);
                    return run(in, s);
                }
                int run(String string, S s) {
                    System.out.println("Applying function on "+string);
                    return s.r().function().apply(string);
                }
                int parse(String t) {
                    j = Integer.parseInt(t);
                    return j;
                }
            }
            """;

    @DisplayName("propagate modification via functional interface component, abstract")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo R = X.findSubType("R");
        FieldInfo functionInR = R.getFieldByName("function", true);
        assertTrue(functionInR.isSynthetic());

        TypeInfo SImpl = X.findSubType("SImpl");
        TypeInfo S = X.findSubType("S");
        assertTrue(SImpl.interfacesImplemented().stream().map(ParameterizedType::typeInfo).anyMatch(ti -> ti == S));
        MethodInfo rInSImpl = SImpl.findUniqueMethod("r", 0);
        MethodInfo rInS = S.findUniqueMethod("r", 0);
        assertTrue(rInSImpl.overrides().contains(rInS));

        MethodInfo parse = X.findUniqueMethod("parse", 1);
        assertSame(TRUE, parse.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));

        MethodInfo go = X.findUniqueMethod("go", 1);
        {
            Statement s0 = go.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0R = vd0.variableInfo("r");
            assertEquals("Type a.b.X.RImpl E=new RImpl(this::parse) this.function=this::parse",
                    vi0R.staticValues().toString());
        }
        {
            Statement s1 = go.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);

            VariableInfo vi1R = vd1.variableInfo("r");
            assertEquals("Type a.b.X.RImpl E=new RImpl(this::parse) this.function=this::parse",
                    vi1R.staticValues().toString());
            VariableInfo vi1S = vd1.variableInfo("s");
            assertEquals("Type a.b.X.SImpl E=new SImpl(r) this.r=r", vi1S.staticValues().toString());
        }

        MethodInfo run = X.findUniqueMethod("run", 2);
        ParameterInfo runS = run.parameters().get(1);
        {
            Statement s1 = run.methodBody().lastStatement();
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1S = vd1.variableInfo(runS);
            assertEquals("s.r.function=true", vi1S.analysis()
                    .getOrDefault(MODIFIED_FI_COMPONENTS_VARIABLE, EMPTY).toString());
        }

        assertSame(FALSE, runS.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
        assertEquals("this.r.function=true", runS.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER,
                ValueImpl.VariableBooleanMapImpl.class).toString());
        assertSame(FALSE, run.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));



        assertSame(TRUE, go.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, FALSE));
        ParameterInfo goIn = go.parameters().get(0);
        assertSame(FALSE, goIn.analysis().getOrDefault(PropertyImpl.MODIFIED_PARAMETER, FALSE));
    }
}