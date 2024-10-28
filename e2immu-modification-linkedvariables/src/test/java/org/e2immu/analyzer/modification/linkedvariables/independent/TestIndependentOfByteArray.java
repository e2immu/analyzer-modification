package org.e2immu.analyzer.modification.linkedvariables.independent;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.IfElseStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl.LINKED_VARIABLES_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestIndependentOfByteArray extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            import java.io.EOFException;
            import java.io.IOException;
            import java.io.RandomAccessFile;
            
            public class B {
              public void readFully(byte[] b, int off, int len) throws IOException {
                int n = 0;
                do {
                  int count = read(b, off + n, len - n);
                  if (count < 0) throw new EOFException();
                  n += count;
                } while (n < len);
              }
            
              RandomAccessFile rf;
              byte[] arrayIn;
              int arrayInPtr;
              byte back;
              boolean isBack = false;
            
              public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                int n = 0;
                if (isBack) {
                  isBack = false;
                  if (len == 1) {
                    b[off] = back;
                    return 1;
                  } else {
                    n = 1;
                    b[off++] = back;
                    --len;
                  }
                }
                if (arrayIn == null) {
                  return rf.read(b, off, len) + n;
                } else {
                  if (arrayInPtr >= arrayIn.length) return -1;
                  if (arrayInPtr + len > arrayIn.length) len = arrayIn.length - arrayInPtr;
                  System.arraycopy(arrayIn, arrayInPtr, b, off, len);
                  arrayInPtr += len;
                  return len + n;
                }
              }
            }
            """;

    @DisplayName("byte array independent?")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        HiddenContentTypes hctB = B.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("0=RandomAccessFile", hctB.detailedSortedTypes());

        MethodInfo readFully = B.findUniqueMethod("readFully", 3);
        HiddenContentTypes hctReadFully = readFully.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, HiddenContentTypes.NO_VALUE);
        assertEquals("0=RandomAccessFile - ", hctReadFully.detailedSortedTypes());

        analyzer.doPrimaryType(B, ao);

        MethodInfo read = B.findUniqueMethod("read", 3);
        ParameterInfo b = read.parameters().get(0);
        Statement s3 = read.methodBody().statements().get(3);
        {
            Statement s312 = ((IfElseStatement) s3).elseBlock().statements().get(2);
            VariableData vd312 = VariableDataImpl.of(s312);
            VariableInfo viB = vd312.variableInfo(b);
            assertEquals("", viB.linkedVariables().toString());
        }
        {
            VariableData vd3 = VariableDataImpl.of(s3);
            VariableInfo viB = vd3.variableInfo(b);
            assertEquals("", viB.linkedVariables().toString());
        }
        assertEquals("", b.analysis().getOrDefault(LINKED_VARIABLES_PARAMETER, LinkedVariablesImpl.EMPTY).toString());
        assertTrue(b.analysis().getOrDefault(INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.DEPENDENT).isIndependent());
    }
}
