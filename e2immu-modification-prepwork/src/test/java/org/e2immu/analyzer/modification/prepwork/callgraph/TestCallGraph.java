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
    }
}
