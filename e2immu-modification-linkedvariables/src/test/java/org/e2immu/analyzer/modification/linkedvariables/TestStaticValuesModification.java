package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_COMPONENTS_PARAMETER;
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

        TypeInfo R = X.findSubType("R");
        FieldInfo rSet = R.getFieldByName("set", true);

        {
            MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
            ParameterInfo setAdd0 = setAdd.parameters().get(0);
            assertTrue(setAdd0.isModified());
            {
                Statement s0 = setAdd.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0r = vd0.variableInfo(setAdd0);
                assertTrue(vi0r.isModified());
                assertEquals("", vi0r.linkedVariables().toString());
                assertEquals("a.b.X.R.i#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                        vd0.knownVariableNamesToString());
                VariableInfo vi0Set = vd0.variableInfo("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r");
                assertEquals("", vi0Set.linkedVariables().toString());
                VariableInfo vi0i = vd0.variableInfo("a.b.X.R.i#a.b.X.setAdd(a.b.X.R):0:r");
                assertEquals("", vi0i.linkedVariables().toString());
            }
            assertTrue(setAdd0.analysis().haveAnalyzedValueFor(PropertyImpl.INDEPENDENT_PARAMETER));
            // there is no link between R and "this"
            assertTrue(setAdd0.analysis().getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER,
                    ValueImpl.IndependentImpl.DEPENDENT).isAtLeastIndependentHc());

            VariableData vdSetAdd0 = VariableDataImpl.of(setAdd.methodBody().lastStatement());
            VariableInfo viSetAdd0R = vdSetAdd0.variableInfo(setAdd0);
            assertTrue(viSetAdd0R.isModified());
            assertEquals("a.b.X.R.i#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                    vdSetAdd0.knownVariableNamesToString());
            VariableInfo viRSet = vdSetAdd0.variableInfo("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r");
            assertTrue(viRSet.isModified());

            Value.VariableBooleanMap modificationMap = setAdd0.analysis().getOrNull(MODIFIED_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.class);
            FieldReference frSet = runtime.newFieldReference(rSet, runtime.newVariableExpression(setAdd0), rSet.type());
            assertNotNull(modificationMap);
            assertNotNull(modificationMap.map());
            assertEquals(1, modificationMap.map().size());
            boolean fieldSetHasBeenModified = modificationMap.map().get(frSet);
            assertTrue(fieldSetHasBeenModified);
        }
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

            VariableInfo vd3L = vd3.variableInfo("l");
            assertEquals("*M-2-FM:r", vd3L.linkedVariables().toString());
            assertFalse(vd3L.isModified());

            VariableInfo vd3S = vd3.variableInfo("s");
            assertEquals("*M-2-FM:r", vd3S.linkedVariables().toString());
            assertTrue(vd3S.isModified());
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

        MethodInfo method = X.findUniqueMethod("method", 0);
        Statement s3 = method.methodBody().statements().get(3);
        VariableData vd3 = VariableDataImpl.of(s3);
        VariableInfo vi3R = vd3.variableInfo("r");
        assertEquals("Type a.b.X.RI E=new RI(s,3,l) this.i=3, this.list=l, this.set=s", vi3R.staticValues().toString());
        assertEquals("FM-2-*M:l,FM-2-*M:s", vi3R.linkedVariables().toString());
        assertTrue(vi3R.isModified());

        VariableInfo vd3S = vd3.variableInfo("s");
        assertEquals("*M-2-FM:r", vd3S.linkedVariables().toString());
        assertTrue(vd3S.isModified());
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


    // this test mimics the "get/set" and "variables" system in Loop and Try
    // we pack a variable in an array, clearly marked with @GetSet, and expect that modifications are propagated
    // through this packing.
    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import org.e2immu.annotation.method.GetSet;
            import java.util.Set;
            class X {
                interface R {
                    @GetSet("objects") Object get(int i);
                    @GetSet("objects") R set(int i, Object o);
                }
                record RI(Object[] objects) implements R {
                    Object get(int i) { return objects[i]; }
                    R set(int i, Object o) { objects[i] = o; return this; }
                }
            
                void modify(R r, int index) {
                    Set<Integer> set = (Set<Integer>) r.get(index);
                    set.add(3);
                }
            
                void method(Set<Integer> set) {
                    Object[] objects = new Object[2];
                    RI r = new RI(objects).set(0, set);
                    modify(r, 0); // this action should see 'set' modified
                }
            
                void method2(Set<Integer> set) {
                    Object[] objects = new Object[2];
                    R r = new RI(objects);
                    r.set(0, set); // via interface
                    modify(r, 0); // this action should see 'set' modified
                }
            }
            """;

    @DisplayName("modification of an array component element")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        TypeInfo R = X.findSubType("R");
        FieldInfo objectsR = R.getFieldByName("objects", true);
        {
            assertTrue(objectsR.isSynthetic());
            MethodInfo get = R.findUniqueMethod("get", 1);
            assertSame(objectsR, get.getSetField().field());
            MethodInfo set = R.findUniqueMethod("set", 2);
            assertSame(objectsR, set.getSetField().field());
        }
        {
            TypeInfo RI = X.findSubType("RI");
            FieldInfo objectsRI = RI.getFieldByName("objects", true);
            assertFalse(objectsRI.isSynthetic());
            MethodInfo get = RI.findUniqueMethod("get", 1);
            // test the inheritance of @GetSet
            assertSame(objectsRI, get.getSetField().field());
            MethodInfo set = RI.findUniqueMethod("set", 2);
            assertSame(objectsRI, set.getSetField().field());

            {
                Statement s0 = set.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo viObjectsI = vd0.variableInfo("a.b.X.RI.objects[a.b.X.RI.set(int,Object):0:i]");
                assertEquals("E=o", viObjectsI.staticValues().toString());
                VariableInfo viObjects = vd0.variableInfo("a.b.X.RI.objects");
                assertEquals("this[i]=o", viObjects.staticValues().toString()); // seems a bit weird
                VariableInfo viThis = vd0.variableInfo(runtime.newThis(RI));
                assertEquals("objects[i]=o", viThis.staticValues().toString());
            }
            StaticValues setSv = set.analysis().getOrNull(StaticValuesImpl.STATIC_VALUES_METHOD, StaticValuesImpl.class);
            assertEquals("E=this objects[i]=o", setSv.toString());
        }

        MethodInfo method = X.findUniqueMethod("method", 1);
        ParameterInfo method0 = method.parameters().get(0);
        {
            Statement s1 = method.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1Ri = vd1.variableInfo("r");
            assertEquals("E=new RI(objects) objects[0]=set", vi1Ri.staticValues().toString());
        }

        MethodInfo modify = X.findUniqueMethod("modify", 2);
        ParameterInfo modify0 = modify.parameters().get(0);
        {
            Statement s0 = modify.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0Set = vd0.variableInfo("set");
            assertEquals("*-4-F:r", vi0Set.linkedVariables().toString()); // 4 because Object is immutable HC
            assertEquals("E=r.objects", vi0Set.staticValues().toString()); // this is the result of @GetSet
        }
        {
            Statement s1 = modify.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1Set = vd1.variableInfo("set");
            assertTrue(vi1Set.isModified());
            assertEquals("*-4-F:r", vi1Set.linkedVariables().toString());
            assertEquals("E=r.objects", vi1Set.staticValues().toString()); // this is the result of @GetSet
        }
        assertFalse(modify0.isModified()); // R.objects is Immutable HC, but we have a modification on the component, via the cast
        assertEquals("", method0.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER, ValueImpl.VariableBooleanMapImpl.EMPTY).map().toString());
        assertTrue(method0.isModified());
    }

}