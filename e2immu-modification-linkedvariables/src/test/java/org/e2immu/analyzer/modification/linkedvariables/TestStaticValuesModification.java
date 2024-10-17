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
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.NONE;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_METHOD;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_COMPONENTS_METHOD;
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
            assertEquals("r.set=true", modificationMap.toString());
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
            assertEquals("*M-2-0M|*-0:r", vd2S.linkedVariables().toString());
            assertEquals("Type java.util.HashSet<Integer> E=new HashSet<>()", vd2S.staticValues().toString());

            VariableInfo vd2L = vd2.variableInfo("l");
            assertEquals("*M-2-1M|*-2:r", vd2L.linkedVariables().toString());
            assertEquals("Type java.util.ArrayList<String> E=new ArrayList<>()", vd2L.staticValues().toString());
            assertFalse(vd2L.isModified());

            VariableInfo vd2R = vd2.variableInfo("r");
            assertFalse(vd2R.isModified());
            assertEquals("1M-2-*M|2-*:l, 0M-2-*M|0-*:s", vd2R.linkedVariables().toString());
            assertEquals("Type a.b.X.R E=new R(s,3,l) this.i=3, this.list=l, this.set=s", vd2R.staticValues().toString());
        }
        {
            Statement s3 = method.methodBody().statements().get(3);
            VariableData vd3 = VariableDataImpl.of(s3);
            VariableInfo vi3R = vd3.variableInfo("r");
            assertEquals("Type a.b.X.R E=new R(s,3,l) this.i=3, this.list=l, this.set=s", vi3R.staticValues().toString());
            assertEquals("1M-2-*M|2-*:l, 0M-2-*M|0-*:s", vi3R.linkedVariables().toString());
            assertTrue(vi3R.isModified());

            VariableInfo vd3L = vd3.variableInfo("l");
            assertEquals("*M-2-1M|*-2:r", vd3L.linkedVariables().toString());
            assertFalse(vd3L.isModified());

            VariableInfo vd3S = vd3.variableInfo("s");
            assertEquals("*M-2-0M|*-0:r", vd3S.linkedVariables().toString());
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
                    setAdd(r); // at this point, s should have been modified, via???
                }
            }
            """;

    @DisplayName("modification of a single component, interface in between")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo R = X.findSubType("R");
        MethodInfo rSet = R.findUniqueMethod("set", 0);
        assertEquals("E=this.set", rSet.analysis().getOrDefault(STATIC_VALUES_METHOD, NONE).toString());

        MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
        ParameterInfo setAdd0 = setAdd.parameters().get(0);
        {
            Statement s0 = setAdd.methodBody().statements().get(0);
            VariableData vdSetAdd0 = VariableDataImpl.of(s0);
            VariableInfo viSetAdd0R = vdSetAdd0.variableInfo(setAdd0);
            assertTrue(viSetAdd0R.isModified());
            assertEquals("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                    vdSetAdd0.knownVariableNamesToString());
        }
        assertTrue(setAdd0.isModified());
        assertEquals("r.set=true", setAdd0.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER,
                ValueImpl.VariableBooleanMapImpl.EMPTY).toString());

        MethodInfo method = X.findUniqueMethod("method", 0);
        Statement s3 = method.methodBody().statements().get(3);
        VariableData vd3 = VariableDataImpl.of(s3);
        VariableInfo vi3R = vd3.variableInfo("r");
        assertEquals("Type a.b.X.RI E=new RI(s,3,l) this.i=3, this.list=l, this.set=s", vi3R.staticValues().toString());
        assertEquals("1M-2-*M|2-*:l, 0M-2-*M|0-*:s", vi3R.linkedVariables().toString());
        assertTrue(vi3R.isModified());

        VariableInfo vd3S = vd3.variableInfo("s");
        assertEquals("*M-2-0M|*-0:r", vd3S.linkedVariables().toString());
        assertTrue(vd3S.isModified());
    }


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import org.e2immu.annotation.Fluent;
            import org.e2immu.annotation.Modified;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.List;
            class X {
                interface R {
                    @Modified("set") //corresponds to LoopData.hasNext()
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
        TypeInfo R = X.findSubType("R");
        TypeInfo RI = X.findSubType("RI");
        {
            // computed
            MethodInfo add = RI.findUniqueMethod("add", 0);
            assertTrue(add.isModifying());
            assertEquals("this.set=true", add.analysis().getOrDefault(MODIFIED_COMPONENTS_METHOD,
                    ValueImpl.VariableBooleanMapImpl.EMPTY).toString());
        }
        {
            // from annotations
            MethodInfo add = R.findUniqueMethod("add", 0);
            assertTrue(add.isModifying());
            Value.VariableBooleanMap map = add.analysis().getOrDefault(MODIFIED_COMPONENTS_METHOD,
                    ValueImpl.VariableBooleanMapImpl.EMPTY);
            assertEquals("this.set=true", map.toString());
            FieldInfo set = R.getFieldByName("set", true);

            assertTrue(set.isSynthetic());
            Variable variable = map.map().keySet().stream().findFirst().orElseThrow();
            if (variable instanceof FieldReference fr) {
                assertSame(set, fr.fieldInfo());
            } else fail();
        }
        {
            // propagated to the parameter
            MethodInfo setAdd = X.findUniqueMethod("setAdd", 1);
            ParameterInfo setAdd0 = setAdd.parameters().get(0);
            {
                Statement s0 = setAdd.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0r = vd0.variableInfo(setAdd0);
                assertTrue(vi0r.isModified());
                // we expect the r.set variable to exist (see MethodAnalyzer.Visitor.beforeExpression,MC.)
                assertEquals("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r, a.b.X.setAdd(a.b.X.R):0:r",
                        vd0.knownVariableNamesToString());
                // we expect to see a modification on r.set (via ExpressionAnalyzer,markModified)
                VariableInfo vi0rSet = vd0.variableInfo("a.b.X.R.set#a.b.X.setAdd(a.b.X.R):0:r");
                assertTrue(vi0rSet.isModified());
            }
            assertTrue(setAdd0.isModified());
            assertEquals("r.set=true", setAdd0.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.EMPTY).toString());
        }
        {
            MethodInfo method = X.findUniqueMethod("method", 0);
            Statement s2 = method.methodBody().statements().get(2);
            VariableData vd2 = VariableDataImpl.of(s2);
            VariableInfo vi2B = vd2.variableInfo("b");
            assertEquals("E=new Builder() this.intSet=s, this.stringList=l", vi2B.staticValues().toString());

            {   // setAdd(r)  it should modify r.set
                Statement s4 = method.methodBody().statements().get(4);
                VariableData vd4 = VariableDataImpl.of(s4);
                assertEquals("a.b.X.this, b, l, r, s", vd4.knownVariableNamesToString());

                VariableInfo vi4R = vd4.variableInfo("r");
                assertTrue(vi4R.isModified());
                // note that we have the fields in RI here, not those of the builder! Code is dedicated to builder-like
                // methods, ExpressionAnalyzer.checkCaseForBuilder

                assertEquals("Type a.b.X.RI this.list=l, this.set=s", vi4R.staticValues().toString());
                Variable v0 = vi4R.staticValues().values().keySet().stream().findFirst().orElseThrow();
                if (v0 instanceof FieldReference fr) {
                    assertEquals("Type a.b.X.RI", fr.scopeVariable().parameterizedType().toString());
                } else fail();

                // IMPORTANT: we are relying on the name "set" to be the same in @Modified("set") and the field name in RI
                // we need the correspondence between R and RI!!
                // the lifting occurs in: ...

                // check that the correct component is modified!
                VariableInfo vi4s = vd4.variableInfo("s");
                assertTrue(vi4s.isModified());

                VariableInfo vi4l = vd4.variableInfo("l");
                assertFalse(vi4l.isModified());
            }

        }
    }


    // this test mimics the "get/set" and "variables" system in Loop and Try
    // we pack a variable in an array, clearly marked with @GetSet, and expect that modifications are propagated
    // through this packing.
    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import org.e2immu.annotation.Fluent;
            import org.e2immu.annotation.method.GetSet;
            import java.util.Set;
            class X {
                interface R {
                    @GetSet("objects") Object get(int i);
                    @Fluent @GetSet("objects") R set(int i, Object o);
                }
                record RI(Object[] objects) implements R {
                    Object get(int i) { return objects[i]; }
                    R set(int i, Object o) { objects[i] = o; return this; }
                }
            
                void modify(RI ri, int index) {
                    Set<Integer> set = (Set<Integer>) ri.get(index);
                    set.add(3);
                }
            
                void method(Set<Integer> set) {
                    Object[] objects = new Object[2];
                    RI r = new RI(objects).set(0, set);
                    modify(r, 0); // this action should see 'set' modified
                }
            
                void modify2(R r, int index) {
                    Set<Integer> set = (Set<Integer>) r.get(index);
                    set.add(3);
                }
            
                void method2(Set<Integer> set) {
                    Object[] objects = new Object[2];
                    R r = new RI(objects);
                    r.set(0, set); // via interface
                    modify2(r, 0); // this action should see 'set' modified
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
            StaticValues setSv = set.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
            // this sv is synthetically created from the @GetSet annotation
            assertEquals("E=this objects[i]=o", setSv.toString());
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
                VariableInfo viThis = vd0.variableInfo(runtime.newThis(RI.asParameterizedType()));
                assertEquals("objects[i]=o", viThis.staticValues().toString());
            }
            StaticValues setSv = set.analysis().getOrNull(STATIC_VALUES_METHOD, StaticValuesImpl.class);
            assertEquals("E=this objects[i]=o", setSv.toString());
        }

        // first, we do the 'modify', 'method' combination, which requires fewer mechanics than 'modify2', 'method2'
        // which goes via the interfaces.
        {
            MethodInfo modify = X.findUniqueMethod("modify", 2);
            ParameterInfo modify0 = modify.parameters().get(0);
            {
                Statement s0 = modify.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0Set = vd0.variableInfo("set");
                assertEquals("*-4-0:ri", vi0Set.linkedVariables().toString()); // 4 because Object is immutable HC
                assertEquals("E=ri.objects[index]", vi0Set.staticValues().toString()); // this is the result of @GetSet
            }
            {
                Statement s1 = modify.methodBody().statements().get(1);
                VariableData vd1 = VariableDataImpl.of(s1);
                VariableInfo vi1Set = vd1.variableInfo("set");
                assertTrue(vi1Set.isModified());
                assertEquals("*-4-0:ri", vi1Set.linkedVariables().toString());
                assertEquals("E=ri.objects[index]", vi1Set.staticValues().toString()); // this is the result of @GetSet
            }
            assertFalse(modify0.isModified()); // R.objects is Immutable HC, but we have a modification on the component, via the cast
            assertEquals("{ri.objects[index]=true}", modify0.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.EMPTY).map().toString());
        }
        {
            MethodInfo method = X.findUniqueMethod("method", 1);
            ParameterInfo method0 = method.parameters().get(0);

            Statement s1 = method.methodBody().statements().get(1);
            VariableData vd1 = VariableDataImpl.of(s1);
            VariableInfo vi1Ri = vd1.variableInfo("r");
            assertEquals("E=new RI(objects) objects[0]=set", vi1Ri.staticValues().toString());
            assertTrue(method0.isModified());
        }

        // so now modify2
        {
            MethodInfo modify2 = X.findUniqueMethod("modify2", 2);
            ParameterInfo modify0 = modify2.parameters().get(0);
            {
                Statement s0 = modify2.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0Set = vd0.variableInfo("set");
                assertEquals("*-4-0:r", vi0Set.linkedVariables().toString()); // 4 because Object is immutable HC
                assertEquals("E=r.objects[index]", vi0Set.staticValues().toString()); // this is the result of @GetSet
            }
            {
                Statement s1 = modify2.methodBody().statements().get(1);
                VariableData vd1 = VariableDataImpl.of(s1);
                VariableInfo vi1Set = vd1.variableInfo("set");
                assertTrue(vi1Set.isModified());
                assertEquals("*-4-0:r", vi1Set.linkedVariables().toString());
                assertEquals("E=r.objects[index]", vi1Set.staticValues().toString()); // this is the result of @GetSet
            }
            assertFalse(modify0.isModified()); // R.objects is Immutable HC, but we have a modification on the component, via the cast
            assertEquals("{r.objects[index]=true}", modify0.analysis().getOrDefault(MODIFIED_COMPONENTS_PARAMETER,
                    ValueImpl.VariableBooleanMapImpl.EMPTY).map().toString());
        }
        {
            MethodInfo method2 = X.findUniqueMethod("method2", 1);
            ParameterInfo method0 = method2.parameters().get(0);
            { // R r = new RI(objects)
                Statement s1 = method2.methodBody().statements().get(1);
                VariableData vd1 = VariableDataImpl.of(s1);
                VariableInfo vi1r = vd1.variableInfo("r");
                assertEquals("Type a.b.X.RI E=new RI(objects) this.objects=objects", vi1r.staticValues().toString());
            }
            { // r.set(0, set)
                Statement s2 = method2.methodBody().statements().get(2);
                VariableData vd2 = VariableDataImpl.of(s2);

                VariableInfo vi2set = vd2.variableInfo(method0);
                assertFalse(vi2set.isModified());

                VariableInfo vi2r = vd2.variableInfo("r");
                assertEquals("Type a.b.X.RI E=r objects[0]=set, this.objects=objects", vi2r.staticValues().toString());
            }
            { // modify2(r, 0)
                Statement s3 = method2.methodBody().statements().get(3);
                VariableData vd3 = VariableDataImpl.of(s3);

                // modification has been propagated via the parameter
                VariableInfo vi3set = vd3.variableInfo(method0);
                assertTrue(vi3set.isModified());
            }
            assertTrue(method0.isModified());
        }
    }

}