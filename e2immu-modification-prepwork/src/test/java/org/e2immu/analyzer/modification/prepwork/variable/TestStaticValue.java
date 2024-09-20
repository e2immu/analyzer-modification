package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.annotation.Fluent;
import org.e2immu.annotation.Modified;
import org.e2immu.annotation.method.GetSet;

import java.util.ArrayList;
import java.util.List;

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
public class TestStaticValue {

    @Modified
    private final List<Integer> list = new ArrayList<>();

    @Modified
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

    @Modified
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

    @FunctionalInterface
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

        @GetSet // and indexed... notation??
        @Override
        public Object get(int i) {
            return variables[i];
        }

        @Override
        public TryData withException(Exception exception) {
            return new TryDataImpl(throwingFunction, variables, exception);
        }

        @GetSet
        @Override
        public ThrowingFunction throwingFunction() {
            return throwingFunction;
        }

        @GetSet
        @Override
        public Exception exception() {
            return exception;
        }

        @GetSet // immutable fashion, indexed?
        @Override
        public TryData with(int pos, Object value) {
            Object[] newVariables = variables.clone();
            newVariables[pos] = value;
            return new TryDataImpl(throwingFunction, newVariables, exception);
        }

        public static class Builder {
            private final Object[] variables = new Object[10]; // static value: set:0,1
            private ThrowingFunction bodyThrowingFunction; // static value: body:0

            @GetSet
            @Fluent
            public Builder body(ThrowingFunction throwingFunction) {
                this.bodyThrowingFunction = throwingFunction;
                return this;
            }

            @GetSet // indexed
            @Fluent
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
