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


    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            abstract class X {
                final Object object = new Object();
                static String method1(String in) {
                   synchronized (object) {
                        System.out.println(in);
                        return in;
                    }
                }
            }
            """;

    @DisplayName("synchronized with return")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        {
            MethodInfo method = X.findUniqueMethod("method1", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
        }
    }



    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            abstract class X {
                final Object object = new Object();
                static String method1(String in) {
                   try {
                        if(in.isEmpty()) System.out.println("empty");
                        return in.toLowerCase();
                   } finally {
                        System.out.println(object);
                   }
                }
            }
            """;

    @DisplayName("try-finally")
    @Test
    public void test4() {
        TypeInfo X = javaInspector.parse(INPUT4);
        {
            MethodInfo method = X.findUniqueMethod("method1", 1);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
        }
    }


    @Language("java")
    private static final String INPUT5 = """
            import java.util.Date;
            import java.util.Random;
            import java.util.WeakHashMap;
            
            public class X {
              public static int method1() {
                do {
                  int random_num = randomNumberGenerator.nextInt();
                  if (random_num <= 0) continue;
                  synchronized (aceThreadList) {
                    if (aceThreadList.get(random_num) == null) {
                      return random_num;
                    }
                  }
                } while (true);
              }
            
              private static WeakHashMap aceThreadList = new WeakHashMap();
              private static Random randomNumberGenerator = new Random((new Date()).getTime());
            }
            """;

    @DisplayName("synchronized in infinite loop")
    @Test
    public void test5() {
        TypeInfo X = javaInspector.parse(INPUT5);
        {
            MethodInfo method = X.findUniqueMethod("method1", 0);
            ComputeAlwaysEscapes.go(method);
            Statement s0 = method.methodBody().statements().get(0);
            assertTrue(s0.alwaysEscapes());
            Statement s001 = s0.block().statements().get(1);
            assertFalse(s001.alwaysEscapes());
            Statement s002 = s0.block().statements().get(2);
            assertFalse(s002.alwaysEscapes());
        }
    }

}
