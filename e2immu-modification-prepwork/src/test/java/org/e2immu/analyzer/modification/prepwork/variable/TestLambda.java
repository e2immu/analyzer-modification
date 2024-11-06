package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.Lambda;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.ExpressionAsStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestLambda extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.stream.IntStream;
            public class X {
                void method(byte[] b) {
                    IntStream.iterate(0, i -> i < b.length, i -> i + 1);
                }
            }
            """;


    @DisplayName("lambda parameters")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);

        MethodInfo method = X.findUniqueMethod("method", 1);
        Statement s0 = method.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(s0);
        // we don't want the variables that have been created in the lambda. We do get b
        assertEquals("a.b.X.method(byte[]):0:b", vd0.knownVariableNamesToString());
        Lambda lambda = (Lambda) ((MethodCall) s0.expression()).parameterExpressions().get(1);
        assertEquals("a.b.X.$1.test(int)", lambda.methodInfo().fullyQualifiedName());
        Statement l0 = lambda.methodInfo().methodBody().statements().get(0);
        assertEquals("return i<b.length;", l0.print(runtime.qualificationFullyQualifiedNames()).toString());
        VariableData vdL0 = VariableDataImpl.of(l0);
        assertEquals("a.b.X.$1.test(int), a.b.X.$1.test(int):0:i, a.b.X.method(byte[]):0:b",
                vdL0.knownVariableNamesToString());
    }


    @Language("java")
    private static final String INPUT2 = """
            import java.io.*;
            import java.util.*;
            
            public class Function10927145_file1830095 {
            
                public static void method(final String extension, File currentDir, List<File> filesList) {
                    String[] files = currentDir.list(new FilenameFilter() {
            
                        public boolean accept(File dir, String name) {
                            File f = new File(dir, name);
                            return f.isDirectory() || name.endsWith(extension);
                        }
                    });
                    for (String filename : files) {
                        File f = new File(currentDir, filename);
                        if (f.isDirectory())
                            method(extension, f, filesList);
                        else
                            filesList.add(f);
                    }
                }
            }
            """;


    @DisplayName("created in lambda")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
    }

    @Language("java")
    private static final String INPUT3 = """
            import java.io.File;
            import java.net.URI;
            
            public class X {
            
                public void open(File file) throws Exception {
                    if (file != null) {
                        File fixedFile = new File(file.getAbsoluteFile().toString()) {
            
                            public URI toURI() {
                                try {
                                    return new URI("file://" + getAbsolutePath());
                                } catch (Exception e) {
                                    return super.toURI();
                                }
                            }
                        };
                    }
                }
            }
            """;

    @DisplayName("created in anonymous type, with parameters in constructor call")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);
        MethodInfo methodInfo = X.findUniqueMethod("open", 1);
        VariableData vd = VariableDataImpl.of(methodInfo.methodBody().statements().get(0).block().statements().get(0));
        // important: test 'file' parameter present
        assertEquals("X.open(java.io.File):0:file, fixedFile", vd.knownVariableNamesToString());
    }

}
