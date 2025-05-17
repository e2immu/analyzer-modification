package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestNeedMethodReturnTypeInHCT extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.io.*;
            import java.util.LinkedList;
            
            public class Function17439301_file1261893 {
              public String[] getMRUFileList() {
                if (size() == 0) {
                  return null;
                }
                String[] ss = new String[size()];
                for (int i = 0; i < size(); i++) {
                  Object o = getFile(i);
                  if (o instanceof File) {
                    ss[i] = ((File) o).getAbsolutePath();
                  } else {
                    ss[i] = o.toString();
                  }
                }
                return ss;
              }
            
              private LinkedList _mruFileList;
            
              /** Gets the size of the MRU file list. */
              public int size() {
                return _mruFileList.size();
              }
            
              /** Returns a particular file name stored in a MRU file list based on an index value. */
              public Object getFile(int index) {
                if (index < size()) {
                  return _mruFileList.get(index);
                }
                return null;
              }
            }
            """;

    @DisplayName("fails when method return type is not in method HCT")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);

        MethodInfo getFile = B.findUniqueMethod("getFile", 1);
        assertTrue(getFile.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT).isIndependentHc());
    }
}
