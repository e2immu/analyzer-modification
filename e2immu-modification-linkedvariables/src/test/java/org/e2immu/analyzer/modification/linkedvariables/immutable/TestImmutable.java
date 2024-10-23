package org.e2immu.analyzer.modification.linkedvariables.immutable;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
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
        analyzer.doPrimaryType(X, ao);

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
        analyzer.doPrimaryType(X, ao);

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
}
