package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.cst.impl.variable.FieldReferenceImpl;
import org.e2immu.language.cst.impl.variable.ThisImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.NONE;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_FI_COMPONENTS_PARAMETER;
import static org.junit.jupiter.api.Assertions.*;

/*
how does 'method' become @Modified?
the problem is one of indirection: 'body', which is modified, is passed into the builder 'b' and then the
TryData td to be passed on to 'run', which actually activates the method. Calling run(td) should modify 'this'.

A system of static values should work with getters, setters, constructors.
It should work with direct assignment, addition, additionAt and get, getAt.
It should work in a single pass.
The information flow is method's statements -> field summary -> parameter backlink.

The current quick-fix is to make a map 'method is modified if body is modified', using
calls to TDI.Builder.body(),.resources() and LDI.Builder.body().
 */
public class TestStaticValuesOfTryData extends CommonTest {

    @Language("java")
    public static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import org.e2immu.annotation.Independent;import org.e2immu.annotation.Modified;import org.e2immu.annotation.method.GetSet;
            class X {
                private final List<Integer> list = new ArrayList<>();
            
                public int method(int i) {
                    // after this statement, 'b' holds static values 'i' for variables[0], and 'this::body' for 'bodyThrowingFunction'.
                    TryDataImpl.Builder b = new TryDataImpl.Builder().set(0, i).body(this::body);
            
                    // after this statement, 'td' holds static values 'i' for variables[0], and 'this::body' for 'throwingFunction'.
                    TryDataImpl td = b.build();
            
                    // after this statement, a modification is executed on the scope of the value of 'throwingFunction' in 'td',
                    // which happens to be 'this'. This is done because a 'potential modification' signal is received for a
                    // modifying method (this::body).
                    TryData r = run(td);
                    if (r.exception() instanceof RuntimeException re) throw re;
                    return list.size();
                }
            
                public List<Integer> getList() {
                    return list;
                }
            
                private TryData body(TryData td) {
                    int i = (int) td.get(0);
                    if (i < 0) throw new IllegalArgumentException();
                    list.add(i);
                    return td.with(0, -i);
                }
            
                /*
                the method is not modified, and neither is parameter 'td'.
                However, the 'apply' sends a 'potential modification' signal to the scope of
                the value of 'td.throwingFunction()' == 'td.throwingFunction'.
                 */
                public static TryData run(TryData td) {
                    try {
                        return td.throwingFunction().apply(td);
                    } catch (Exception e) {
                        return td.withException(e);
                    }
                }
            
                public interface ThrowingFunction {
                    @Modified
                    TryData apply(@Independent TryData o) throws Exception;
                }
            
                public interface TryData {
                    @GetSet
                    ThrowingFunction throwingFunction();
            
                    TryData withException(Exception t);
            
                    @GetSet
                    Exception exception();
            
                    TryData with(int pos, Object value);
            
                    @GetSet("variables")
                    Object get(int i);
                }
            
                public static class TryDataImpl implements TryData {
                    private final ThrowingFunction throwingFunction; // static value: TryDataImpl:0
                    private final Object[] variables; // static values: TryDataImpl:1, with:0,1
                    private final Exception exception; // static values: TryDataImpl:2, withException:0 (indirectly)
            
                    public TryDataImpl(ThrowingFunction throwingFunction, // back-link to field throwingFunction
                                       Object[] variables, // ...
                                       Exception exception) { // ...
                        this.exception = exception;
                        this.throwingFunction = throwingFunction;
                        this.variables = variables;
                    }
            
                    @Override
                    public Object get(int i) {
                        return variables[i];
                    }
            
                    @Override
                    public TryData withException(Exception exception) {
                        return new TryDataImpl(throwingFunction, variables, exception);
                    }
            
                    @Override
                    public ThrowingFunction throwingFunction() {
                        return throwingFunction;
                    }
            
                    @Override
                    public Exception exception() {
                        return exception;
                    }
            
