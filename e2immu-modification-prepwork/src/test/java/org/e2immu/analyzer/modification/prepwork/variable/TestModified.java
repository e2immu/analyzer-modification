package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestModified extends CommonTest {

    public TestModified() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                List<String> list = new ArrayList<>();
                void add(String s) {
                    list.add(s);
                }
                String get() {
                    return list.get(0);
                }
            }
            """;

    @Language("java")
    private static final String OUTPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import org.e2immu.annotation.Modified;
            class X {
                List<String> list = new ArrayList<>();
                @Modified void add(String s) { list.add(s); }
                String get() { return list.get(0); }
            }
            """;

    @DisplayName("basics of modification")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);

        MethodInfo add = X.findUniqueMethod("add", 1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(add);
        VariableData vdAdd = add.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdAdd);
        assertEquals("a.b.X.add(String):0:s, a.b.X.list, a.b.X.this", vdAdd.knownVariableNamesToString());

        String listFqn = X.fields().get(0).fullyQualifiedName();
        String thisFqn = X.fullyQualifiedName() + ".this";

        VariableInfo listVi = vdAdd.variableInfo(listFqn);
        assertEquals("list", listVi.variable().simpleName());
        assertTrue(listVi.isModified());

        VariableInfo thisVi = vdAdd.variableInfo(thisFqn);
        assertTrue(thisVi.isModified());

        boolean mm = add.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
        assertTrue(mm);

        MethodInfo get = X.findUniqueMethod("get", 0);
        analyzer.doMethod(get);
        VariableData vdGet = get.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assertNotNull(vdGet);
        assertEquals("a.b.X.get(), a.b.X.list, a.b.X.this", vdGet.knownVariableNamesToString());

        VariableInfo listViGet = vdGet.variableInfo(listFqn);
        assertEquals("list", listViGet.variable().simpleName());
        assertFalse(listViGet.isModified());

        VariableInfo thisViGet = vdGet.variableInfo(thisFqn);
        assertFalse(thisViGet.isModified());

        boolean mmGet = get.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
        assertFalse(mmGet);


        String result = printType(X);
        assertEquals(OUTPUT1, result);
    }

}
