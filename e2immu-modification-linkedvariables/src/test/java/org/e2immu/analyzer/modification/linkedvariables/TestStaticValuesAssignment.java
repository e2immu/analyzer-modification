package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.Variable;
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


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.Set;
            record X(int j, int k) {
            
                class Builder {
                    int j;
                    int k;
                    Builder setJ(int jp) {
                        j=jp;
                        return this;
                    }
                    Builder setJK(int jp, int kp) {
                        j=jp;
                        k=kp;
                        return this;
                    }
                    X build() {
                        return new X(j, k);
                    }
                }
                static X justJ4(int jp) {
                    Builder b = new Builder().setJK(jp, 4);
                    return b.build();
                }
                static X justJ(int jp) {
                    Builder b = new Builder().setJ(jp);
                    return b.build();
                }
            }
            """;

    @DisplayName("from builder to built class")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo builder = X.findSubType("Builder");
        MethodInfo build = builder.findUniqueMethod("build", 0);
        StaticValues svBuild = build.analysis().getOrDefault(STATIC_VALUES_METHOD, NONE);
        assertEquals("Type a.b.X E=new X(this.j,this.k)", svBuild.toString());

        {
            MethodInfo justJ = X.findUniqueMethod("justJ", 1);
            Statement s0 = justJ.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi0B = vd0.variableInfo("b");
            assertEquals("E=new Builder() this.j=jp", vi0B.staticValues().toString());

            Statement s1 = justJ.methodBody().lastStatement();
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi1Rv = vd1.variableInfo(justJ.fullyQualifiedName());
            assertEquals("Type a.b.X this.j=jp", vi1Rv.staticValues().toString());
        }

        {
            MethodInfo justJ4 = X.findUniqueMethod("justJ4", 1);
            Statement s0 = justJ4.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi0B = vd0.variableInfo("b");
            assertEquals("E=new Builder() this.j=jp, this.k=4", vi0B.staticValues().toString());

            Statement s1 = justJ4.methodBody().lastStatement();
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo vi1Rv = vd1.variableInfo(justJ4.fullyQualifiedName());
            assertEquals("Type a.b.X this.j=jp, this.k=4", vi1Rv.staticValues().toString());
        }
    }



    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import java.util.Set;
            class X {
                static class R { int i; }
    
                int method(R r) {
                    r.i = 3;
                    return r.i+2;
                }
            }
            """;

    @DisplayName("one level deep")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 1);
        ParameterInfo r = method.parameters().get(0);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0R = vd0.variableInfo(r);
            assertEquals("", vi0R.linkedVariables().toString());
            assertEquals("r.i=3", vi0R.staticValues().toString());

            Variable ri = vi0R.staticValues().values().keySet().stream().findFirst().orElseThrow();
            VariableInfo vi0Ri = vd0.variableInfo(ri);
            assertEquals("", vi0Ri.linkedVariables().toString());
            assertEquals("E=3", vi0Ri.staticValues().toString());
        }
        {
            Statement s1 = method.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi1Rv = vd1.variableInfo(method.fullyQualifiedName());
            assertEquals("", vi1Rv.linkedVariables().toString());
            assertEquals("", vi1Rv.staticValues().toString());
        }

        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("", methodSv.toString());
    }


    @Language("java")
    private static final String INPUT5 = """
            package a.b;
            import java.util.Set;
            class X {
                record R(int i, int j) {}
                record S(R r, int k) {}
    
                int method(S s) {
                    s.r().i = 3;
                    s.k = s.r.j;
                    return s.r().i+s.r.j()+s.k;
                }
            }
            """;

    @DisplayName("two levels deep")
    @Test
    public void test5() {
        TypeInfo X = javaInspector.parse(INPUT5);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 1);
        ParameterInfo s = method.parameters().get(0);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            // at this point, only s.r.i has a static value E=3; s.r and s do not have one ... should they?
            // s.r should have component i=3
            // s should have r.i=3
            VariableInfo vi0J = vd0.variableInfo(s);
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

}
