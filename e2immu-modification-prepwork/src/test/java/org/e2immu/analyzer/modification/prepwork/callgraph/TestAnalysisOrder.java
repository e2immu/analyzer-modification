package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.util.internal.graph.G;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestAnalysisOrder extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import org.e2immu.annotation.Modified;
            class X {
                interface I {}
            
                interface J extends Comparable<J> {}
            
                // modifying
                interface K {
                    @Modified
                    void add(String s);
                }
            
                // modifying, because K is
                interface KK extends K {
                    int get();
                }
            
                interface L {
                    int get();
                }
            
                //modifying (implicit in abstract void methods)
                interface M extends L {
                    void set(int i);
                }
            
                class Nested {
                    int n;
                }
            }
            """;

    @DisplayName("interfaces")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT1);
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, X);
        G<Info> graph = ccg.go().graph();
        assertEquals("""
                a.b.X->1->a.b.X.<init>(), a.b.X.K->1->a.b.X.K.add(String), \
                a.b.X.KK->1->a.b.X.K, a.b.X.KK->1->a.b.X.KK.get(), a.b.X.L->1->a.b.X.L.get(), \
                a.b.X.M->1->a.b.X.L, a.b.X.M->1->a.b.X.M.set(int), a.b.X.Nested->1->a.b.X, \
                a.b.X.Nested->1->a.b.X.Nested.<init>(), a.b.X.Nested->1->a.b.X.Nested.n\
                """, graph.toString());
        List<Info> analysisOrder = new ComputeAnalysisOrder().go(graph);
        assertEquals("""
                <init>, I, J, add, get, get, set, <init>, n, X, K, L, KK, M, Nested\
                """, analysisOrder.stream().map(Info::simpleName).collect(Collectors.joining(", ")));
    }

}
