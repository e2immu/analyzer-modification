package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestReadFieldReferences extends CommonTest {

    @Language("java")
    public static String INPUT = """
            package a.b;
            import java.util.stream.IntStream;
            import org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop;
            
            class X {
                private double[][] nxmCosTableX = null;
                private double[][] nxmCosTableY = null;
                private int N = 0;
                private int M = 0;
                private int pixelRange(int p) { return ((p > 255) ? 255 :(p < 0) ? 0 : p); }
                
                public void method(double[][] dcts, int[][] pixels) {
                    int x = 0;
                    int y = 0;
                    int i = 0;
                    int j = 0;
                    double t = 0.0;
                    double cx0 = Math.sqrt(1.0 / this.N);
                    double cy0 = Math.sqrt(1.0 / this.M);
                    for(x = 0; x < this.N; x++ ) {
                        for(y = 0; y < this.M; y++ ) {
                            t = cx0 * cy0 * dcts[0][0];
                            Loop.LoopData ldIn = new Loop.LoopDataImpl.Builder()
                                .set(0, cy0)
                                .set(1, dcts)
                                .set(2, t)
                                .set(3, x)
                                .iterator(IntStream.iterate(1, i0 -> i0 < this.N, i0 -> i0 + 1).iterator())
                                .build();
                            Loop.LoopData ld = Loop.run(ldIn);
                            t = (double)ld.get(2);
                            Loop.LoopData ldIn0 = new Loop.LoopDataImpl.Builder()
                                .set(0, cx0)
                                .set(1, dcts)
                                .set(2, t)
                                .set(3, y)
                                .iterator(IntStream.iterate(1, j0 -> j0 < this.M, j0 -> j0 + 1).iterator())
                                .build();
                            Loop.LoopData ld0 = Loop.run(ldIn0);
                            t = (double)ld0.get(2);
                            for(i = 1; i < this.N; i++ ) {
                                Loop.LoopData ldIn1 = new Loop.LoopDataImpl.Builder()
                                    .set(0, dcts)
                                    .set(1, i)
                                    .set(2, t)
                                    .set(3, x)
                                    .set(4, y)
                                    .iterator(IntStream.iterate(1, j1 -> j1 < this.M, j1 -> j1 + 1).iterator())
                                    .build();
                                Loop.LoopData ld1 = Loop.run(ldIn1);
                                t = (double)ld1.get(2);
                            }
                            pixels[x][y] = this.pixelRange((int)(t + 128.5));
                        }
                    }
                }
            }
            """;

    @Test
    public void test1() throws IOException {
        String loopFile = "./src/test/java/org/e2immu/analyzer/modification/linkedvariables/staticvalues/Loop.java";
        String loopJava = Files.readString(Path.of(loopFile));
        {
            TypeInfo loop = javaInspector.parse(loopJava);
            List<Info> analysisOrder = prepWork(loop);
            analyzer.doPrimaryType(loop, analysisOrder);
        }
        {
            TypeInfo X = javaInspector.parse(INPUT);
            List<Info> analysisOrder = prepWork(X);
            assertEquals("""
                    [a.b.X.$12.applyAsInt(int), a.b.X.$4.applyAsInt(int), a.b.X.$8.applyAsInt(int), a.b.X.<init>(), \
                    a.b.X.nxmCosTableX, a.b.X.nxmCosTableY, a.b.X.pixelRange(int), a.b.X.$11.test(int), \
                    a.b.X.$3.test(int), a.b.X.$7.test(int), a.b.X.M, a.b.X.N, a.b.X.method(double[][],int[][]), \
                    a.b.X, a.b.X.$11, a.b.X.$12, a.b.X.$3, a.b.X.$4, a.b.X.$7, a.b.X.$8]\
                    """, analysisOrder.toString());
            analyzer.doPrimaryType(X, analysisOrder);
        }
    }
}
