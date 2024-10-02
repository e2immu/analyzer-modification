package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.annotation.Fluent;
import org.e2immu.annotation.Modified;
import org.e2immu.annotation.method.GetSet;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl.VARIABLE_DATA;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
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

    public TestStaticValuesOfTryData() {
        super(true);
    }

    @Language("java")
    public static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                private final List<Integer> list = new ArrayList<>();
            
                public int method(int i) {
                    // after this statement, 'b' holds static values 'i' for variables[0], and 'this::body' for 'bodyThrowingFunction'.
                    TryDataImpl.Builder b = new TryDataImpl.Builder().set(0, i).body(this::body);
            
                    // after this statement, 'td' holds static values 'i' for variables[0], and 'this::body' for 'throwingFunction'.
                    TryData td = b.build();
            
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
                    TryData apply(TryData o) throws Exception;
                }
            
                public interface TryData {
                    ThrowingFunction throwingFunction();
            
                    TryData withException(Exception t);
            
                    Exception exception();
            
                    TryData with(int pos, Object value);
            
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
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo tryDataImpl = X.findSubType("TryDataImpl");
        TypeInfo builder = tryDataImpl.findSubType("Builder");
        MethodInfo body = builder.findUniqueMethod("body", 1);
        assertSame(TRUE, body.analysis().getOrDefault(PropertyImpl.FLUENT_METHOD, FALSE));
        assertEquals("E=this this.bodyThrowingFunction=throwingFunction", body.analysis()
                .getOrNull(StaticValuesImpl.STATIC_VALUES_METHOD, StaticValuesImpl.class).toString());

        MethodInfo method = X.findUniqueMethod("method", 1);
        {
            Statement s0 = method.methodBody().statements().get(0);
            VariableData vd0 = s0.analysis().getOrNull(VARIABLE_DATA, VariableDataImpl.class);

            VariableInfo vi0B = vd0.variableInfo("b");
            assertEquals("", vi0B.linkedVariables().toString());
            assertEquals("E=(new Builder()).set(0,i) this.bodyThrowingFunction=this::body",
                    vi0B.staticValues().toString());
        }
    }

}
