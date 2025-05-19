package org.e2immu.analyzer.modification.linkedvariables.immutable;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeCallGraph;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestImmutable extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class X {
                record R(String s, int i) {}
            
                // hidden content
                record RT<T>(String s, T t) {}
            
                // setter modifies field
                static class M { int i; void setI(int i) { this.i = i; }}
            
                // field is exposed
                static class MF { final M m; MF(M m) { this.m = m; }}
            
                // field is exposed through accessor
                record RM(M m, int i) {}
            }
            """;

    @DisplayName("basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(X);
        assertEquals("""
                [a.b.X.<init>(), a.b.X.M.<init>(), a.b.X.M.setI(int), a.b.X.R.<init>(String,int), a.b.X.R.i(), \
                a.b.X.R.s(), a.b.X.RM.i(), a.b.X.RT.<init>(String,T), a.b.X.RT.s(), a.b.X.RT.t(), a.b.X.M.i, a.b.X.R.i, \
                a.b.X.R.s, a.b.X.RT.s, a.b.X.RT.t, a.b.X.M, a.b.X.R, a.b.X.RT, a.b.X.MF.MF(a.b.X.M), \
                a.b.X.RM.<init>(a.b.X.M,int), a.b.X.RM.m(), a.b.X.MF.m, a.b.X.RM.i, a.b.X.RM.m, a.b.X.MF, a.b.X.RM, a.b.X]\
                """, ao.toString());
        analyzer.go(ao);

        TypeInfo R = X.findSubType("R");
        assertTrue(immutable(R).isImmutable());

        TypeInfo RT = X.findSubType("RT");
        assertFalse(immutable(RT).isImmutable());
        assertTrue(immutable(RT).isAtLeastImmutableHC());

        TypeInfo M = X.findSubType("M");
        assertTrue(immutable(M).isMutable());

        TypeInfo MF = X.findSubType("MF");
        assertTrue(immutable(MF).isMutable());

        TypeInfo RM = X.findSubType("RM");
        assertTrue(immutable(RM).isMutable());
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import org.e2immu.annotation.Modified;
            class X {
                interface I {}
            
                interface J extends Comparable<J> {}
            
                // modifying
                interface K {
                    @Modified
                    void add(String s);
                }
            
                // modifying, because K is
                interface KK extends K {
                    int get();
                }
            
                interface L {
                    int get();
                }
            
                //modifying (implicit in abstract void methods)
                interface M extends L {
                    void set(int i);
                }
            }
            """;

    @DisplayName("interfaces")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo I = X.findSubType("I");
        assertTrue(immutable(I).isImmutableHC());

        TypeInfo J = X.findSubType("J");
        assertTrue(immutable(J).isImmutableHC());

        TypeInfo K = X.findSubType("K");
        assertTrue(immutable(K).isMutable());

        TypeInfo KK = X.findSubType("KK");
        assertTrue(immutable(KK).isMutable());

        TypeInfo L = X.findSubType("L");
        assertTrue(immutable(L).isImmutableHC());

        TypeInfo M = X.findSubType("M");
        MethodInfo mSet = M.findUniqueMethod("set", 1);
        assertTrue(mSet.isModifying());
        assertTrue(immutable(M).isMutable());
    }

    private Value.Immutable immutable(TypeInfo r) {
        return r.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.Set;
            class X {
                record Pair<F, G>(F f, G g) {
                }
            
                record R<F, G>(Pair<F, G> pair) {
                    public R {
                        assert pair != null;
                    }
                }
            }
            """;

    @DisplayName("type parameters in records")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo Pair = X.findSubType("Pair");
        Value.Immutable immutablePair = immutable(Pair);
        assertTrue(immutablePair.isImmutableHC(), "Have " + immutablePair);

        TypeInfo R = X.findSubType("R");
        MethodInfo pair = R.findUniqueMethod("pair", 0);
        assertNotNull(pair.getSetField().field());
        Value.Independent pairIndependent = pair.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD,
                ValueImpl.IndependentImpl.DEPENDENT);
        assertTrue(pairIndependent.isIndependentHc());

        Value.Immutable immutableR = immutable(R);
        assertTrue(immutableR.isImmutableHC(), "Have " + immutableR);
    }


    @Language("java")
    private static final String INPUT4 = """
        package a.b;
        public class X {
            public interface Exit {
            }
        
            public static class Break implements Exit {
                private final int level;
        
                public Break(int level) {
                    this.level = level;
                }
            }
        
            public static class Continue implements Exit {
                private final int level;
        
                public Continue(int level) {
                    this.level = level;
                }
        
                public int level() {
                    return level;
                }
            }
        
            public static class Return implements Exit {
                private final Object value;
        
                public Return(Object value) {
                    this.value = value;
                }
        
                public Object value() {
                    return value;
                }
            }
        
            public static class ExceptionThrown implements Exit {
                private final Exception exception;
        
                public ExceptionThrown(Exception exception) {
                    this.exception = exception;
                }
        
                public Exception exception() {
                    return exception;
                }
            }
        }
        """;

    @DisplayName("only one is @FinalFields")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo Continue = X.findSubType("Continue");
        Value.Immutable immutableContinue = immutable(Continue);
        assertTrue(immutableContinue.isImmutableHC(), "Have " + immutableContinue);

        TypeInfo Break = X.findSubType("Continue");
        Value.Immutable immutableBreak = immutable(Break);
        assertTrue(immutableBreak.isImmutableHC(), "Have " + immutableBreak);

        TypeInfo Return = X.findSubType("Return");
        Value.Immutable immutableReturn = immutable(Return);
        assertTrue(immutableReturn.isImmutableHC(), "Have " + immutableReturn);

        TypeInfo exceptionThrown = X.findSubType("ExceptionThrown");
        Value.Immutable immutableEt = immutable(exceptionThrown);
        assertTrue(immutableEt.isFinalFields(), "Have " + immutableEt);
    }

}
