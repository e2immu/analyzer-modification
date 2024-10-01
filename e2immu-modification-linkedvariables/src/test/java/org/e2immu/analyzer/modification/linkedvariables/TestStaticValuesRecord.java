package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.LocalVariableCreation;
import org.e2immu.language.cst.api.statement.ReturnStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.*;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestStaticValuesRecord extends CommonTest {

    public TestStaticValuesRecord() {
        super(true);
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
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0SetField = vd0.variableInfo(setFr);
            assertEquals("-1-:set", vi0SetField.linkedVariables().toString());
            assertEquals("E=set", vi0SetField.staticValues().toString());

            VariableInfo vi0SetParam = vd0.variableInfo(setParam);
            assertEquals("-1-:set", vi0SetParam.linkedVariables().toString());
            assertNull(vi0SetParam.staticValues());
        }
        {
            Statement s1 = constructor.methodBody().statements().get(1);
            VariableData vd1 = s1.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi1SetField = vd1.variableInfo(setFr);
            assertEquals("-1-:set", vi1SetField.linkedVariables().toString());
            assertEquals("E=set", vi1SetField.staticValues().toString());
            VariableInfo vi1NField = vd1.variableInfo(nFr);
            assertEquals("-1-:n", vi1NField.linkedVariables().toString());
            assertEquals("E=n", vi1NField.staticValues().toString());
        }
        {
            MethodInfo accessorSet = X.findUniqueMethod("set", 0);
            StaticValues svAccessorSet = accessorSet.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
            assertEquals("E=this.set", svAccessorSet.toString());
            FieldValue getSet = accessorSet.analysis().getOrDefault(PropertyImpl.GET_SET_FIELD, ValueImpl.FieldValueImpl.EMPTY);
            assertEquals(setField, getSet.field());
        }
        {
            StaticValues svSetField = setField.analysis().getOrNull(STATIC_VALUES_FIELD, StaticValuesImpl.class);
            assertEquals("E=set", svSetField.toString());
            assertSame(setParam, ((VariableExpression) svSetField.expression()).variable());
        }
        {
            StaticValues svSetParam = setParam.analysis().getOrNull(STATIC_VALUES_PARAMETER, StaticValuesImpl.class);
            assertEquals("E=this.set", svSetParam.toString());
            assertSame(setField, ((FieldReference) ((VariableExpression) svSetParam.expression()).variable()).fieldInfo());
        }
        {
            MethodInfo accessorN = X.findUniqueMethod("n", 0);
            StaticValues svAccessorN = accessorN.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
            assertEquals("E=this.n", svAccessorN.toString());
            FieldValue getSet = accessorN.analysis().getOrDefault(PropertyImpl.GET_SET_FIELD, ValueImpl.FieldValueImpl.EMPTY);
            assertEquals(nField, getSet.field());
        }
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.Set;
            class X {
                record R(Set<String> set, int n) {}
                int method(Set<String> in) {
                    R r = new R(in, 3);
                    return r.n;
                }
            }
            """;

    @DisplayName("values in record")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        prepWork(X);
        analyzer.doType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        LocalVariableCreation rLvc = (LocalVariableCreation) method.methodBody().statements().get(0);
        LocalVariable r = rLvc.localVariable();

        VariableData vd0 = rLvc.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rVi0 = vd0.variableInfo(r);
        assertEquals("Type a.b.X.R E=new R(in,3) this.n=3, this.set=in", rVi0.staticValues().toString());

        ReturnStatement rs = (ReturnStatement) method.methodBody().statements().get(1);
        {
            VariableData vd1 = rs.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo rVi1 = vd1.variableInfo(r);
            assertEquals(rVi0.staticValues(), rVi1.staticValues());

            VariableInfo rvVi1 = vd1.variableInfo(method.fullyQualifiedName());
            assertEquals("-1-:n", rvVi1.linkedVariables().toString());
            assertEquals("E=3", rvVi1.staticValues().toString());
        }
        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=3", methodSv.toString());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.Set;
            class X {
                record R(Set<String> set, int n) {}
                int method(Set<String> in) {
                    R r = new R(in, 3);
                    R s = r;
                    return s.n;
                }
            }
            """;

    @DisplayName("values in record, extra indirection")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        prepWork(X);
        analyzer.doType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        LocalVariableCreation rLvc = (LocalVariableCreation) method.methodBody().statements().get(0);
        LocalVariable r = rLvc.localVariable();

        VariableData vd0 = rLvc.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rVi0 = vd0.variableInfo(r);
        assertEquals("Type a.b.X.R E=new R(in,3) this.n=3, this.set=in", rVi0.staticValues().toString());

        {
            LocalVariableCreation sLvc = (LocalVariableCreation) method.methodBody().statements().get(1);
            VariableData vd1 = sLvc.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo sVi1 = vd1.variableInfo("s");
            assertEquals("-1-:r,-2-:in", sVi1.linkedVariables().toString());
            assertEquals(rVi0.staticValues(), sVi1.staticValues());
        }

        ReturnStatement rs = (ReturnStatement) method.methodBody().statements().get(2);
        {
            VariableData vd2 = rs.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
            VariableInfo rVi2 = vd2.variableInfo(r);
            assertEquals(rVi0.staticValues(), rVi2.staticValues());

            VariableInfo rvVi2 = vd2.variableInfo(method.fullyQualifiedName());
            assertEquals("-1-:n", rvVi2.linkedVariables().toString());
            assertEquals("E=3", rvVi2.staticValues().toString());
        }
        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=3", methodSv.toString());
    }


    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import java.util.Set;
            class X {
                record R<T>(T t) {}
                int method(Set<String> in) {
                    R<Set<String>> r = new R<>(in);
                    return r.t();
                }
            }
            """;

    @DisplayName("values in record, @Identity, accessor")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        prepWork(X);
        analyzer.doType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);
        LocalVariableCreation rLvc = (LocalVariableCreation) method.methodBody().statements().get(0);
        LocalVariable r = rLvc.localVariable();

        VariableData vd0 = rLvc.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rVi0 = vd0.variableInfo(r);
        assertEquals("Type a.b.X.R<java.util.Set<String>> E=new R<>(in) this.t=in", rVi0.staticValues().toString());
        assertEquals("0.0-4-0:in", rVi0.linkedVariables().toString());

        ReturnStatement rs = (ReturnStatement) method.methodBody().statements().get(1);

        VariableData vd1 = rs.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);
        VariableInfo rVi1 = vd1.variableInfo(r);
        assertEquals(rVi0.staticValues(), rVi1.staticValues());

        VariableInfo rvVi1 = vd1.variableInfo(method.fullyQualifiedName());
        assertEquals("*M-2-0M:r", rvVi1.linkedVariables().toString());
        // we don't want E=r.t here, that one can be substituted again because r.t=in
        assertEquals("E=in", rvVi1.staticValues().toString());

        StaticValues methodSv = method.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
        assertEquals("E=in", methodSv.toString());

        // @Identity method, we return the first parameter
        assertSame(TRUE, method.analysis().getOrDefault(PropertyImpl.IDENTITY_METHOD, FALSE));
        assertSame(FALSE, method.analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
    }
}
