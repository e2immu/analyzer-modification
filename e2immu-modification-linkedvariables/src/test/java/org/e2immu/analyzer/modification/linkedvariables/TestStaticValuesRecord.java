package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class TestStaticValuesRecord extends CommonTest {

    public TestStaticValuesRecord() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            record X(Set<String> set, int n) {}
            """;

    @DisplayName("record")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        prepWork(X);
        analyzer.doType(X);
    }
}
