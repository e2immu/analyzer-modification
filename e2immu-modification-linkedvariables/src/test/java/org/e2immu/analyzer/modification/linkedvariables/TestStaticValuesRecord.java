package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestStaticValuesRecord extends CommonTest {

    public TestStaticValuesRecord() {
        super(false); // not needed for such a simple test
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
        FieldInfo setField = X.getFieldByName("set", true);
        FieldReference setFr = runtime.newFieldReference(setField);
        FieldInfo nField = X.getFieldByName("n", true);
        FieldReference nFr = runtime.newFieldReference(nField);

        MethodInfo constructor = X.findConstructor(2);
        ParameterInfo setParam = constructor.parameters().get(0);
        {
            Statement s0 = constructor.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0SetField = vd0.variableInfo(setFr);
            assertEquals("-1-:set", vi0SetField.linkedVariables().toString());
            assertEquals("E=set", vi0SetField.staticValues().toString());

            VariableInfo vi0SetParam = vd0.variableInfo(setParam);
            assertEquals("-1-:set", vi0SetParam.linkedVariables().toString());
            assertNull(vi0SetParam.staticValues());
        }
        {
            Statement s1 = constructor.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi1SetField = vd1.variableInfo(setFr);
            assertEquals("-1-:set", vi1SetField.linkedVariables().toString());
            assertEquals("E=set", vi1SetField.staticValues().toString());
            VariableInfo vi1NField = vd1.variableInfo(nFr);
            assertEquals("-1-:n", vi1NField.linkedVariables().toString());
            assertEquals("E=n", vi1NField.staticValues().toString());
        }
    }
}
