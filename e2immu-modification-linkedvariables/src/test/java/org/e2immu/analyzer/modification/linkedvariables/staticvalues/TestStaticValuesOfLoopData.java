package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.cst.impl.variable.FieldReferenceImpl;
import org.e2immu.language.cst.impl.variable.ThisImpl;
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

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.NONE;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.analyzer.modification.prepwork.variable.impl.VariableInfoImpl.MODIFIED_FI_COMPONENTS_VARIABLE;
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
            
               // ensure that f is @Modified
               private void swap(float[][] f) {
                   Loop.LoopData ld = new Loop.LoopDataImpl.Builder().set(0, f).build();
                   Loop.run(ld);
               }
            }
            """;


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
        MethodInfo run = loop.findUniqueMethod("run", 1);
        assertTrue(run.parameters().get(0).isModified());

        TypeInfo X = javaInspector.parse(INPUT);
        {
            List<Info> analysisOrder = prepWork(X);
            analyzer.doPrimaryType(X, analysisOrder);
        }
        MethodInfo modify = X.findUniqueMethod("modify", 1);
        ParameterInfo modify0 = modify.parameters().get(0);
        assertEquals("ld.variables=true, ld.variables[0]=true",
                modify0.analysis().getOrNull(MODIFIED_COMPONENTS_PARAMETER, ValueImpl.VariableBooleanMapImpl.class).toString());
        assertTrue(modify.isIdentity());

        VariableData vd0 = VariableDataImpl.of(modify.methodBody().statements().get(0));
        assertEquals("E=ld.variables[0]", vd0.variableInfo("m").staticValues().toString());
        VariableData vdLast = VariableDataImpl.of(modify.methodBody().lastStatement());

        VariableInfo viLastM = vdLast.variableInfo("m");
        assertTrue(viLastM.isModified());

        VariableInfo viLastModified0 = vdLast.variableInfo(modify0);
        assertEquals("0M-4-*M:m, 0M-4-*M:m[a], 0-4-0:variables, 0M-4-*M:variables[0]",
                viLastModified0.linkedVariables().toString());

        // FIXME this is a modification propagation problem... graph should see to solution 4M
        assertTrue(viLastModified0.isModified());
        assertTrue(modify0.isModified());
    }

}
