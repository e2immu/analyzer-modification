package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestStaticValuesModification extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                record R(Set<Integer> set, int i, List<String> list) {}
            
                void setAdd(R r) {
                    r.set.add(r.i);
                }
            
                void method() {
                    List<String> l = new ArrayList<>();
                    Set<Integer> s = new HashSet<>();
                    R r = new R(s, 3, l);
                    setAdd(r); // at this point, s1 should have been modified, via???
                }
            }
            """;

    @DisplayName("modification of a single component")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
        ParameterInfo setAdd0 = setAdd.parameters().get(0);
        assertTrue(setAdd0.isModified());
        VariableData vdSetAdd0 = VariableDataImpl.of(setAdd.methodBody().lastStatement());
        VariableInfo viSetAdd0R = vdSetAdd0.variableInfo(setAdd0);
        assertTrue(viSetAdd0R.isModified());
        assertEquals("a.b.X.R.i#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                vdSetAdd0.knownVariableNamesToString());
        VariableInfo viRSet = vdSetAdd0.variableInfo("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r");
        assertTrue(viRSet.isModified());

        MethodInfo method = X.findUniqueMethod("method", 0);
        {
            Statement s2 = method.methodBody().statements().get(2);
            VariableData vd2 = VariableDataImpl.of(s2);
            VariableInfo vd2S = vd2.variableInfo("s");
            assertFalse(vd2S.isModified());
            assertEquals("*M-2-FM:r", vd2S.linkedVariables().toString());
            assertEquals("Type java.util.HashSet<Integer> E=new HashSet<>()", vd2S.staticValues().toString());

            VariableInfo vd2L = vd2.variableInfo("l");
            assertEquals("*M-2-FM:r", vd2L.linkedVariables().toString());
            assertEquals("Type java.util.ArrayList<String> E=new ArrayList<>()", vd2L.staticValues().toString());

            assertFalse(vd2L.isModified());
            VariableInfo vd2R = vd2.variableInfo("r");
            assertFalse(vd2R.isModified());
            assertEquals("FM-2-*M:l,FM-2-*M:s", vd2R.linkedVariables().toString());
            assertEquals("Type a.b.X.R E=new R(s,3,l) this.i=3, this.list=l, this.set=s", vd2R.staticValues().toString());
        }
        {
            Statement s3 = method.methodBody().statements().get(3);
            VariableData vd3 = VariableDataImpl.of(s3);
            VariableInfo vi3R = vd3.variableInfo("r");
            assertEquals("Type a.b.X.R E=new R(s,3,l) this.i=3, this.list=l, this.set=s", vi3R.staticValues().toString());
            assertEquals("FM-2-*M:l,FM-2-*M:s", vi3R.linkedVariables().toString());
            assertTrue(vi3R.isModified());

            VariableInfo vd3S = vd3.variableInfo("s");
            assertEquals("*M-2-FM:r", vd3S.linkedVariables().toString());
            assertTrue(vd3S.isModified()); // FIXME: we want to achieve that eventually, but don't have the code yet

            VariableInfo vd3L = vd3.variableInfo("l");
            assertEquals("*M-2-FM:r", vd3L.linkedVariables().toString());
            assertTrue(vd3L.isModified()); // FIXME
        }
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                interface R {
                    @GetSet Set<Integer> set();
                    int i();
                }
                record RI(Set<Integer> set, int i, List<String> list) implements R {}
            
                void setAdd(R r) {
                    r.set().add(r.i());
                }
            
                void method() {
                    List<String> l = new ArrayList<>();
                    Set<Integer> s = new HashSet<>();
                    R r = new RI(s, 3, l);
                    setAdd(r); // at this point, s1 should have been modified, via???
                }
            }
            """;

    @DisplayName("modification of a single component, interface in between")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        // see e2immu-inspection-integration.TestRecord for a check of the @GetSet and override properties of R, RI

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
        ParameterInfo setAdd0 = setAdd.parameters().get(0);
        assertTrue(setAdd0.isModified());
        VariableData vdSetAdd0 = VariableDataImpl.of(setAdd.methodBody().lastStatement());
        VariableInfo viSetAdd0R = vdSetAdd0.variableInfo(setAdd0);
        assertTrue(viSetAdd0R.isModified());
        assertEquals("a.b.X.setAdd(a.b.X.R):0:r", vdSetAdd0.knownVariableNamesToString());
        // FIXME here the 'r.set()' accessor has not been expanded, the r.set field reference does not exist, and
        //  we have no knowledge of which component is modified
        //  but at least there is a 1-1 mapping between the components of the interface, and the components of the implementation
        //    thanks to the @GetSet
        // it would help if we had a @Modified("set") here on the parameter 'r'

        MethodInfo method = X.findUniqueMethod("method", 0);
        Statement s3 = method.methodBody().statements().get(3);
        VariableData vd3 = VariableDataImpl.of(s3);
        VariableInfo vi3R = vd3.variableInfo("r");
        assertEquals("Type a.b.X.RI E=new RI(s,3,l) this.i=3, this.list=l, this.set=s", vi3R.staticValues().toString());
        assertEquals("-2-:this,FM-2-*M:l,FM-2-*M:s", vi3R.linkedVariables().toString());
        assertTrue(vi3R.isModified());

        VariableInfo vd3S = vd3.variableInfo("s");
        assertEquals("*M-2-FM:r,-2-:l,-2-:this", vd3S.linkedVariables().toString());
        assertTrue(vd3S.isModified());
        // FIXME all link related issues remain the same
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.Fluent;import org.e2immu.annotation.Modified;import org.e2immu.annotation.method.GetSet;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                interface R {
                    @Modified//("set")   corresponds to LoopData.hasNext()
                    void add();
                }
                record RI(Set<Integer> set, int i, List<String> list) implements R {
                    @Override
                    void add() {
                        set.add(i);
                    }
                }
                static class Builder {
                    Set<Integer> intSet; // different name
                    List<String> stringList; // ditto
                    @Fluent
                    Builder intSet(Set<Integer> intSet) {
                        this.intSet = intSet;
                        return this;
                    }
                    @Fluent
                    Builder stringList(List<String> stringList) {
                        this.stringList = stringList;
                        return this;
                    }
                    RI build() {
                        return new RI(intSet, intSet.size(), stringList);
                    }
                }
            
                void setAdd(R r) { //@Modified("set") on r, corresponds to Loop.run(LD initial)
                    r.add();
                }
            
                void method() {
                    List<String> l = new ArrayList<>();
                    Set<Integer> s = new HashSet<>();
                    Builder b = new Builder().intSet(s).stringList(l);
                    R r = b.build();
                    setAdd(r);
                }
            }
            """;

    @DisplayName("situation similar to Loop's iterator")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo method = X.findUniqueMethod("method", 0);
        Statement s2 = method.methodBody().statements().get(2);
        VariableData vd2 = VariableDataImpl.of(s2);
        VariableInfo vi2B = vd2.variableInfo("b");
        assertEquals("E=new Builder() this.intSet=s, this.stringList=l", vi2B.staticValues().toString());

        // FIXME then this should go over to R
        Statement s3 = method.methodBody().statements().get(3);
        VariableData vd3 = VariableDataImpl.of(s3);
        VariableInfo vi3R = vd3.variableInfo("r");
        assertEquals("Type a.b.X.RI this.intSet=s, this.stringList=l", vi3R.staticValues().toString());

        // FIXME at the same time, the method 'setAdd(R r)' should have a modification to the component 'set'
        //  this comes about because of the @Modified("set") on the R.add() method
        // and then we're fine.
    }
}