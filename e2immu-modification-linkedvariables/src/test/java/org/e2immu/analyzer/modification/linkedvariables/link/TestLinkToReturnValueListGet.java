package org.e2immu.analyzer.modification.linkedvariables.link;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLinkToReturnValueListGet extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.List;
            class X {
                final static class M {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
                static <T> T get(List<T> list, int i) {
                    return list.get(i);
                }
                static M get2(List<M> list, int i) {
                    return list.get(i);
                }
                static String get3(List<String> list, int i) {
                    return list.get(i);
                }
                static Object get4(List<Object> list, int i) {
                    return list.get(i);
                }
            }
            """;

    @DisplayName("return list.get(index)")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);

        testLinks(X);
    }

    private void testLinks(TypeInfo X) {
        MethodInfo listGet = X.findUniqueMethod("get", 2);

        Statement s0 = listGet.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(s0);
        assertNotNull(vd0);
        VariableInfo viRv = vd0.variableInfo(listGet.fullyQualifiedName());
        assertEquals("*-4-0:_synthetic_list, -1-:_synthetic_list[i], *M-4-0M:list", viRv.linkedVariables().toString());

        assertEquals(viRv.linkedVariables(), listGet.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY));

        MethodInfo listGet2 = X.findUniqueMethod("get2", 2);
        assertEquals("*M-2-0M|*-?:_synthetic_list, -1-:_synthetic_list[i], *M-2-0M|*-0.?:list",
                listGet2.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                        LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet3 = X.findUniqueMethod("get3", 2);
        assertEquals("-1-:_synthetic_list[i]", listGet3.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet4 = X.findUniqueMethod("get4", 2);
        assertEquals("*-4-0:_synthetic_list, -1-:_synthetic_list[i], *M-4-0M:list",
                listGet4.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                        LinkedVariablesImpl.EMPTY).toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                final static class M {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
                static <T> T get(List<T> list, int i) {
                    return new ArrayList<>(list).get(i);
                }
                static M get2(List<M> list, int i) {
                    return new ArrayList<>(list).get(i);
                }
                static String get3(List<String> list, int i) {
                    return new ArrayList<>(list).get(i);
                }
                static Object get4(List<Object> list, int i) {
                    return new ArrayList<>(list).get(i);
                }
            }
            """;

    @DisplayName("return new ArrayList<>(list).get(index)")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);

        TypeInfo typeInfo = javaInspector.compiledTypesManager().get(ArrayList.class);
        TypeInfo collectionTypeInfo = javaInspector.compiledTypesManager().get(Collection.class);
        MethodInfo methodInfo = typeInfo.findConstructor(collectionTypeInfo);
        assertEquals("java.util.ArrayList.<init>(java.util.Collection<? extends E>)", methodInfo.fullyQualifiedName());
        HiddenContentTypes methodHct = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("0=E - 1=Collection", methodHct.detailedSortedTypes());
        assertEquals("ArrayList:E - <init>:Collection", methodHct.toString());

        ParameterInfo p0 = methodInfo.parameters().get(0);
        HiddenContentSelector paramHcs = p0.analysis().getOrDefault(HCS_PARAMETER, HiddenContentSelector.NONE);
        assertEquals("0=0,1=*", paramHcs.detailed());

        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);

        testLinks2(X);
    }

    private void testLinks2(TypeInfo X) {
        MethodInfo listGet = X.findUniqueMethod("get", 2);

        Statement s0 = listGet.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(s0);
        assertNotNull(vd0);
        VariableInfo viRv = vd0.variableInfo(listGet.fullyQualifiedName());
        assertEquals("*-4-0:_synthetic_list, -1-:_synthetic_list[i]", viRv.linkedVariables().toString());

        assertEquals(viRv.linkedVariables(), listGet.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY));

        MethodInfo listGet2 = X.findUniqueMethod("get2", 2);
        assertEquals("*M-4-0M:list", listGet2.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet3 = X.findUniqueMethod("get3", 2);
        assertEquals("", listGet3.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet4 = X.findUniqueMethod("get4", 2);
        assertEquals("*-4-0:list", listGet4.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY).toString());
    }

    private void testLinks3(TypeInfo X) {
        MethodInfo listGet = X.findUniqueMethod("get", 2);

        Statement s0 = listGet.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(s0);
        assertNotNull(vd0);
        VariableInfo viRv = vd0.variableInfo(listGet.fullyQualifiedName());
        assertEquals("*-4-0:_synthetic_list, -1-:_synthetic_list[i]", viRv.linkedVariables().toString());

        assertEquals(viRv.linkedVariables(), listGet.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                LinkedVariablesImpl.EMPTY));

        MethodInfo listGet2 = X.findUniqueMethod("get2", 2);
        assertEquals("*M-2-0M|*-?:_synthetic_list, -1-:_synthetic_list[i]",
                listGet2.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                        LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet3 = X.findUniqueMethod("get3", 2);
        assertEquals("-1-:_synthetic_list[i]", listGet3.analysis()
                .getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD, LinkedVariablesImpl.EMPTY).toString());

        MethodInfo listGet4 = X.findUniqueMethod("get4", 2);
        assertEquals("*-4-0:_synthetic_list, -1-:_synthetic_list[i]",
                listGet4.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD,
                        LinkedVariablesImpl.EMPTY).toString());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.List;
            class X {
                final static class M {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
                static <T> T get(List<T> list, int i) {
                    return list.subList(0, 10).get(i);
                }
                static M get2(List<M> list, int i) {
                    return list.subList(0, 10).get(i);
                }
                static String get3(List<String> list, int i) {
                    return list.subList(0, 10).get(i);
                }
                static Object get4(List<Object> list, int i) {
                    return list.subList(0, 10).get(i);
                }
            }
            """;

    @DisplayName("return list.subList(0, 10).get(index)")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);
        testLinks3(X);
    }


    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import java.util.List;
            class X {
                final static class M {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
                static <T> T get(List<T> list, int i) {
                    return list.subList(0, 10).subList(1, 5).get(i);
                }
                static M get2(List<M> list, int i) {
                    return list.subList(0, 10).subList(1, 5).get(i);
                }
                static String get3(List<String> list, int i) {
                    return list.subList(0, 10).subList(1, 5).get(i);
                }
                static Object get4(List<Object> list, int i) {
                    return list.subList(0, 10).subList(1, 5).get(i);
                }
            }
            """;

    @DisplayName("return list.subList(0, 10).subList(1, 5).get(index)")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);
        testLinks3(X);
    }

}
