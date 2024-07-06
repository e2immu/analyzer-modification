package org.e2immu.analyzer.modification.prepwork.variable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestVariableData {

    private static JavaInspector javaInspector;

    @BeforeAll
    public static void beforeAll() throws IOException {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.e2immu.analyzer.shallow")).setLevel(Level.DEBUG);

        InputConfigurationImpl.Builder builder = new InputConfigurationImpl.Builder()
                .addClassPath(InputConfigurationImpl.DEFAULT_CLASSPATH);
        InputConfiguration inputConfiguration = builder.build();
        javaInspector = new JavaInspectorImpl();
        javaInspector.initialize(inputConfiguration);
    }

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class C {
                void method1(String s) {
                    assert s != null;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo typeInfo = javaInspector.parseReturnAll(INPUT).get(0);
        MethodInfo method1 = typeInfo.findUniqueMethod("method1", 1);
        Analyze analyze = new Analyze(javaInspector.runtime());
        analyze.doMethod(method1);
        VariableData vd = method1.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
        assert vd != null;
        List<VariableInfo> vis = vd.variableInfoStream().toList();
        assertEquals(2, vis.size());
        
        This thisVar = javaInspector.runtime().newThis(typeInfo);
        VariableInfo thisVi = vd.variableInfo(thisVar.fullyQualifiedName());
        assertNotNull(thisVi);
        assertSame(VariableInfoContainer.NOT_YET_READ, thisVi.readId());

        ParameterInfo pi = method1.parameters().get(0);
        VariableInfo vi0 = vd.variableInfo(pi.fullyQualifiedName());
        assertSame("0", vi0.readId());
        assertTrue(vi0.assignmentIds().hasNotYetBeenAssigned());
    }
}
