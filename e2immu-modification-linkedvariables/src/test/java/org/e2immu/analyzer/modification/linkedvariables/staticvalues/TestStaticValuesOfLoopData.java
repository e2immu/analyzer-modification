package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_COMPONENTS_PARAMETER;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.MODIFIED_FI_COMPONENTS_PARAMETER;
import static org.junit.jupiter.api.Assertions.*;

public class TestStaticValuesOfLoopData extends CommonTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestStaticValuesOfLoopData.class);

    @Language("java")
    public static final String INPUT = """
            package a.b;
            import org.e2immu.annotation.Modified;
            import org.e2immu.annotation.method.GetSet;
            import java.util.ArrayList;
            import java.util.Iterator;import java.util.List;
            import java.util.function.Function;
            import org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop;
            
            class X {
               private Loop.LoopData modify(Loop.LoopData ld) {
                    float[][] m =(float[][])ld.get(0);
                    int a = (int)ld.get(1);
                    float tmp = m[a][1];
                    m[a][1] = m[a][0];
                    m[a][0] = tmp;
                    return ld;
               }
            
               private void swap1(float[][] f) {
                   Loop.LoopData ld = new Loop.LoopDataImpl.Builder().set(0, f).build();
                   modify(ld);
               }
            
               private void swap2(float[][] f) {
                   Loop.LoopData ld = new Loop.LoopDataImpl.Builder()
                       .body(this::modify)
                       .set(0, f)
                       .build();
                   Loop.run(ld);
               }

                private void swap3(float[][] f) {
                   Loop.LoopData ld1 = new Loop.LoopDataImpl.Builder()
                       .body(this::modify)
                       .set(0, f)
                       .build();
                   Loop.run(ld1);
               }
            }
            """;

    public static final String LD_VARIABLES = "org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop.LoopData.variables#a.b.X.modify(org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop.LoopData):0:ld";

    @DisplayName("static assignment in LoopData, propagate modifications")
    @Test
    public void test1() throws IOException {
        LOGGER.info("Path is {}", new File(".").getAbsolutePath());
        String loopFile = "./src/test/java/org/e2immu/analyzer/modification/linkedvariables/staticvalues/Loop.java";
        String loopJava = Files.readString(Path.of(loopFile));
        TypeInfo loop = javaInspector.parse(loopJava);
        {
            List<Info> analysisOrder = prepWork(loop);
            analyzer.doPrimaryType(loop, analysisOrder);
        }

        testRun(loop);

        TypeInfo X = javaInspector.parse(INPUT);
        {
            List<Info> analysisOrder = prepWork(X);
            analyzer.doPrimaryType(X, analysisOrder);
        }
        testModify(X);
        testSwap1(X);
        testSwap2(X);
        testSwap3(X);
    }

    private static void testSwap3(TypeInfo X) {
        MethodInfo swap = X.findUniqueMethod("swap3", 1);
        ParameterInfo swap0 = swap.parameters().get(0);
        {
            VariableData vd0 = VariableDataImpl.of(swap.methodBody().statements().get(0));
            VariableInfo vi0Ld = vd0.variableInfo("ld1");
            assertEquals("Type org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop.LoopDataImpl this.body=this::modify, variables[0]=f",
                    vi0Ld.staticValues().toString());
        }
        {
            VariableData vd1 = VariableDataImpl.of(swap.methodBody().statements().get(1));
            VariableInfo vi1f = vd1.variableInfo(swap0);
            // compared to testSwap2, 'ld' has a different name :-)
            assertTrue(vi1f.isModified());
        }
    }

    private static void testSwap2(TypeInfo X) {
        MethodInfo swap = X.findUniqueMethod("swap2", 1);
        ParameterInfo swap0 = swap.parameters().get(0);
        {
            VariableData vd0 = VariableDataImpl.of(swap.methodBody().statements().get(0));
            VariableInfo vi0Ld = vd0.variableInfo("ld");
            assertEquals("Type org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop.LoopDataImpl this.body=this::modify, variables[0]=f",
                    vi0Ld.staticValues().toString());
        }
        {
            VariableData vd1 = VariableDataImpl.of(swap.methodBody().statements().get(1));
            VariableInfo vi1f = vd1.variableInfo(swap0);
            // here we should see that ld.body has been executed, which is the modify() call
            // the modify call changes variables[0], which is f

            // we have all the information to make this work!
            // main code in ExpressionAnalyzer.propagateComponents -> EA.propagateModificationOfParameter
            assertTrue(vi1f.isModified());
        }
    }

    private static void testRun(TypeInfo loop) {
        MethodInfo run = loop.findUniqueMethod("run", 1);
        assertTrue(run.parameters().get(0).isModified());
        ParameterInfo run0 = run.parameters().get(0);
        assertTrue(run0.isModified());
        assertEquals("this.body=true", run0.analysis().getOrNull(MODIFIED_FI_COMPONENTS_PARAMETER,
                ValueImpl.VariableBooleanMapImpl.class).toString());
    }

    private static void testSwap1(TypeInfo X) {
        MethodInfo swap = X.findUniqueMethod("swap1", 1);
        ParameterInfo swap0 = swap.parameters().get(0);
        {
            VariableData vd0 = VariableDataImpl.of(swap.methodBody().statements().get(0));
            VariableInfo vi0Ld = vd0.variableInfo("ld");
            assertEquals("Type org.e2immu.analyzer.modification.linkedvariables.staticvalues.Loop.LoopDataImpl variables[0]=f",
                    vi0Ld.staticValues().toString());
        }
        {
            VariableData vd1 = VariableDataImpl.of(swap.methodBody().statements().get(1));
            VariableInfo vi1Ld = vd1.variableInfo("ld");
            assertFalse(vi1Ld.isModified());
            VariableInfo vi1f = vd1.variableInfo(swap0);
            assertTrue(vi1f.isModified());
        }
    }

    private static void testModify(TypeInfo X) {
        MethodInfo modify = X.findUniqueMethod("modify", 1);
        ParameterInfo modify0 = modify.parameters().get(0);
        assertEquals("ld", modify0.name());

        {
            VariableData vd0 = VariableDataImpl.of(modify.methodBody().statements().get(0));
            assertEquals("E=ld.variables[0]", vd0.variableInfo("m").staticValues().toString());
        }
        {
            VariableData vd2 = VariableDataImpl.of(modify.methodBody().statements().get(2));
            assertEquals("E=m[a][1]", vd2.variableInfo("tmp").staticValues().toString());
        }
        {
            VariableData vd3 = VariableDataImpl.of(modify.methodBody().statements().get(3));
            VariableInfo vi3m = vd3.variableInfo("m");
            assertEquals("E=ld.variables[0] this[a][1]=m[a][0]", vi3m.staticValues().toString());
            assertEquals("*M-4-0M:ld, 0M-2-*M|0-*:m[a], *-2-0:variables, -1-:variables[0]",
                    vi3m.linkedVariables().toString());
            assertTrue(vi3m.isModified());

            VariableInfo vi3Variables = vd3.variableInfo(LD_VARIABLES);
            assertTrue(vi3Variables.isModified()); // this follows the 2 link

            VariableInfo vi3ld = vd3.variableInfo(modify0);
            // NOT following the -4- link!
            assertFalse(vi3ld.isModified());
        }
        {
            VariableData vdLast = VariableDataImpl.of(modify.methodBody().lastStatement());
            VariableInfo viLastM = vdLast.variableInfo("m");
            assertTrue(viLastM.isModified());

            VariableInfo viLastModified0 = vdLast.variableInfo(modify0);
            assertEquals("0M-4-*M:m, 0M-4-*M:m[a], 0-4-0:variables, 0M-4-*M:variables[0]",
                    viLastModified0.linkedVariables().toString());
            assertFalse(viLastModified0.isModified());
        }
        assertFalse(modify0.isModified());
        // the modified components parameter will be our gateway to propagating the modifications
        assertEquals("this.variables=true, this.variables[0]=true",
                modify0.analysis().getOrNull(MODIFIED_COMPONENTS_PARAMETER, ValueImpl.VariableBooleanMapImpl.class).toString());
        assertTrue(modify.isIdentity());
    }

}
