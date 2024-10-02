package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.junit.jupiter.api.Assertions.*;

public class TestStaticValuesAssignment extends CommonTest {

    public TestStaticValuesAssignment() {
        super(true);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Set;
            class X {
                int method() {
                    int j=3;
                    return j;
                }
            }
            """;

    @DisplayName("direct assignment")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 0);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0J = vd0.variableInfo("j");
            assertEquals("", vi0J.linkedVariables().toString());
            assertEquals("E=3", vi0J.staticValues().toString());
        }
        {
            Statement s1 = method.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi1Rv = vd1.variableInfo(method.fullyQualifiedName());
            assertEquals("-1-:j", vi1Rv.linkedVariables().toString());
            assertEquals("E=3", vi1Rv.staticValues().toString());
        }

        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=3", methodSv.toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            class X {
                int j;
                int k;
                X setJ(int jp) {
                    j=jp;
                    return this;
                }
                X setJK(int jp, int kp) {
                    j=jp;
                    k=kp;
                    return this;
                }
                static X method() {
                    X x = new X().setJ(3);
                    return x;
                }
            }
            """;

    @DisplayName("assignment to field in builder")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        FieldInfo fieldJ = X.getFieldByName("j", true);
        MethodInfo setJ = X.findUniqueMethod("setJ", 1);
        {
            Statement s0 = setJ.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0J = vd0.variableInfo(runtime.newFieldReference(fieldJ));
            assertEquals("-1-:jp", vi0J.linkedVariables().toString());
            assertEquals("E=jp", vi0J.staticValues().toString());
        }
        {
            Statement s1 = setJ.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi1Rv = vd1.variableInfo(setJ.fullyQualifiedName());
            assertEquals("-1-:this", vi1Rv.linkedVariables().toString());
            assertEquals("E=this this.j=jp", vi1Rv.staticValues().toString());
        }

        StaticValues setJSv = setJ.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=this this.j=jp", setJSv.toString());

        MethodInfo setJK = X.findUniqueMethod("setJK", 2);
        StaticValues setJKSv = setJK.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=this this.j=jp, this.k=kp", setJKSv.toString());

        MethodInfo method = X.findUniqueMethod("method", 0);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0X = vd0.variableInfo("x");
            assertEquals("E=new X() this.j=3", vi0X.staticValues().toString());
        }
        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=new X() this.j=3", methodSv.toString());
    }
}
