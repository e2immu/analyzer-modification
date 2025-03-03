package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.getset.ApplyGetSetTranslation;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestStaticValuesHelper extends CommonTest {


    @Language("java")
    private static final String INPUT_1 = """
            package a.b;
            class X {
                interface I { }
                static class R {
                    I i;
                }
                record Wrapper(R r, R[] rs) {}
                void method(Wrapper w, I j) {
                   w.r().i = j;
                   w.rs[3].i = j;
                }
            }
            """;

    @Test
    public void test1a() {
        TypeInfo X = javaInspector.parse(INPUT_1);
        prepWork(X);

        MethodInfo method = X.findUniqueMethod("method", 2);

        Statement s0 = method.methodBody().statements().get(0);
        Assignment a = (Assignment) s0.expression().translate(new ApplyGetSetTranslation(runtime));
        assertEquals("w.r.i", a.target().toString());
        StaticValues sv = new StaticValuesImpl(a.value().parameterizedType(), a.value(), false, Map.of());
        assertEquals("Type a.b.X.I E=j", sv.toString());
        FieldReference fr = (FieldReference) a.variableTarget();
        StaticValuesHelper svh = new StaticValuesHelper(runtime);
        Map<Variable, List<StaticValues>> append = new HashMap<>();
        List<StaticValues> list = new ArrayList<>();
        list.add(sv);
        append.put(fr, list);
        svh.recursivelyAdd1(append, fr, a.source(), list, VariableDataImpl.of(s0), "0");
        assertEquals("""
                a.b.X.method(a.b.X.Wrapper,a.b.X.I):0:w=[this.r.i=j], \
                w.r.i=[Type a.b.X.I E=j], \
                w.r=[this.i=j]\
                """, append.entrySet().stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
    }

    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT_1);
        prepWork(X);

        MethodInfo method = X.findUniqueMethod("method", 2);

        Statement s1 = method.methodBody().statements().get(1);
        Assignment a = (Assignment) s1.expression().translate(new ApplyGetSetTranslation(runtime));
        assertEquals("w.rs[3].i", a.target().toString());
        StaticValues sv = new StaticValuesImpl(a.value().parameterizedType(), a.value(), false, Map.of());
        assertEquals("Type a.b.X.I E=j", sv.toString());
        FieldReference fr = (FieldReference) a.variableTarget();
        StaticValuesHelper svh = new StaticValuesHelper(runtime);
        Map<Variable, List<StaticValues>> append = new HashMap<>();
        List<StaticValues> list = new ArrayList<>();
        list.add(sv);
        append.put(fr, list);
        svh.recursivelyAdd1(append, fr, a.source(), list, VariableDataImpl.of(s1), "0");
        assertEquals("w.rs[3].i=[Type a.b.X.I E=j], w.rs[3]=[this.i=j]",
                append.entrySet().stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
    }


    @Language("java")
    private static final String INPUT_2 = """
            package a.b;
            import java.util.Set;
            class X {
                record R(int i, int j) {}
                record S(R r, int k) {}
            
                int method(S s) {
                    s.r().i = 3;
                    s.k = s.r.j;
                    return s.r().i+s.r.j()+s.k;
                }
            }
            """;

    @Test
    public void test2a() {
        TypeInfo X = javaInspector.parse(INPUT_2);
        prepWork(X);

        MethodInfo method = X.findUniqueMethod("method", 1);

        Statement s0 = method.methodBody().statements().get(0);
        Assignment a = (Assignment) s0.expression().translate(new ApplyGetSetTranslation(runtime));
        assertEquals("s.r.i", a.target().toString());
        StaticValues sv = new StaticValuesImpl(a.value().parameterizedType(), a.value(), false, Map.of());
        assertEquals("Type int E=3", sv.toString());
        FieldReference fr = (FieldReference) a.variableTarget();
        StaticValuesHelper svh = new StaticValuesHelper(runtime);
        Map<Variable, List<StaticValues>> append = new HashMap<>();
        List<StaticValues> list = new ArrayList<>();
        list.add(sv);
        append.put(fr, list);
        svh.recursivelyAdd1(append, fr, a.source(), list, VariableDataImpl.of(s0), "0");
        assertEquals("""
                a.b.X.method(a.b.X.S):0:s=[this.r.i=3], s.r.i=[Type int E=3], s.r=[this.i=3]\
                """, append.entrySet().stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
    }

    @Test
    public void test2b() {
        TypeInfo X = javaInspector.parse(INPUT_2);
        prepWork(X);

        MethodInfo method = X.findUniqueMethod("method", 1);

        Statement s1 = method.methodBody().statements().get(1);
        Assignment a = (Assignment) s1.expression().translate(new ApplyGetSetTranslation(runtime));
        assertEquals("s.k", a.target().toString());
        StaticValues sv = new StaticValuesImpl(a.value().parameterizedType(), a.value(), false, Map.of());
        assertEquals("Type int E=s.r.j", sv.toString());
        FieldReference fr = (FieldReference) a.variableTarget();
        StaticValuesHelper svh = new StaticValuesHelper(runtime);
        Map<Variable, List<StaticValues>> append = new HashMap<>();
        List<StaticValues> list = new ArrayList<>();
        list.add(sv);
        append.put(fr, list);
        svh.recursivelyAdd1(append, fr, a.source(), list, VariableDataImpl.of(s1), "1");
        assertEquals("""
                a.b.X.method(a.b.X.S):0:s=[this.k=s.r.j], s.k=[Type int E=s.r.j]\
                """, append.entrySet().stream().map(Object::toString).sorted().collect(Collectors.joining(", ")));
    }

}
