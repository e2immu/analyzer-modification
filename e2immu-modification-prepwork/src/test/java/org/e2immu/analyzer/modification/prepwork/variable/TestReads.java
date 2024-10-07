package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.statement.WhileStatement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestReads extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public class X {
                public static int arrayIndexOf(byte[] array, byte[] subsequence, int fromIndex) {
                    if (subsequence.length == 0)
                        return 0;
                    else {
                        int j;
                        int stop = (array.length - subsequence.length) + 1;
                        while (fromIndex < stop) {
                            if (array[fromIndex] == subsequence[0]) {
                                j = 1;
                                while (j < subsequence.length) {
                                    if (array[fromIndex + j] == subsequence[j])
                                        j++;
                                    else
                                        break;
                                }
                                if (j == subsequence.length)
                                    return fromIndex;
                            }
                            fromIndex++;
                        }
                        return -1;
                    }
                }
            }
            """;

    @DisplayName("nested while, array access")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        MethodInfo method = X.findUniqueMethod("arrayIndexOf", 3);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doMethod(method);
        IfElseStatement ie0 = (IfElseStatement) method.methodBody().statements().get(0);
        Statement outerWhile = ie0.elseBlock().statements().get(2);
        WhileStatement innerWhile = (WhileStatement) outerWhile.block().statements().get(0).block().statements().get(1);
        IfElseStatement ieInInner = (IfElseStatement) innerWhile.block().statements().get(0);
        VariableData vd = VariableDataImpl.of(ieInInner);
        ParameterInfo fromIndex = method.parameters().get(2);
        VariableInfo viFromIndex = vd.variableInfo(fromIndex);
        assertEquals("0.1.2-E, 0.1.2.0.0-E, 0.1.2.0.0.0.1.0.0-E, 0.1.2;E", viFromIndex.reads().toString());
    }
}
