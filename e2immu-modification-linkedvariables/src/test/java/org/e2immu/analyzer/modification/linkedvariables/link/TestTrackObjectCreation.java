package org.e2immu.analyzer.modification.linkedvariables.link;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.impl.IteratingAnalyzerImpl;
import org.e2immu.analyzer.modification.linkedvariables.impl.MethodModAnalyzerImpl;
import org.e2immu.analyzer.modification.linkedvariables.impl.ModAnalyzerForTesting;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestTrackObjectCreation extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            public class X<T> {
                private static final Logger LOGGER = LoggerFactory.getLogger(X.class);
                public static List<T> makeList() {
                    return new LinkedList<>();
                }
                public static List<T> makeList2() {
                    List<T> list = new LinkedList<>();
                    LOGGER.info("List {}", list);
                    return list;
                }
                public static List<T> makeList3(boolean b) {
                    if(b) {
                        List<T> list = new LinkedList<>();
                        LOGGER.info("List {}", list);
                        return list;
                    }
                    return null;
                }
                public static List<T> makeList4(boolean b) {
                    if(b) {
                        List<T> list = new LinkedList<>();
                        LOGGER.info("List {}", list);
                        return list;
                    }
                    return new ArrayList<>();
                }
            }
            """;

    @DisplayName("basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime, true, true);
        List<Info> analysisOrder = prepAnalyzer.doPrimaryType(X);
        IteratingAnalyzer.Configuration configuration = new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setTrackObjectCreations(true)
                .build();
        ModAnalyzerForTesting analyzer = new MethodModAnalyzerImpl(runtime, configuration);
        analyzer.go(analysisOrder);
        {
            MethodInfo makeList = X.findUniqueMethod("makeList", 0);
            VariableData vdMethod = VariableDataImpl.of(makeList.methodBody().lastStatement());
            assertEquals("a.b.X.makeList(), oc:10-16:java.util.LinkedList<T>", vdMethod.knownVariableNamesToString());

            VariableInfo viRv = vdMethod.variableInfo(makeList.fullyQualifiedName());
            assertEquals("-1-:oc:10-16", viRv.linkedVariables().toString());
        }
        {
            MethodInfo makeList = X.findUniqueMethod("makeList2", 0);
            VariableData vdMethod = VariableDataImpl.of(makeList.methodBody().lastStatement());
            assertEquals("a.b.X.LOGGER, a.b.X.makeList2(), list, oc:13-24:java.util.LinkedList<T>",
                    vdMethod.knownVariableNamesToString());

            VariableInfo viRv = vdMethod.variableInfo(makeList.fullyQualifiedName());
            assertEquals("-1-:list, -1-:oc:13-24", viRv.linkedVariables().toString());
        }
        {
            MethodInfo makeList = X.findUniqueMethod("makeList3", 1);
            VariableData vdMethod = VariableDataImpl.of(makeList.methodBody().lastStatement());
            assertEquals("""
                    a.b.X.LOGGER, a.b.X.makeList3(boolean), a.b.X.makeList3(boolean):0:b, \
                    oc:19-28:java.util.LinkedList<T>\
                    """, vdMethod.knownVariableNamesToString());

            VariableInfo viRv = vdMethod.variableInfo(makeList.fullyQualifiedName());
            assertEquals("-1-:oc:19-28", viRv.linkedVariables().toString());
        }
        {
            MethodInfo makeList = X.findUniqueMethod("makeList4", 1);
            VariableData vdMethod = VariableDataImpl.of(makeList.methodBody().lastStatement());
            assertEquals("""
                    a.b.X.LOGGER, a.b.X.makeList4(boolean), a.b.X.makeList4(boolean):0:b, \
                    oc:27-28:java.util.LinkedList<T>, oc:31-16:java.util.ArrayList<T>\
                    """, vdMethod.knownVariableNamesToString());

            VariableInfo viRv = vdMethod.variableInfo(makeList.fullyQualifiedName());
            assertEquals("-1-:oc:27-28, -1-:oc:31-16", viRv.linkedVariables().toString());
        }
    }
}
