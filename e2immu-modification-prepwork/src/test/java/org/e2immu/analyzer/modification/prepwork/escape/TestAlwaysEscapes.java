package org.e2immu.analyzer.modification.prepwork.escape;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAlwaysEscapes extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                static int method1(String in) {
                    int i = in.length();
                    return i;
                }
                static int method2(String in) {
                    if(in.isEmpty()) {
                        return 1;
                    } else {
                        throw new UnsupportedOperationException();
                    }
                }
                static int method3(String in) {
                    if(in.isEmpty()) {
                        return 1;
                    } else {
                        System.out.println(in);
                    }
                }
            }
            """;

    @DisplayName("basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        {
            MethodInfo method = X.findUniqueMethod("method1", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertFalse(s0.alwaysEscapes());
            Statement s1 = method.methodBody().statements().get(1);
            assertTrue(s1.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method2", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            Statement s000 = s0.block().statements().get(0);
            assertTrue(s000.alwaysEscapes());
            Statement s010 = ((IfElseStatement) s0).elseBlock().statements().get(0);
            assertTrue(s010.alwaysEscapes());
            assertTrue(s0.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method3", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            Statement s000 = s0.block().statements().get(0);
            assertTrue(s000.alwaysEscapes());
            Statement s010 = ((IfElseStatement) s0).elseBlock().statements().get(0);
            assertFalse(s010.alwaysEscapes());
            assertFalse(s0.alwaysEscapes());
        }
    }


    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            abstract class X {
                static int method1(String in) {
                    for(int i=0; ; i++) { }
                }
                static int method2(String in) {
                    for(int i=0; ; i++) { break; }
                }
                static int method3(String in) {
                   for(int i=0; ; i++) { System.out.println("?"); return; }
                }
            
                abstract int read();
                abstract void write(int i);
                void method4() {
                    while (true) {
                        int read = read();
                        if (read == -1) {
                            break;
                        }
                        write(read);
                    }
                }
                void method5() {
                    while (true) {
                        int read = read();
                        if (read == -1) {
                            return;
                        }
                        write(read);
                    }
                }
            }
            """;

    @DisplayName("loops with condition 'true'")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        {
            MethodInfo method = X.findUniqueMethod("method1", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method2", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            Statement s000 = s0.block().statements().get(0);
            assertFalse(s000.alwaysEscapes());
            assertFalse(s0.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method3", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method4", 0);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            Statement s001 = s0.block().statements().get(1);
            assertFalse(s001.alwaysEscapes());
            assertFalse(s0.alwaysEscapes());
        }
        {
            MethodInfo method = X.findUniqueMethod("method5", 0);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
        }
    }

}
