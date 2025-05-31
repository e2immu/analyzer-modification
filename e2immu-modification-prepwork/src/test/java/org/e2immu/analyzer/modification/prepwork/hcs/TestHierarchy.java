package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestHierarchy extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            
            class X {
                interface LoopData {
                    LoopData withBreak(int pos);
                }
                static class LoopDataImpl implements LoopData {
                    @Override
                    public LoopData withBreak(int pos) {
                        return new LoopDataImpl();
                    }
                }
            }
            """;

    @DisplayName("correct HCS_METHOD")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.initialize(javaInspector.compiledTypesManager().typesLoaded());
        prepAnalyzer.doPrimaryType(X);

        TypeInfo ldi = X.findSubType("LoopDataImpl");
        MethodInfo withBreak = ldi.findUniqueMethod("withBreak", 1);
        HiddenContentSelector hcsMethodWithBreak = withBreak.analysis().getOrNull(HiddenContentSelector.HCS_METHOD, HiddenContentSelector.class);
        assertNotNull(hcsMethodWithBreak);
        assertSame(withBreak, hcsMethodWithBreak.hiddenContentTypes().getMethodInfo());
    }
}
