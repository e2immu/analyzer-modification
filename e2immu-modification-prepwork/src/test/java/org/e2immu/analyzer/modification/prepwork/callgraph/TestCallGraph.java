package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.util.internal.graph.G;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestCallGraph extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                int unused;
                int j = square(10);
                void m1() {
                    m2();
                }
                void m2() {
                    j++;
                }
                static int square(int i) {
                    return i*i;
                }
                void recursive(int k) {
                    if(k <= 0) {
                        System.out.println("done");
                    } else {
                        System.out.println("k = "+k);
                        recursive(k-1);
                    }
                }
            }
            """;

    @DisplayName("basics of call graph")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, X);
        G<Info> graph = ccg.go().graph();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X->1->a.b.X.j, a.b.X->1->a.b.X.m1(), a.b.X->1->a.b.X.m2(), \
                a.b.X->1->a.b.X.recursive(int), a.b.X->1->a.b.X.square(int), a.b.X->1->a.b.X.unused, \
                a.b.X.j->1->a.b.X.square(int), a.b.X.j->2->a.b.X.m2(), a.b.X.m1()->1->a.b.X.m2()\
                """, graph.toString());

        FieldInfo unused = X.getFieldByName("unused", true);
        assertNotNull(graph.vertex(unused));

        MethodInfo methodInfo = X.findUniqueMethod("recursive", 1);
        assertTrue(ccg.recursiveMethods().contains(methodInfo));

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        List<Info> analysisOrder = cao.go(graph);
        assertEquals("""
                [a.b.X.<init>(), a.b.X.m2(), a.b.X.recursive(int), a.b.X.square(int), a.b.X.unused, a.b.X.j, a.b.X.m1(), a.b.X]\
                """, analysisOrder.toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.List;class X {
                static void method(List<String> list) {
                    list.forEach(s -> {
                        if(list.isEmpty()) {
                            System.out.println("empty");
                        } else {
                            System.out.println("s = "+s);
                            method(list.subList(0, list.size()-1));
                        }
                    });
                }
            }
            """;

    @DisplayName("recursion in lambda")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, X);
        G<Info> graph = ccg.go().graph();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X->1->a.b.X.method(java.util.List<String>), a.b.X.$0->1->a.b.X.$0.accept(String), \
                a.b.X.$0->1->a.b.X.method(java.util.List<String>), a.b.X.method(java.util.List<String>)->1->a.b.X.$0.accept(String)\
                """, graph.toString());

        // NOTE: at the moment, both the lambda method and 'method' are marked recursive
        assertEquals("[a.b.X.$0.accept(String), a.b.X.method(java.util.List<String>)]",
                ccg.recursiveMethods().stream().map(MethodInfo::fullyQualifiedName).sorted().toList().toString());

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        List<Info> analysisOrder = cao.go(graph);
        assertEquals("""
                [a.b.X.$0.accept(String), a.b.X.<init>(), a.b.X.method(java.util.List<String>), a.b.X, a.b.X.$0]\
                """, analysisOrder.toString());
    }


    @Language("java")
    static final String INPUT3 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            
            class X {
                private List<String> list;
                X(int i) {
                    initList(i);
                    print();
                    sleep();
                }
                private void initList(int i) {
                    list = new ArrayList<>();
                    list.add(i);
                }
                private void sleep() {
                    list.clear();
                }
                void print() {
                    System.out.println("print!");
                }
                public void rest() {
                    sleep();
                    System.out.println("awake");
                }
            }
            """;

    @DisplayName("part of construction")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, X);
        G<Info> graph = ccg.go().graph();
        assertEquals("""
                a.b.X->1->a.b.X.X(int), a.b.X->1->a.b.X.initList(int), a.b.X->1->a.b.X.list, a.b.X->1->a.b.X.print(), \
                a.b.X->1->a.b.X.rest(), a.b.X->1->a.b.X.sleep(), a.b.X.X(int)->1->a.b.X.initList(int), \
                a.b.X.X(int)->1->a.b.X.print(), a.b.X.X(int)->1->a.b.X.sleep(), a.b.X.list->2->a.b.X.initList(int), \
                a.b.X.rest()->1->a.b.X.sleep()\
                """, graph.toString());

        assertTrue(ccg.recursiveMethods().isEmpty());

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        List<Info> analysisOrder = cao.go(graph);
        assertEquals("""
                [a.b.X.initList(int), a.b.X.print(), a.b.X.sleep(), a.b.X.X(int), a.b.X.list, a.b.X.rest(), a.b.X]\
                """, analysisOrder.toString());
    }


    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            class X {
                interface I { int i(); }
                record Y(int i) implements I {}
                record Z(Y y) {}
                Z z;
                Z getZ() { return z; }
            }
            """;

    @DisplayName("subtypes in call graph")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, X);
        G<Info> graph = ccg.go().graph();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X->1->a.b.X.getZ(), a.b.X->1->a.b.X.z, a.b.X.I->1->a.b.X.I.i(), \
                a.b.X.Y->1->a.b.X.I, a.b.X.Y->1->a.b.X.Y.<init>(int), a.b.X.Y->1->a.b.X.Y.i, a.b.X.Y->1->a.b.X.Y.i(), \
                a.b.X.Y.i->1->a.b.X.Y.i(), a.b.X.Y.i->2->a.b.X.Y.<init>(int), a.b.X.Z->1->a.b.X.Z.<init>(a.b.X.Y), \
                a.b.X.Z->1->a.b.X.Z.y, a.b.X.Z->1->a.b.X.Z.y(), a.b.X.Z.<init>(a.b.X.Y)->1->a.b.X.Y, \
                a.b.X.Z.y()->1->a.b.X.Y, a.b.X.Z.y->1->a.b.X.Y, a.b.X.Z.y->1->a.b.X.Z.y(), \
                a.b.X.Z.y->2->a.b.X.Z.<init>(a.b.X.Y), a.b.X.getZ()->1->a.b.X.Z, a.b.X.z->1->a.b.X.Z, \
                a.b.X.z->1->a.b.X.getZ()\
                """, graph.toString());

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        List<Info> analysisOrder = cao.go(graph);
        assertEquals("""
                [a.b.X.<init>(), a.b.X.I.i(), a.b.X.Y.<init>(int), a.b.X.Y.i(), a.b.X.I, a.b.X.Y.i, a.b.X.Y, \
                a.b.X.Z.<init>(a.b.X.Y), a.b.X.Z.y(), a.b.X.Z.y, a.b.X.Z, a.b.X.getZ(), a.b.X.z, a.b.X]\
                """, analysisOrder.toString());
    }
}
