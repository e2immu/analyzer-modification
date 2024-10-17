package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.EMPTY;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.LINKED_VARIABLES_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestLinkTypeParameters extends CommonTest {

    @Language("java")
    public static final String INPUT1 = """
            package a.b;
            import org.e2immu.support.SetOnce;
            public class X {
                static class M { int i; int getI() { return i; } void setI(int i) { this.i = i; } }
                static class N { int i; int getI() { return i; } void setI(int i) { this.i = i; } }
            
                record Pair<F, G>(F f, G g) {
                }
            
                record R<F, G>(Pair<F, G> pair) {
                    public R {
                        assert pair != null;
                    }
                }
            
                record R1<F, G>(SetOnce<Pair<F, G>> setOncePair) {
                }
            
                static <X, Y> Pair<X, Y> create0(X x, Y y) {
                    return new Pair<>(x, y);
                }
            
                static <X> Pair<X, M> create1(X x, M m) {
                    return new Pair<>(x, m);
                }
            
                static <X> Pair<X, M> create2(X x, M m) {
                    //noinspection ALL
                    Pair<X, M> p = new Pair<>(x, m);
                    return p;
                }
            
                static Pair<N, M> create3(N n, M m) {
                    //noinspection ALL
                    Pair<N, M> p = new Pair<>(n, m);
                    return p;
                }
            
                static Pair<Integer, M> create4(Integer i, M m) {
                    //noinspection ALL
                    Pair<Integer, M> p = new Pair<>(i, m);
                    return p;
                }
            }
            """;

    @DisplayName("constructing pairs")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo create0 = X.findUniqueMethod("create0", 2);
        assertEquals("0-4-*:x,1-4-*:y", lvs(create0));

        MethodInfo create1 = X.findUniqueMethod("create1", 2);
        assertEquals("0-4-*:x,1M-4-*M:m", lvs(create1));

        MethodInfo create2 = X.findUniqueMethod("create2", 2);
        assertEquals("0-4-*:x,1M-4-*M:m", lvs(create2));

        MethodInfo create3 = X.findUniqueMethod("create3", 2);
        assertEquals("0M-4-*M:n,1M-4-*M:m", lvs(create3));

        MethodInfo create4 = X.findUniqueMethod("create4", 2);
        assertEquals("1M-4-*M:m", lvs(create4));
    }

    private static String lvs(MethodInfo methodInfo) {
        return methodInfo.analysis().getOrDefault(LINKED_VARIABLES_METHOD, EMPTY).toString();
    }


    @Language("java")
    public static final String INPUT2 = """
            package a.b;
            import org.e2immu.support.SetOnce;
            public class X {
            
                record Pair<F, G>(F f, G g) {
                }
            
                record R<F, G>(Pair<F, G> pair) {
                    public R {
                        assert pair != null;
                    }
                }
            
                record R1<F, G>(SetOnce<Pair<F, G>> setOncePair) {
                }
            
                static <X, Y> Pair<X, Y> copy(Pair<X, Y> pair) {
                    return new Pair<>(pair.f, pair.g);
                }
            
                static <Y> Pair<?, Y> copy2(Pair<?, Y> pair) {
                    return new Pair<>(pair.f, pair.g);
                }
            
                static Pair<?, ?> copy3(Pair<?, ?> pair) {
                    return new Pair<>(pair.f, pair.g);
                }
            
                static Pair copy4(Pair pair) {
                    return new Pair(pair.f, pair.g);
                }
            
                static <X, Y> Pair<Y, X> reverse(Pair<X, Y> pair) {
                    return new Pair<>(pair.g, pair.f);
                }
            
                static <X, Y> Pair<Y, X> reverse2(Pair<X, Y> pair) {
                    return new Pair<>(pair.g(), pair.f());
                }
            
                static <X, Y> Pair<Y, X> reverse3(R<X, Y> r) {
                    return new Pair<>(r.pair.g, r.pair.f);
                }
            
                static <X, Y> R<Y, X> reverse4(R<X, Y> r) {
                    return new R<>(new Pair<>(r.pair.g, r.pair.f));
                }
            
                static <X, Y> R<Y, X> reverse5(R<X, Y> r) {
                    Pair<Y, X> yxPair = new Pair<>(r.pair.g, r.pair.f);
                    return new R<>(yxPair);
                }
            
                static <X, Y> R<Y, X> reverse6(R<X, Y> r) {
                    return new R(new Pair(r.pair.g, r.pair.f));
                }
            
                static <X, Y> R<Y, X> reverse7(X x, Y y) {
                    return new R<>(new Pair<>(y, x));
                }
            
                static <X, Y> R<Y, X> reverse8(R<X, Y> r1, R<X, Y> r2) {
                    return new R<>(new Pair<>(r2.pair.g, r1.pair.f));
                }
            }
            """;

    @DisplayName("reverse, and pack in R")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo R = X.findSubType("R");

        // ... no analyzer yet
        assertTrue(R.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isMutable());

        MethodInfo copy = X.findUniqueMethod("copy", 1);
        assertEquals("0-4-*:f,0;1-4-*:pair,1-4-*:g", lvs(copy));

        MethodInfo copy2 = X.findUniqueMethod("copy2", 1);
        assertEquals("0-4-*:f,0;1-4-*:pair,1-4-*:g", lvs(copy2));

        MethodInfo copy3 = X.findUniqueMethod("copy3", 1);
        assertEquals("0;1-4-*:f,0;1-4-*:g,0;1-4-*:pair", lvs(copy3));

        MethodInfo copy4 = X.findUniqueMethod("copy4", 1);
        assertEquals("0;1-4-*:f,0;1-4-*:g,0;1-4-*:pair", lvs(copy4));

        MethodInfo reverse = X.findUniqueMethod("reverse", 1);
        assertEquals("0-4-*:g,0;1-4-*:pair,1-4-*:f", lvs(reverse));

        MethodInfo reverse2 = X.findUniqueMethod("reverse2", 1);
        // FIXME links to f, g missing
        assertEquals("0-4-*:g,0;1-4-*:pair,1-4-*:f", lvs(reverse2));

        MethodInfo reverse3 = X.findUniqueMethod("reverse3", 1);
        assertEquals("0-4-*:g,0;1-4-*:pair,1-4-*:f", lvs(reverse3));

        // FIXME links to R missing
        MethodInfo reverse4 = X.findUniqueMethod("reverse4", 1);
        assertEquals("0,1-2-0,1:pair,0-4-*:g,1-4-*:f, link to r", lvs(reverse4));
    }

}
