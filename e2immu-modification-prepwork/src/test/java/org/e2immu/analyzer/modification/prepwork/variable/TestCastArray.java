package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.expression.Cast;
import org.e2immu.language.cst.api.expression.EnclosedExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.ExpressionAsStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestCastArray extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import java.lang.reflect.Array;
            
            public class X {
            
                public static void put(Object array, Object element, int index) {
                    try {
                        if (array instanceof Object[]) {
                            try {
                                ((Object[]) array)[index] = element;
                            } catch (ArrayStoreException e) {
                                throw new IllegalArgumentException();
                            }
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                        throw new ArrayIndexOutOfBoundsException(index + " into " + Array.getLength(array));
                    }
                }
            }
            """;

    public static final String AV_9_21 = "av-9-21";

    @DisplayName("variable should exist")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
        MethodInfo put = X.findUniqueMethod("put", 3);
        Statement s000 = put.methodBody().statements().get(0).block().statements().get(0);
        Statement s00000 = s000.block().statements().get(0);
        Statement s000000 = s00000.block().statements().get(0);
        if (s000000 instanceof ExpressionAsStatement eas) {
            if (eas.expression() instanceof Assignment a) {
                if (a.variableTarget() instanceof DependentVariable dv) {
                    assertTrue(dv.arrayExpression() instanceof EnclosedExpression ee && ee.expression() instanceof Cast);
                    assertEquals(AV_9_21, dv.arrayVariable().fullyQualifiedName());
                } else fail();
            } else fail();
        } else fail();

        VariableData vd000000 = VariableDataImpl.of(s000000);
        VariableInfo vi000000av = vd000000.variableInfo(AV_9_21);
        assertEquals("D:0.0.0.0.0.0.0, A:[]", vi000000av.assignments().toString());
        assertEquals("0.0.0.0.0.0.0", vi000000av.reads().toString());

        VariableData vd0000 = VariableDataImpl.of(s00000);
        assertFalse(vd0000.isKnown(AV_9_21));

        VariableData vd00 = VariableDataImpl.of(s000);
        assertFalse(vd00.isKnown(AV_9_21));
    }
}