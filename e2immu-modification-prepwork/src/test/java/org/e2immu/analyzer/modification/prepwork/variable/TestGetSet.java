package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestGetSet extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                record R(int i) {}
                Object[] objects = new Object[10];
                List<String> list = new ArrayList<>();
                double d;
                R r;
                R rr;
            
                Object getO(int i) { return objects[i]; }
                Object getO2() { return objects[2]; } // not @GetSet YET
            
                String getS(int j) { return list.get(j); }
                String getS2() { return list.get(0); } // not @GetSet YET
            
                double d() { return d; }
                double dd() { return this.d; }
            
                int ri() { return r.i; }
                int rri() { return rr.i; }
                int ri2() { return r.i(); } // ri2 is not an accessor! (we must have a field)
            
                void setO(Object o, int i) { objects[i] = o; }
                X setO2(int i, Object o) { this.objects[i] = o; return this; }
                X setO3(Object o) { this.objects[0] = o; return this; } // not @GetSet yet
            
                void setS(Object o, int i) { list.set(i, o); }
                X setS2(int i, Object o) { this.list.set(i, o); return this; }
                X setS3(Object o) { this.list.set(0, o); return this; } // not @GetSet yet
            
                void setD(double d) { this.d = d; }
                void setRI(int i) { this.r.i = i; }
            }
            """;

    @DisplayName("getters and setters")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);

        FieldInfo objects = X.getFieldByName("objects", true);
        FieldInfo list = X.getFieldByName("list", true);
        FieldInfo d = X.getFieldByName("d", true);
        TypeInfo R = X.findSubType("R");
        FieldInfo ri = R.getFieldByName("i", true);

        MethodInfo getO = X.findUniqueMethod("getO", 1);
        assertSame(objects, getO.getSetField().field());
        MethodInfo getO2 = X.findUniqueMethod("getO2", 0);
        assertNull(getO2.getSetField().field());

        MethodInfo setO = X.findUniqueMethod("setO", 2);
        assertSame(objects, setO.getSetField().field());
        MethodInfo setO2 = X.findUniqueMethod("setO2", 2);
        assertSame(objects, setO2.getSetField().field());
        MethodInfo setO3 = X.findUniqueMethod("setO3", 1);
        assertNull(setO3.getSetField().field());

        MethodInfo getS = X.findUniqueMethod("getS", 1);
        assertSame(list, getS.getSetField().field());
        MethodInfo getS2 = X.findUniqueMethod("getS2", 0);
        assertNull(getS2.getSetField().field());

        MethodInfo setS = X.findUniqueMethod("setS", 2);
        assertSame(list, setS.getSetField().field());
        MethodInfo setS2 = X.findUniqueMethod("setS2", 2);
        assertSame(list, setS2.getSetField().field());
        MethodInfo setS3 = X.findUniqueMethod("setS3", 1);
        assertNull(setS3.getSetField().field());

        MethodInfo md = X.findUniqueMethod("d", 0);
        assertSame(d, md.getSetField().field());
        MethodInfo mdd = X.findUniqueMethod("dd", 0);
        assertSame(d, mdd.getSetField().field());

        MethodInfo mri = X.findUniqueMethod("ri", 0);
        assertSame(ri, mri.getSetField().field());
        MethodInfo mRri = X.findUniqueMethod("rri", 0);
        assertSame(ri, mRri.getSetField().field()); // IMPORTANT! we cannot yet tell the difference: are we accessing r or rr??
        MethodInfo mRi2 = X.findUniqueMethod("ri2", 0);
        assertNull(mRi2.getSetField().field());

        MethodInfo setD = X.findUniqueMethod("setD", 1);
        assertSame(d, setD.getSetField().field());
        MethodInfo setRI = X.findUniqueMethod("setRI", 1);
        assertSame(ri, setRI.getSetField().field());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                interface R {
                    @GetSet Set<Integer> set();
                    int i();
                }
            
                void setAdd(R r) {
                    r.set().add(r.i());
                }
            }
            """;

    /*
    why do we want r.set() to leave a variable trace? because the MODIFIED_COMPONENTS_PARAMETER analysis has to
    pick up the change in one of the components of 'r'. This is nigh impossible if we do not leave some variable trace.
     */
    @DisplayName("create variables for @GetSet access")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
        {
            Statement s0 = setAdd.methodBody().statements().get(0);
            VariableData vdSetAdd0 = VariableDataImpl.of(s0);
            //FIXME do we expect r.i to be known?
            assertEquals("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                    vdSetAdd0.knownVariableNamesToString());
        }
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                record Pair<F, G>(F f, G g) { }
            
                static <F, G> F getF(Pair<F, G> pair) {
                    return pair.f;
                }
                static <F, G> F getF2(Pair<F, G> pair) {
                    return pair.f(); // 1st level
                }
            
                record R<F, G>(Pair<F, G> pair) {
                    public R {
                        assert pair != null;
                    }
                }
                static <X, Y> boolean bothNotNull(R<X, Y> r) {
                    X x = r.pair.f;
                    Y y = r.pair().g(); // 2nd level, relies on correct recursion
                    return x != null && y != null;
                }
            }
            """;

    @DisplayName("create variables for @GetSet access, recursion")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);

        MethodInfo getF = X.findUniqueMethod("getF", 1);
        {
            Statement s0 = getF.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            assertEquals("""
                            a.b.X.Pair.f#a.b.X.getF(a.b.X.Pair<F,G>):0:pair, \
                            a.b.X.getF(a.b.X.Pair<F,G>), \
                            a.b.X.getF(a.b.X.Pair<F,G>):0:pair\
                            """,
                    vd0.knownVariableNamesToString());
        }
        MethodInfo getF2 = X.findUniqueMethod("getF2", 1);
        {
            Statement s0 = getF2.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            assertEquals("""
                            a.b.X.Pair.f#a.b.X.getF2(a.b.X.Pair<F,G>):0:pair, \
                            a.b.X.getF2(a.b.X.Pair<F,G>), \
                            a.b.X.getF2(a.b.X.Pair<F,G>):0:pair\
                            """,
                    vd0.knownVariableNamesToString());
        }

        MethodInfo bnn = X.findUniqueMethod("bothNotNull", 1);
        {
            Statement s0 = bnn.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            assertEquals("""
                            a.b.X.Pair.f#a.b.X.R.pair#a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            a.b.X.R.pair#a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            x\
                            """,
                    vd0.knownVariableNamesToString());
        }
        {
            Statement s1 = bnn.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
            assertEquals("""
                            a.b.X.Pair.f#a.b.X.R.pair#a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            a.b.X.Pair.g#a.b.X.R.pair#a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            a.b.X.R.pair#a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            a.b.X.bothNotNull(a.b.X.R<X,Y>):0:r, \
                            x, y\
                            """,
                    vd1.knownVariableNamesToString());
        }
    }
}
