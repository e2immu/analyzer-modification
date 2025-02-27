package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestNull extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.io.*;
            import java.util.*;
            import java.util.regex.*;
            
            public class Function9837402_file1210466 {
              public static void copy(Reader r, Writer w) throws IOException {
                char[] buffer = new char[1024 * 16];
                for (; ; ) {
                  int n = r.read(buffer, 0, buffer.length);
                  if (n < 0) break;
                  w.write(buffer, 0, n);
                }
              }
            }
            """;

    @DisplayName("empty block causes issues")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }
}
