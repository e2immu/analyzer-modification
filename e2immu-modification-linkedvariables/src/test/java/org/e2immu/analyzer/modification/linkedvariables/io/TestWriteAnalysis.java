package org.e2immu.analyzer.modification.linkedvariables.io;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.LinkedVariablesCodec;
import org.e2immu.analyzer.shallow.analyzer.WriteAnalysis;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.util.internal.util.Trie;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestWriteAnalysis extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public class X {
                private int n;
                private int i;
                public X(int n) { this.n = n; }
                int getI() { return i; }
                int getN() { return n; }
            }
            """;

    @Language("java")
    private static final String OUTPUT1 = """
            package a.b;
            import org.e2immu.annotation.Final;
            import org.e2immu.annotation.Immutable;
            @Immutable(hc = true)
            public class X {
                @Final private int n;
                @Final private int i;
                public X(int n) { this.n = n; }
                int getI() { return i; }
                int getN() { return n; }
            }
            """;

    @Language("json")
    private static final String JSON1 = """
            [
            {"name": "Ta.b.X", "data":{"hc":{"E":true},"immutableType":2,"partOfConstructionType":["C<init>(0)"]}, "subs":[
             {"name": "Fn(0)", "data":{"finalField":1,"staticValuesField":["",["variableExpression",["P",["Ta.b.X","C<init>(0)","Pn(0)"]]],[]]}},
             {"name": "Fi(1)", "data":{"finalField":1}},
             {"name": "C<init>(0)", "data":{"getSetField":["Fn(0)",true],"hc":{"0":"Tjava.lang.Object"},"independentMethod":2,"staticValuesMethod":["",[],[["F",["Ta.b.X","Fn(0)"]],["variableExpression",["P",["Ta.b.X","C<init>(0)","Pn(0)"]]]]]}, "sub":
              {"name": "Pn(0)", "data":{"independentParameter":2,"parameterAssignedToField":["Fn(0)"],"staticValuesParameter":["",["variableExpression",["F",["Ta.b.X","Fn(0)"]]],[]]}}},
             {"name": "MgetI(0)", "data":{"getSetField":["Fi(1)",false],"independentMethod":2,"staticValuesMethod":["",["variableExpression",["F",["Ta.b.X","Fi(1)"]]],[]]}},
             {"name": "MgetN(1)", "data":{"getSetField":["Fn(0)",false],"independentMethod":2,"staticValuesMethod":["",["variableExpression",["F",["Ta.b.X","Fn(0)"]]],[]]}}]}
            ]
            """;

    @DisplayName("basics")
    @Test
    public void test1() throws IOException {
        test(INPUT1, OUTPUT1, JSON1);
    }


    @Language("java")
    private static final String INPUT2 = """
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

    @Language("java")
    private static final String OUTPUT2 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Set;
            import org.e2immu.annotation.Immutable;
            import org.e2immu.annotation.Modified;
            @Immutable(hc = true)
            class X {
                record R(Set<Integer> set, int i, List<String> list) {
                    private final Set<Integer> set;
                    private final int i;
                    private final List<String> list;
                }
                void setAdd(@Modified X.R r) { r.set.add(r.i); }
                void method() {
                    List<String> l = new ArrayList<>();
                    Set<Integer> s = new HashSet<>();
                    X.R r = new X.R(s, 3, l);
                    setAdd(r);
                }
            }
            """;

    @Language("json")
    private static final String JSON2 = """
            [
            {"name": "Ta.b.X", "data":{"hc":{"E":true},"immutableType":2,"partOfConstructionType":["C<init>(0)"]}, "subs":[
             {"name": "SR(0)", "data":{"hc":{"0":"Tjava.util.Set","1":"Tjava.util.List","M":2},"immutableType":1,"partOfConstructionType":["C<init>(0)"]}, "subs":[
              {"name": "Fset(0)", "data":{"finalField":1,"staticValuesField":["",["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Pset(0)"]]],[]]}},
              {"name": "Fi(1)", "data":{"finalField":1,"staticValuesField":["",["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Pi(1)"]]],[]]}},
              {"name": "Flist(2)", "data":{"finalField":1,"staticValuesField":["",["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Plist(2)"]]],[]]}},
              {"name": "C<init>(0)", "data":{"hc":{"2":"Tjava.lang.Object","M":2},"hcsMethod":{"0":[[0]],"1":[[1]]},"independentMethod":2,"staticValuesMethod":["",[],[["F",["Ta.b.X","SR(0)","Fi(1)"]],["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Pi(1)"]]],["F",["Ta.b.X","SR(0)","Flist(2)"]],["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Plist(2)"]]],["F",["Ta.b.X","SR(0)","Fset(0)"]],["variableExpression",["P",["Ta.b.X","SR(0)","C<init>(0)","Pset(0)"]]]]]}, "subs":[
               {"name": "Pset(0)", "data":{"hcsParameter":{"0":[[-1]]},"parameterAssignedToField":["Fset(0)"],"staticValuesParameter":["",["variableExpression",["F",["Ta.b.X","SR(0)","Fset(0)"]]],[]]}},
               {"name": "Pi(1)", "data":{"independentParameter":2,"parameterAssignedToField":["Fi(1)"],"staticValuesParameter":["",["variableExpression",["F",["Ta.b.X","SR(0)","Fi(1)"]]],[]]}},
               {"name": "Plist(2)", "data":{"hcsParameter":{"1":[[-1]]},"parameterAssignedToField":["Flist(2)"],"staticValuesParameter":["",["variableExpression",["F",["Ta.b.X","SR(0)","Flist(2)"]]],[]]}}]},
              {"name": "Mset(0)", "data":{"getSetField":["Fset(0)",false],"hcsMethod":{"0":[[-1]]},"staticValuesMethod":["",["variableExpression",["F",["Ta.b.X","SR(0)","Fset(0)"]]],[]]}},
              {"name": "Mi(1)", "data":{"getSetField":["Fi(1)",false],"independentMethod":2,"staticValuesMethod":["",["variableExpression",["F",["Ta.b.X","SR(0)","Fi(1)"]]],[]]}},
              {"name": "Mlist(2)", "data":{"getSetField":["Flist(2)",false],"hcsMethod":{"1":[[-1]]},"staticValuesMethod":["",["variableExpression",["F",["Ta.b.X","SR(0)","Flist(2)"]]],[]]}}]},
             {"name": "C<init>(0)", "data":{"hc":{"0":"Tjava.lang.Object"},"independentMethod":2}},
             {"name": "MsetAdd(0)", "data":{"hc":{"0":"Ta.b.X.R"},"independentMethod":2}, "sub":
              {"name": "Pr(0)", "data":{"hcsParameter":{"0":[[-1]]},"independentParameter":2,"modifiedComponentsParameter":[["F",["Ta.b.X","SR(0)","Fset(0)"]],true],"modifiedParameter":1}}},
             {"name": "Mmethod(1)", "data":{"independentMethod":2}}]}
            ]
            """;

    @DisplayName("analyzer info")
    @Test
    public void test2() throws IOException {
        test(INPUT2, OUTPUT2, JSON2);
    }

    private void test(String input, String output, String json) throws IOException {
        TypeInfo X = javaInspector.parse(input);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        String s = printType(X);
        assertEquals(output, s);
        Trie<TypeInfo> typeTrie = new Trie<>();
        typeTrie.add(X.fullyQualifiedName().split("\\."), X);
        WriteAnalysis writeAnalysis = new WriteAnalysis(runtime);
        File dest = new File("build/json");
        dest.mkdirs();
        Codec codec = new LinkedVariablesCodec(runtime).codec();
        writeAnalysis.write(dest, typeTrie, codec);
        String written = Files.readString(new File(dest, "ABX.json").toPath());
        assertEquals(json, written);
    }
}
