package org.e2immu.analyzer.modification.linkedvariables.modification;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestModificationField extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.io.BufferedReader;
            import java.io.IOException;
            public class Function63498_file16492 {
                protected BufferedReader buffRead;
                public String fastForward(String strSearch, boolean blnRegexMatch) throws IOException {
                    boolean blnContinue = false;
                    String strLine;
                    do {
                        strLine = buffRead.readLine();
                        if(strLine != null) {
                            if(blnRegexMatch) {
                                blnContinue = !strLine.matches(strSearch);
                            } else { blnContinue = !strLine.contains(strSearch); }
                        }
                    } while(blnContinue && strLine != null);
                    return strLine;
                }
            }
            """;

    @DisplayName("simple field modification")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo bufferedReader = javaInspector.compiledTypesManager().get(BufferedReader.class);
        assertTrue(bufferedReader.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isMutable());

        MethodInfo brReadLine = bufferedReader.findUniqueMethod("readLine", 0);
        assertTrue(brReadLine.isModifying());

        MethodInfo fastForward = X.findUniqueMethod("fastForward", 2);
        FieldInfo buffRead = X.getFieldByName("buffRead", true);

        Statement s200 = fastForward.methodBody().statements().get(2).block().statements().get(0);
        VariableData vd200 = VariableDataImpl.of(s200);
        VariableInfo vi200BuffRead = vd200.variableInfo(runtime.newFieldReference(buffRead));
        assertTrue(vi200BuffRead.isModified());

        assertTrue(fastForward.isModifying());
        assertTrue(buffRead.isModified());
    }
}