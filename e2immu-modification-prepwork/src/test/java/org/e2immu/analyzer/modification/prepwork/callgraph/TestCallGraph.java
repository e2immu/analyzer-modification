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
        ComputeCallGraph ccg = new ComputeCallGraph(X);
        G<Info> graph = ccg.go();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X->1->a.b.X.j, a.b.X->1->a.b.X.m1(), a.b.X->1->a.b.X.m2(), a.b.X->1->a.b.X.recursive(int), a.b.X->1->a.b.X.square(int), a.b.X->1->a.b.X.unused, \
                a.b.X.j->1->a.b.X.square(int), a.b.X.m1()->1->a.b.X.m2()\
                """, graph.toString());

        FieldInfo unused = X.getFieldByName("unused", true);
        assertNotNull(graph.vertex(unused));

        MethodInfo methodInfo = X.findUniqueMethod("recursive", 1);
        assertTrue(ccg.recursiveMethods().contains(methodInfo));

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        AnalysisOrder ao = cao.go(graph, ccg.recursiveMethods());
        assertEquals("""
                [a.b.X.<init>():FTF, a.b.X.m2():FFF, a.b.X.recursive(int):FFT, a.b.X.square(int):FFF, a.b.X.unused:FFF, a.b.X.j:FFF, a.b.X.m1():FFF, a.b.X:FFF]\
                """, ao.toString());
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
        ComputeCallGraph ccg = new ComputeCallGraph(X);
        G<Info> graph = ccg.go();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X->1->a.b.X.method(java.util.List<String>), a.b.X.$1->1->a.b.X.$1.accept(String), \
                a.b.X.$1->1->a.b.X.method(java.util.List<String>)\
                """, graph.toString());

        // NOTE: at the moment, both the lambda method and 'method' are marked recursive
        assertEquals("[a.b.X.$1.accept(String), a.b.X.method(java.util.List<String>)]",
                ccg.recursiveMethods().stream().map(MethodInfo::fullyQualifiedName).sorted().toList().toString());

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        AnalysisOrder ao = cao.go(graph, ccg.recursiveMethods());
        assertEquals("""
                [a.b.X.$1.accept(String):FFT, a.b.X.<init>():FTF, a.b.X.method(java.util.List<String>):FFT, a.b.X:FFF, a.b.X.$1:FFF]\
                """, ao.toString());
    }


    @Language("java")
    private static final String INPUT3 = """
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
        ComputeCallGraph ccg = new ComputeCallGraph(X);
        G<Info> graph = ccg.go();
        assertEquals("""
                a.b.X->1->a.b.X.X(int), a.b.X->1->a.b.X.initList(int), a.b.X->1->a.b.X.list, a.b.X->1->a.b.X.print(), \
                a.b.X->1->a.b.X.rest(), a.b.X->1->a.b.X.sleep(), \
                a.b.X.X(int)->1->a.b.X.initList(int), a.b.X.X(int)->1->a.b.X.print(), a.b.X.X(int)->1->a.b.X.sleep(), \
                a.b.X.rest()->1->a.b.X.sleep()\
                """, graph.toString());

        assertTrue(ccg.recursiveMethods().isEmpty());

        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        AnalysisOrder ao = cao.go(graph, ccg.recursiveMethods());
        assertEquals("""
                [a.b.X.initList(int):FTF, a.b.X.list:FFF, a.b.X.print():FFF, a.b.X.sleep():FFF, a.b.X.X(int):FTF, a.b.X.rest():FFF, a.b.X:FFF]\
                """, ao.toString());
    }
}