                    @Override
                    public TryData with(int pos, Object value) {
                        Object[] newVariables = variables.clone();
                        newVariables[pos] = value;
                        return new TryDataImpl(throwingFunction, newVariables, exception);
                    }
            
                    public static class Builder {
                        private final Object[] variables = new Object[10]; // static value: set:0,1
                        private ThrowingFunction bodyThrowingFunction; // static value: body:0
            
                        public Builder body(ThrowingFunction throwingFunction) {
                            this.bodyThrowingFunction = throwingFunction;
                            return this;
                        }
            
                        public Builder set(int pos, Object value) {
                            variables[pos] = value;
                            return this;
                        }
            
                        /*
                        the build method links the static values of this class to the static values
                        of the resulting TryDataImpl object.
                         */
                        public TryDataImpl build() {
                            return new TryDataImpl(bodyThrowingFunction, variables, null);
                        }
            
                    }
                }
            }
            """;


    @DisplayName("static assignment in TryData")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT);
        List<Info> analysisOrder = prepWork(X);

        TypeInfo exception = javaInspector.compiledTypesManager().get(Exception.class);
        assertTrue(exception.isExtensible());
        TypeInfo throwingFunction = X.findSubType("ThrowingFunction");
        assertTrue(throwingFunction.isExtensible());

        TypeInfo tryData = X.findSubType("TryData");
        TypeInfo tryDataImpl = X.findSubType("TryDataImpl");
        TypeInfo builder = tryDataImpl.findSubType("Builder");

        testGetSet(tryData, tryDataImpl);

        analyzer.doPrimaryType(X, analysisOrder);

        testBuilderBody(builder);
        testBuilderBuild(builder);
        testXRun(X);
        testXMethod(X);
    }

    private static void testXMethod(TypeInfo X) {
        MethodInfo method = X.findUniqueMethod("method", 1);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);

            VariableInfo vi0B = vd0.variableInfo("b");
            assertEquals("", vi0B.linkedVariables().toString());
            assertEquals("E=new Builder() this.bodyThrowingFunction=this::body, variables[0]=i",
                    vi0B.staticValues().toString());

            VariableInfo vi0This = vd0.variableInfo(new ThisImpl(X.asParameterizedType()));
            assertEquals("", vi0This.linkedVariables().toString());
            assertFalse(vi0This.isModified());
        }
        {
            Statement s1 = method.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);

            VariableInfo vi1Td = vd1.variableInfo("td");
            assertEquals("-2-:b", vi1Td.linkedVariables().toString());
            assertEquals("Type a.b.X.TryDataImpl this.throwingFunction=this::body, variables[0]=i",
                    vi1Td.staticValues().toString());
        }
        {
            Statement s2 = method.methodBody().statements().get(2);
            VariableData vd2 = VariableDataImpl.of(s2);

            VariableInfo vi2This = vd2.variableInfo(new ThisImpl(X.asParameterizedType()));
            assertEquals("", vi2This.linkedVariables().toString());
            assertTrue(vi2This.isModified());
        }
    }

    private static void testGetSet(TypeInfo tryData, TypeInfo tryDataImpl) {
        HiddenContentTypes hctTryData = tryData.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=ThrowingFunction, 1=Exception, 2=Object", hctTryData.detailedSortedTypes());
        MethodInfo tryDataGet = tryData.findUniqueMethod("get", 1);
        HiddenContentTypes hctTryDataGet = tryDataGet.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=ThrowingFunction, 1=Exception, 2=Object - ", hctTryDataGet.detailedSortedTypes());

        // the implementation does see Object
        HiddenContentTypes hctTryDataImpl = tryDataImpl.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=ThrowingFunction, 1=Object, 2=Exception", hctTryDataImpl.detailedSortedTypes());

        MethodInfo tryDataImplGet = tryDataImpl.findUniqueMethod("get", 1);
        HiddenContentTypes hctTryDataImplGet = tryDataImplGet.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=ThrowingFunction, 1=Object, 2=Exception - ", hctTryDataImplGet.detailedSortedTypes());
        HiddenContentSelector hcsTryDataImplGet = tryDataImplGet.analysis().getOrDefault(HCS_METHOD, NONE);
        assertEquals("2=*", hcsTryDataImplGet.detailed());
    }

    private static void testXRun(TypeInfo X) {
        MethodInfo run = X.findUniqueMethod("run", 1);
        {
            ParameterInfo runTd = run.parameters().get(0);
            Statement s0 = run.methodBody().statements().get(0);
            Statement s000 = s0.block().statements().get(0);
            VariableData vd000 = VariableDataImpl.of(s000);
            VariableInfo viTd000 = vd000.variableInfo(runTd);
            assertEquals("td.throwingFunction=true", viTd000.analysis().getOrNull(MODIFIED_FI_COMPONENTS_VARIABLE,
                    ValueImpl.VariableBooleanMapImpl.class).toString());

            // test that the analysis is copied "up" to the try statement
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo viTd0 = vd0.variableInfo(runTd);
            assertEquals("td.throwingFunction=true", viTd0.analysis().getOrNull(MODIFIED_FI_COMPONENTS_VARIABLE,
                    ValueImpl.VariableBooleanMapImpl.class).toString());

            // test that it is copied onto the parameter
            assertEquals("td.throwingFunction=true", runTd.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.class).toString());
        }
    }

    private static void testBuilderBuild(TypeInfo builder) {
        MethodInfo build = builder.findUniqueMethod("build", 0);
        assertEquals("""
                        Type a.b.X.TryDataImpl E=new TryDataImpl(this.bodyThrowingFunction,this.variables,null) \
                        this.exception=null, \
                        this.throwingFunction=this.bodyThrowingFunction, \
                        this.variables=this.variables\
                        """,
                build.analysis().getOrDefault(StaticValuesImpl.STATIC_VALUES_METHOD, StaticValuesImpl.NONE).toString());
        assertFalse(build.isModifying());
    }

    private static void testBuilderBody(TypeInfo builder) {
        MethodInfo body = builder.findUniqueMethod("body", 1);
        {
            ParameterInfo body0 = body.parameters().get(0);
            FieldInfo bodyThrowingFunction = builder.getFieldByName("bodyThrowingFunction", true);
            {
                Statement s0 = body.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0 = vd0.variableInfo(body0);
                assertFalse(vi0.isModified());
                assertEquals("-1-:bodyThrowingFunction", vi0.linkedVariables().toString()); // link to the field
                VariableInfo viBtf0 = vd0.variableInfo(new FieldReferenceImpl(bodyThrowingFunction));
                assertFalse(viBtf0.isModified()); // assignment is not a modification!
            }
            {
                Statement s1 = body.methodBody().statements().get(1);
                VariableData vd1 = VariableDataImpl.of(s1);
                VariableInfo vi1 = vd1.variableInfo(body0);
                assertEquals("-1-:bodyThrowingFunction", vi1.linkedVariables().toString()); // still link to field

                VariableInfo vi1This = vd1.variableInfo(new ThisImpl(builder.asParameterizedType()));
                assertEquals("", vi1This.linkedVariables().toString());
                VariableInfo vi1Btf = vd1.variableInfo(new FieldReferenceImpl(bodyThrowingFunction));
                assertEquals("-1-:throwingFunction", vi1Btf.linkedVariables().toString());

                assertFalse(vi1.isModified());
                assertFalse(vi1Btf.isModified()); // assignment is not a modification!
            }
            assertTrue(body.isFluent());
            assertEquals("E=this this.bodyThrowingFunction=throwingFunction", body.analysis()
                    .getOrNull(StaticValuesImpl.STATIC_VALUES_METHOD, StaticValuesImpl.class).toString());
            assertFalse(body0.isModified());
            assertTrue(body.isModifying());
        }
    }

}
