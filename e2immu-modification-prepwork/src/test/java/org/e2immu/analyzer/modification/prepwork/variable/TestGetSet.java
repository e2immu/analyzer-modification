package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestGetSet extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                record R(int i) {}
                Object[] objects = new Object[10];
                List<String> list = new ArrayList<>();
                double d;
                R r;
                R rr;
            
                Object getO(int i) { return objects[i]; }
                Object getO2() { return objects[2]; } // not @GetSet YET
            
                String getS(int j) { return list.get(j); }
                String getS2() { return list.get(0); } // not @GetSet YET
            
                double d() { return d; }
                double dd() { return this.d; }
            
                int ri() { return r.i; }
                int rri() { return rr.i; }
            }
            """;

    @DisplayName("getters and setters")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);
        analyzer.doPrimaryType(X);

        FieldInfo objects = X.getFieldByName("objects", true);
        FieldInfo list = X.getFieldByName("list", true);
        FieldInfo d = X.getFieldByName("d", true);
        TypeInfo R = X.findSubType("R");
        FieldInfo ri = R.getFieldByName("i", true);

        MethodInfo getO = X.findUniqueMethod("getO", 1);
        assertSame(objects, getO.getSetField().field());
        MethodInfo getO2 = X.findUniqueMethod("getO2", 0);
        assertNull(getO2.getSetField().field());

        MethodInfo getS = X.findUniqueMethod("getS", 1);
        assertSame(list, getS.getSetField().field());
        MethodInfo getS2 = X.findUniqueMethod("getS2", 0);
        assertNull(getS2.getSetField().field());

        MethodInfo md = X.findUniqueMethod("d", 0);
        assertSame(d, md.getSetField().field());
        MethodInfo mdd = X.findUniqueMethod("dd", 0);
        assertSame(d, mdd.getSetField().field());

        MethodInfo mri = X.findUniqueMethod("ri", 0);
        assertSame(ri, mri.getSetField().field());
        MethodInfo mRri = X.findUniqueMethod("rri", 0);
        assertSame(ri, mRri.getSetField().field()); // FIXME we cannot tell the difference: are we accessing r or rr??
    }
}
