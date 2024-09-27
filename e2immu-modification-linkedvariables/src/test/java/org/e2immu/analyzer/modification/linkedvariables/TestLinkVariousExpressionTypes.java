package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestLinkVariousExpressionTypes extends CommonTest {

    public TestLinkVariousExpressionTypes() {
        super(true);
    }

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
                static M method(Object object) {
                    if (object instanceof M m) {
                        return m;
                    }
                    return null;
                }
            }
            """;

    @DisplayName("instanceof pattern variable")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        prepWork(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        analyzer.doMethod(method);

        Statement s000 = method.methodBody().statements().get(0).block().statements().get(0);
        VariableData vd000 = s000.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rv000 = vd000.variableInfo(method.fullyQualifiedName());
        assertEquals("-1-:m,-1-:object", rv000.linkedVariables().toString());

        Statement s0 = method.methodBody().statements().get(0);
        VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rv0 = vd0.variableInfo(method.fullyQualifiedName());
        assertEquals("-1-:m,-1-:object", rv0.linkedVariables().toString());

        VariableData vd = method.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rv = vd.variableInfo(method.fullyQualifiedName());
        assertEquals("-1-:object", rv.linkedVariables().toString());
    }

}
