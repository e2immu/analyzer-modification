package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class TestTemplate extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
""";

    @DisplayName("empty block causes issues")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }
}
