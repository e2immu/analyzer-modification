package org.e2immu.analyzer.modification.linkedvariables.modification;

import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.impl.IteratingAnalyzerImpl;
import org.e2immu.analyzer.modification.linkedvariables.impl.MethodModAnalyzerImpl;
import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestModificationBasics extends CommonTest {
    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            import java.util.Iterator;
            class Test {
                public Iterator<String> m(List<String> items) {
                    return items.iterator();
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse(INPUT);
        e2immuAnalysis(X);
        MethodInfo m = X.findUniqueMethod("m", 1);
        assertFalse(m.isModifying());
        assertFalse(m.parameters().getFirst().isModified());
    }

    public void e2immuAnalysis(TypeInfo typeInfo) {
        List<TypeInfo> typesLoaded = javaInspector.compiledTypesManager().typesLoaded();
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.initialize(typesLoaded);
        List<Info> ao = prepAnalyzer.doPrimaryType(typeInfo);
        IteratingAnalyzer.Configuration configuration = new IteratingAnalyzerImpl.ConfigurationBuilder().build();
        MethodModAnalyzerImpl analyzer = new MethodModAnalyzerImpl(runtime, configuration);
        analyzer.doPrimaryType(typeInfo, ao);
    }

}
