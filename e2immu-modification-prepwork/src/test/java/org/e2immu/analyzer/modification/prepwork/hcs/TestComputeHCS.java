package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.*;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestComputeHCS extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                interface I<T> {
                    String m(T t);
                }
                static class C1<S> implements I<S> {
                    S s;
                    public String m(S s) {
                        return s.toString();
                    }
                }
                static class CI implements I<Integer> {
                    public String m(Integer i) {
                        return (i+1) + "";
                    }
                }
                static class CO {
                    Object o;
                    String s;
                    void set(Object o) {
                        this.o = o;
                        this.s = o.toString();
                    }
                    Object getO() {
                        return o;
                    }
                }
            }
            """;

    @DisplayName("basics, extension of TestComputeHiddenContent.basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        ComputeHiddenContent chc = new ComputeHiddenContent(javaInspector.runtime());
        ComputeHCS computeHCS = new ComputeHCS(runtime);

        TypeInfo integer = runtime.integerTypeInfo();
        assertFalse(integer.isExtensible());
        HiddenContentTypes hctInteger = chc.compute(integer);
        assertEquals("", hctInteger.detailedSortedTypes());
        integer.analysis().set(HIDDEN_CONTENT_TYPES, hctInteger);

        TypeInfo I = X.findSubType("I");
        HiddenContentTypes hctI = chc.compute(I);
        assertEquals("0=T", hctI.detailedSortedTypes());
        I.analysis().set(HIDDEN_CONTENT_TYPES, hctI);
        MethodInfo Im = I.findUniqueMethod("m", 1);
        HiddenContentTypes hctIM = chc.compute(hctI, Im);
        assertEquals("I:T - m:", hctIM.toString());
        Im.analysis().set(HIDDEN_CONTENT_TYPES, hctIM);

        HiddenContentSelector hcsIm = computeHCS.doHiddenContentSelector(Im);
        assertEquals("X", hcsIm.detailed());
        HiddenContentSelector hcsImM = Im.parameters().get(0).analysis().getOrDefault(HCS_PARAMETER, NONE);
        assertEquals("0=*", hcsImM.detailed());

        TypeInfo C1 = X.findSubType("C1");
        HiddenContentTypes hctC1 = chc.compute(C1);
        assertEquals("0=S", hctC1.detailedSortedTypes());
        assertEquals("S=0", hctC1.detailedSortedTypeToIndex());
        C1.analysis().set(HIDDEN_CONTENT_TYPES, hctC1);

        TypeInfo CI = X.findSubType("CI");
        HiddenContentTypes hctCI = chc.compute(CI);
        assertEquals("", hctCI.detailedSortedTypes());
        assertEquals("", hctCI.detailedSortedTypeToIndex());
        CI.analysis().set(HIDDEN_CONTENT_TYPES, hctCI);

        TypeInfo string = runtime.stringTypeInfo();
        assertFalse(string.isExtensible());
        HiddenContentTypes hctString = chc.compute(string);
        assertEquals("", hctString.detailedSortedTypes());
        string.analysis().set(HIDDEN_CONTENT_TYPES, hctString);

        TypeInfo object = runtime.objectTypeInfo();
        assertTrue(object.isExtensible());
        HiddenContentTypes hctObject = chc.compute(object);
        assertEquals("", hctObject.detailedSortedTypes());
        object.analysis().set(HIDDEN_CONTENT_TYPES, hctObject);

        TypeInfo comparable = javaInspector.compiledTypesManager().get(Comparable.class);
        assertTrue(object.isExtensible());
        HiddenContentTypes hctComparable = chc.compute(comparable);
        assertEquals("0=T", hctComparable.detailedSortedTypes());
        comparable.analysis().set(HIDDEN_CONTENT_TYPES, hctComparable);

        TypeInfo CO = X.findSubType("CO");
        HiddenContentTypes hctCO = chc.compute(CO);
        assertEquals("0=Object", hctCO.detailedSortedTypes());
        assertEquals("Object=0", hctCO.detailedSortedTypeToIndex());
        CO.analysis().set(HIDDEN_CONTENT_TYPES, hctCO);

        MethodInfo COGetO = CO.findUniqueMethod("getO", 0);
        HiddenContentTypes hctCoGetO = chc.compute(hctCO, COGetO);
        COGetO.analysis().set(HIDDEN_CONTENT_TYPES, hctCoGetO);

        HiddenContentSelector hcsCOGet = computeHCS.doHiddenContentSelector(COGetO);
        assertEquals("0=*", hcsCOGet.detailed());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                interface C {
                    void setO(Object o);
                    Object getO();
                }
                static class CO implements C {
                    Object o;
                    String s;
                    void setO(Object o) {
                        this.o = o;
                        this.s = o.toString();
                    }
                    Object getO() {
                        return o;
                    }
                }
            }
            """;

    @DisplayName("interface and implementation")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        ComputeHiddenContent chc = new ComputeHiddenContent(javaInspector.runtime());
        ComputeHCS computeHCS = new ComputeHCS(runtime);

        TypeInfo string = runtime.stringTypeInfo();
        assertFalse(string.isExtensible());
        HiddenContentTypes hctString = chc.compute(string);
        assertEquals("", hctString.detailedSortedTypes());
        string.analysis().set(HIDDEN_CONTENT_TYPES, hctString);

        /*
        in the interface C, setO and getO do not have the @GetSet annotation.
        We don't know whether to take Object as a HCT or not.
        Currently, we don't.
         */
        TypeInfo C = X.findSubType("C");
        HiddenContentTypes hctC = chc.compute(C);
        assertEquals("", hctC.detailedSortedTypes());
        C.analysis().set(HIDDEN_CONTENT_TYPES, hctC);

        MethodInfo CgetO = C.findUniqueMethod("getO", 0);
        HiddenContentTypes hctCgetO = chc.compute(hctC, CgetO);
        assertEquals("C: - getO:", hctCgetO.toString());
        CgetO.analysis().set(HIDDEN_CONTENT_TYPES, hctCgetO);

        MethodInfo CsetO = C.findUniqueMethod("setO", 1);
        HiddenContentTypes hctCsetO = chc.compute(hctC, CsetO);
        assertEquals("C: - setO:", hctCsetO.toString());
        CsetO.analysis().set(HIDDEN_CONTENT_TYPES, hctCgetO);

        HiddenContentSelector hcsCgetO = computeHCS.doHiddenContentSelector(CgetO);
        assertEquals("X", hcsCgetO.detailed());
        HiddenContentSelector hcsCSetO0 = CsetO.parameters().get(0).analysis().getOrDefault(HCS_PARAMETER, NONE);
        assertEquals("X", hcsCSetO0.detailed());

        TypeInfo CO = X.findSubType("CO");
        HiddenContentTypes hctCO = chc.compute(CO);
        assertEquals("0=Object", hctCO.detailedSortedTypes());
        CO.analysis().set(HIDDEN_CONTENT_TYPES, hctCO);

        MethodInfo COgetO = CO.findUniqueMethod("getO", 0);
        HiddenContentTypes hctCOgetO = chc.compute(hctCO, COgetO);
        assertEquals("CO:Object - getO:", hctCOgetO.toString());
        COgetO.analysis().set(HIDDEN_CONTENT_TYPES, hctCOgetO);

        MethodInfo COsetO = CO.findUniqueMethod("setO", 1);
        HiddenContentTypes hctCOsetO = chc.compute(hctCO, COsetO);
        assertEquals("CO:Object - setO:", hctCOsetO.toString());
        COsetO.analysis().set(HIDDEN_CONTENT_TYPES, hctCOgetO);

        // We go via the override...
        HiddenContentSelector hcsCOgetO = computeHCS.doHiddenContentSelector(COgetO);
        assertEquals("X", hcsCOgetO.detailed());
        HiddenContentSelector hcsCOSetO0 = COsetO.parameters().get(0).analysis().getOrDefault(HCS_PARAMETER, NONE);
        assertEquals("X", hcsCOSetO0.detailed());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            class X {
                interface C {
                    @GetSet void setO(Object o);
                    @GetSet Object getO();
                }
                static class CO implements C {
                    Object o;
                    String s;
                    void setO(Object o) {
                        this.o = o;
                        this.s = o.toString();
                    }
                    Object getO() {
                        return o;
                    }
                }
            }
            """;

    @DisplayName("interface and implementation, with @GetSet")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.initialize(javaInspector.compiledTypesManager().typesLoaded());
        prepAnalyzer.doPrimaryType(X);

        TypeInfo string = runtime.stringTypeInfo();
        assertFalse(string.isExtensible());
        HiddenContentTypes hctString = string.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("", hctString.detailedSortedTypes());

        /*
        in the interface C, setO and getO do not have the @GetSet annotation.
        We don't know whether to take Object as a HCT or not.
        Currently, we don't.
         */
        TypeInfo C = X.findSubType("C");
        assertTrue(C.isInterface());
        assertEquals(1, C.fields().size());
        FieldInfo synthetic = C.fields().get(0);
        assertEquals("o", synthetic.name());
        assertEquals("Type Object", synthetic.type().toString());
        assertTrue(synthetic.isSynthetic());
        assertTrue(C.isExtensible());

        MethodInfo CsetO = C.findUniqueMethod("setO", 1);
        assertSame(synthetic, CsetO.getSetField().field());
        MethodInfo CgetO = C.findUniqueMethod("getO", 0);
        assertSame(synthetic, CgetO.getSetField().field());

        HiddenContentTypes hctC = C.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=Object", hctC.detailedSortedTypes());

        HiddenContentTypes hctCgetO = CgetO.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("C:Object - getO:", hctCgetO.toString());

        HiddenContentTypes hctCsetO =  CsetO.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("C:Object - setO:", hctCsetO.toString());

        HiddenContentSelector hcsCgetO = CgetO.analysis().getOrDefault(HCS_METHOD, NONE);
        assertEquals("0=*", hcsCgetO.detailed());
        HiddenContentSelector hcsCSetO0 = CsetO.parameters().get(0).analysis().getOrDefault(HCS_PARAMETER, NONE);
        assertEquals("0=*", hcsCSetO0.detailed());

        TypeInfo CO = X.findSubType("CO");
        HiddenContentTypes hctCO = CO.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=Object", hctCO.detailedSortedTypes());

        MethodInfo COgetO = CO.findUniqueMethod("getO", 0);
        HiddenContentTypes hctCOgetO = COgetO.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("CO:Object - getO:", hctCOgetO.toString());

        MethodInfo COsetO = CO.findUniqueMethod("setO", 1);
        HiddenContentTypes hctCOsetO = COsetO.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("CO:Object - setO:", hctCOsetO.toString());

        HiddenContentSelector hcsCOgetO = COgetO.analysis().getOrDefault(HCS_METHOD, NONE);
        assertEquals("0=*", hcsCOgetO.detailed());
        HiddenContentSelector hcsCOSetO0 = COsetO.parameters().get(0).analysis().getOrDefault(HCS_PARAMETER, NONE);
        assertEquals("0=*", hcsCOSetO0.detailed());
    }
}
