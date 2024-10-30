package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestIdentity extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import static java.util.Objects.requireNonNull;
            
            import java.io.PrintWriter;
            import java.io.StringWriter;
            
            public class ExceptionUtils {
              public static String printStackTrace(Throwable throwable) {
                requireNonNull(throwable, "throwable may not be null");
                StringWriter stringWriter = new StringWriter();
                try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
                  throwable.printStackTrace(printWriter);
                }
                return stringWriter.toString();
              }
            }
            """;

    @DisplayName("@Identity method")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }

}
