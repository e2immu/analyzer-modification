package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class TestGetSet2 extends CommonTest {
    @Language("java")
    public static final String INPUT1 = """
            import java.util.List;
            import java.util.ArrayList;
            class X {
                private final List<Integer> myList = new ArrayList<>();
            
                public Integer get(int i) {
                    return myList.get(i);
                }
            
                public Integer getMyList(int i) {
                    return myList.get(i);
                }
            
                public List<Integer> getMyList() {
                    return myList;
                }
            
                public void set(int i, int k) {
                    myList.set(i, k);
                }
            
                // not a setter!
                public void add(int k) {
                    myList.add(k);
                }
            
                // should always return true
                public boolean method(int pos) {
                    return myList.get(pos).equals(get(pos));
                }
            }
            """;

    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        new PrepAnalyzer(runtime).doPrimaryType(X);
        MethodInfo method = X.findUniqueMethod("method", 1);

        MethodInfo get = X.findUniqueMethod("get", 1);
        assertEquals("GetSetValueImpl[field=X.myList, setter=false, parameterIndexOfIndex=0]",
                get.getSetField().toString());

        Expression getE = get.methodBody().lastStatement().expression();
        if(getE instanceof MethodCall mc) {
            assertEquals("GetSetValueImpl[field=java.util.List._synthetic_list, setter=false, parameterIndexOfIndex=0]",
                    mc.methodInfo().getSetField().toString());
        } else fail();

        MethodInfo getMyList = X.findUniqueMethod("getMyList", 1);
        assertEquals("GetSetValueImpl[field=X.myList, setter=false, parameterIndexOfIndex=0]",
                getMyList.getSetField().toString());

        MethodInfo getMyList0 = X.findUniqueMethod("getMyList", 0);
        assertEquals("GetSetValueImpl[field=X.myList, setter=false, parameterIndexOfIndex=-1]",
                getMyList0.getSetField().toString());

        MethodInfo add = X.findUniqueMethod("add", 1);
        assertEquals(ValueImpl.GetSetValueImpl.EMPTY, add.getSetField());

        Expression expression = method.methodBody().lastStatement().expression();
        if (expression instanceof MethodCall mc) {
            Expression get1 = mc.object();
            assertEquals("this.myList.get(pos)", get1.toString());
            assertEquals("this.get(pos)", mc.parameterExpressions().get(0).toString());
        } else fail();
    }
}