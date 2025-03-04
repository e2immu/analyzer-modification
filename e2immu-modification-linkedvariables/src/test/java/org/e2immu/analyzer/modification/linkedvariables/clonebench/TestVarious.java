package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestVarious extends CommonTest {

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

    @Language("java")
    private static final String INPUT2 = """
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.nio.ByteBuffer;
            import java.nio.channels.FileChannel;
            
            public class X {
              public void read(File f) throws IOException {
                FileInputStream fis = new FileInputStream(f);
                FileChannel fc = fis.getChannel();
                buffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
                int o = 0;
                do {
                  int l = buffer.getInt(o);
                  if (l <= STRING_OFFSET) {
                    throw new IOException("Corrupted file : invalid packet length");
                  }
                  addOffset(o);
                  o += l;
                } while (o < buffer.limit());
              }
            
              private static final int LEVEL_OFFSET = 0 + 4;
              private static final int MILLIS_OFFSET = LEVEL_OFFSET + 2;
              private static final int NUMBER_OFFSET = MILLIS_OFFSET + 8;
              private static final int THREAD_OFFSET = NUMBER_OFFSET + 8;
              private static final int LOGGER_LENGTH_OFFSET = THREAD_OFFSET + 4;
              private static final int MESSAGE_LENGTH_OFFSET = LOGGER_LENGTH_OFFSET + 1;
              private static final int SOURCE_CLASS_LENGTH_OFFSET = MESSAGE_LENGTH_OFFSET + 2;
              private static final int SOURCE_METHOD_LENGTH_OFFSET = SOURCE_CLASS_LENGTH_OFFSET + 1;
              private static final int STRING_OFFSET = SOURCE_METHOD_LENGTH_OFFSET + 1;
              private int maxEntry;
              private ByteBuffer buffer;
              private int[] offsets;
              private int nbrEntry;
              private int start;
            
              protected int addOffset(int offset) {
                if ((maxEntry < 0) && ((nbrEntry + start + 1) >= offsets.length)) {
                  int[] n = new int[offsets.length * 2];
                  System.arraycopy(offsets, 0, n, 0, offsets.length);
                  offsets = n;
                }
                int index = (nbrEntry + start) % offsets.length;
                int previous = offsets[index];
                offsets[index] = offset;
                if (nbrEntry < offsets.length) {
                  nbrEntry++;
                  return -1;
                } else {
                  start = (start + 1) % offsets.length;
                  return previous;
                }
              }
            }
            """;

    @Test
    public void test2() {
        TypeInfo B = javaInspector.parse(INPUT2);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }


    @Language("java")
    private static final String INPUT3 = """
            import java.util.*;
            
            public class X {
              private boolean addToSortedList(Object element) {
                int min = 0;
                int max = size() - 1;
                boolean found = false;
                int currentIndex = 0;
                int compareResult;
                if (max >= 0) {
                  do {
                    currentIndex = (min + max) / 2;
                    compareResult = ((Comparable) myList.get(currentIndex)).compareTo(element);
                    if (compareResult < 0) {
                      min = currentIndex + 1;
                    } else if (compareResult > 0) {
                      max = currentIndex - 1;
                    } else {
                      found = true;
                    }
                  } while ((min <= max) && (found == false));
                }
                if (found == false && size() > 0) {
                  if (((Comparable) element).compareTo(get(currentIndex)) > 0) {
                    myList.add(currentIndex + 1, element);
                  } else {
                    myList.add(currentIndex, element);
                  }
                  return true;
                } else if (found == false) {
                  myList.add(currentIndex, element);
                  return true;
                } else {
                  System.out.println("Element found in vector already.");
                  return false;
                }
              }
            
              private List myList;
            
              /**
               * Get the element at specified index
               *
               * @param index element index
               * @return element from the index
               */
              public Object get(int index) {
                return myList.get(index);
              }
            
              /**
               * Return the number of elements in the list.
               *
               * @return number of elements
               */
              public int size() {
                return myList.size();
              }
            }
            """;

    @Test
    public void test3() {
        TypeInfo B = javaInspector.parse(INPUT3);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }
}
