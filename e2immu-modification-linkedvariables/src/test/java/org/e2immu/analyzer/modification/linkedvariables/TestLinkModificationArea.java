package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
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

public class TestLinkModificationArea extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class X {
                static class M { int i; int get() { return i; } void set(int i) { this.i = i; }}
                record R(M a) {}
                static M getA(R r) {
                    return r.a;
                }
            }
            """;

    @DisplayName("getter")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo M = X.findSubType("M");
        assertTrue(M.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isMutable());
        assertTrue(M.isExtensible());
        TypeInfo R = X.findSubType("R");
        HiddenContentTypes hctR = R.analysis().getOrDefault(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("0=M", hctR.detailedSortedTypes());

        MethodInfo getA = X.findUniqueMethod("getA", 1);
        {
            Statement s0 = getA.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viRv = vd0.variableInfo(getA.fullyQualifiedName());
            assertEquals("E=r.a", viRv.staticValues().toString());
            assertEquals("*M-2-0M|*-0:r,-1-:a", viRv.linkedVariables().toString());
        }
    }

    @Language("java")
    private static final String INPUT1b = """
            package a.b;
            import java.util.Set;
            class X {
                record M(int i) {}
                record R(M a) {}
                static M getA(R r) {
                    return r.a;
                }
            }
            """;

    @DisplayName("getter, immutable, non-extensible variant")
    @Test
    public void test1b() {
        TypeInfo X = javaInspector.parse(INPUT1b);
        List<Info> analysisOrder = prepWork(X);
        // because we do not have an analyzer yet
        TypeInfo M = X.findSubType("M");
        M.analysis().set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE);
        TypeInfo R = X.findSubType("R");
        R.analysis().set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE);
        analyzer.doPrimaryType(X, analysisOrder);
        assertFalse(M.isExtensible());
        HiddenContentTypes hctR = R.analysis().getOrDefault(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("", hctR.detailedSortedTypes());

        MethodInfo getA = X.findUniqueMethod("getA", 1);
        {
            Statement s0 = getA.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viRv = vd0.variableInfo(getA.fullyQualifiedName());
            assertEquals("E=r.a", viRv.staticValues().toString());
            assertEquals("-1-:a", viRv.linkedVariables().toString());
        }
    }


    @Language("java")
    private static final String INPUT1c = """
            package a.b;
            import java.util.Set;
            class X {
                static class M<T> { final T t; M(T t) { this.t = t; }}
                record R<T>(M<T> a) {}
                static <T> M<T> getA(R<T> r) {
                    return r.a;
                }
                static <T> T getT(R<T> r) {
                    return r.a.t;
                }
            }
            """;

    @DisplayName("getter, immutable HC, extensible variant")
    @Test
    public void test1c() {
        TypeInfo X = javaInspector.parse(INPUT1c);
        List<Info> analysisOrder = prepWork(X);
        // because we do not have an analyzer yet
        TypeInfo M = X.findSubType("M");
        M.analysis().set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE_HC);
        TypeInfo R = X.findSubType("R");
        R.analysis().set(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.IMMUTABLE_HC);
        analyzer.doPrimaryType(X, analysisOrder);
        HiddenContentTypes hctR = R.analysis().getOrDefault(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("0=T, 1=M", hctR.detailedSortedTypes());

        MethodInfo getA = X.findUniqueMethod("getA", 1);
        {
            Statement s0 = getA.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viRv = vd0.variableInfo(getA.fullyQualifiedName());
            assertEquals("E=r.a", viRv.staticValues().toString());
            assertEquals("*-4-1:r,-1-:a", viRv.linkedVariables().toString());
        }
        MethodInfo getT = X.findUniqueMethod("getT", 1);
        {
            Statement s0 = getT.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viRv = vd0.variableInfo(getT.fullyQualifiedName());
            assertEquals("E=r.a.t", viRv.staticValues().toString());
            // not recursive! we don't need the transitive completion here
            assertEquals("*-4-0:a,-1-:t", viRv.linkedVariables().toString());
        }
    }


    @Language("java")
    private static final String INPUT1d = """
            package a.b;
            import java.util.Set;
            class X {
                static final class M { int i; int get() { return i; } void set(int i) { this.i = i; }}
                record R(M a) {}
                static M getA(R r) {
                    return r.a;
                }
            }
            """;

    @DisplayName("getter, mutable, non-extensible variant")
    @Test
    public void test1d() {
        TypeInfo X = javaInspector.parse(INPUT1d);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo M = X.findSubType("M");
        assertFalse(M.isExtensible());
        TypeInfo R = X.findSubType("R");
        HiddenContentTypes hctR = R.analysis().getOrDefault(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("", hctR.detailedSortedTypes());

        MethodInfo getA = X.findUniqueMethod("getA", 1);
        {
            Statement s0 = getA.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viRv = vd0.variableInfo(getA.fullyQualifiedName());
            assertEquals("E=r.a", viRv.staticValues().toString());
            assertEquals("-1-:a,-2-|*-0:r", viRv.linkedVariables().toString());
        }
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            class X {
                static class M { int i; int get() { return i; } void set(int i) { this.i = i; }}
                record R(M a, M b) {}
                static void modifyA(R r) {
                    M aa = r.a;
                    M bb = r.b;
                    aa.set(3);
                }
            }
            """;

    @DisplayName("modify one component")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        MethodInfo modifyA = X.findUniqueMethod("modifyA", 1);
        ParameterInfo r = modifyA.parameters().get(0);
        {
            Statement s0 = modifyA.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viA = vd0.variableInfo("aa");
            assertEquals("E=r.a", viA.staticValues().toString());
            assertEquals("*M-2-0M|*-0:r,-1-:a", viA.linkedVariables().toString());

            VariableInfo viR = vd0.variableInfo(r);
            assertEquals("0M-2-*M|0-*:a,0M-2-*M|0-*:aa", viR.linkedVariables().toString());
        }
        {
            Statement s1 = modifyA.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo viA = vd1.variableInfo("aa");
            assertEquals("E=r.a", viA.staticValues().toString());
            assertEquals("*M-2-0M|*-0:r,-1-:a", viA.linkedVariables().toString());

            VariableInfo viB = vd1.variableInfo("bb");
            assertEquals("E=r.b", viB.staticValues().toString());
            assertEquals("*M-2-0M|*-1:r,-1-:b", viB.linkedVariables().toString());

            VariableInfo viR = vd1.variableInfo(r);
            assertEquals("0M-2-*M|0-*:a,0M-2-*M|0-*:aa,0M-2-*M|1-*:b,0M-2-*M|1-*:bb",
                    viR.linkedVariables().toString());
        }
        {
            Statement s2 = modifyA.methodBody().statements().get(2);
            VariableData vd2 = VariableDataImpl.of(s2);
            VariableInfo viA = vd2.variableInfo("aa");
            assertTrue(viA.isModified());

            VariableInfo viB = vd2.variableInfo("bb");
            assertFalse(viB.isModified());

            VariableInfo viR = vd2.variableInfo(r);
            assertTrue(viR.isModified());
        }
    }
}