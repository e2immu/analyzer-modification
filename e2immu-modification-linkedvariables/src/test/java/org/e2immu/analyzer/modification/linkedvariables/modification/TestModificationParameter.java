package org.e2immu.analyzer.modification.linkedvariables.modification;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestModificationParameter extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public class Function63498_file16492 {
                private static int method(int[] dest, int offset, int[] x, int len, int y) {
                    long yl =(long)y & 4294967295L;
                    int carry = 0;
                    int j = 0;
            
                    do {
                        long prod =((long)x[j] & 4294967295L) * yl;
                        int prod_low =(int)prod;
                        int prod_high =(int)(prod >> 32);
                        prod_low += carry;
                        carry =((prod_low ^ -2147483648) <(carry ^ -2147483648) ? 1 : 0) + prod_high;
                        int x_j = dest[offset + j];
                        prod_low = x_j - prod_low;
                        if((prod_low ^ -2147483648) >(x_j ^ -2147483648)) { carry++; }
                        dest[offset + j] = prod_low;
                    } while((++j) < len);
            
                    return carry;
                }
            }
            """;

    @DisplayName("parameter is array type, modification")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
        MethodInfo methodInfo = X.findUniqueMethod("method", 5);
        ParameterInfo dest = methodInfo.parameters().get(0);

        assertTrue(dest.isModified());
    }
}
