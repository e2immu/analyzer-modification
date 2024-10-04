package org.e2immu.analyzer.modification.linkedvariables;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfiguration;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfigurationImpl;
import org.e2immu.analyzer.shallow.analyzer.LoadAnalyzedAnnotatedAPI;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static org.e2immu.language.inspection.integration.JavaInspectorImpl.JAR_WITH_PATH_PREFIX;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommonTest {
    protected JavaInspector javaInspector;
    protected Runtime runtime;
    protected final String[] extraClassPath;
    protected final boolean loadAnnotatedAPIs;
    protected Analyzer analyzer;
    protected PrepAnalyzer prepAnalyzer;

    protected CommonTest(boolean loadAnnotatedAPIs, String... extraClassPath) {
        this.extraClassPath = extraClassPath;
        this.loadAnnotatedAPIs = loadAnnotatedAPIs;
    }

    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        javaInspector = new JavaInspectorImpl();
        InputConfigurationImpl.Builder builder = new InputConfigurationImpl.Builder()
                .addClassPath(InputConfigurationImpl.DEFAULT_CLASSPATH)
                .addClassPath(JAR_WITH_PATH_PREFIX + "org/junit/jupiter/api")
                .addClassPath(JAR_WITH_PATH_PREFIX + "org/apiguardian/api")
                .addClassPath(JAR_WITH_PATH_PREFIX + "org/junit/platform/commons")
                .addClassPath(JAR_WITH_PATH_PREFIX + "org/slf4j/event")
                .addClassPath(JAR_WITH_PATH_PREFIX + "ch/qos/logback/core")
                .addClassPath(JAR_WITH_PATH_PREFIX + "ch/qos/logback/classic")
                .addClassPath(JAR_WITH_PATH_PREFIX + "io/codelaser/jfocus/transform/support")
                .addClassPath(JAR_WITH_PATH_PREFIX + "org/opentest4j");
        for (String extra : extraClassPath) {
            builder.addClassPath(extra);
        }
        InputConfiguration inputConfiguration = builder.build();
        javaInspector.initialize(inputConfiguration);
        javaInspector.preload("java.util");

        if (loadAnnotatedAPIs) {
            String JSON = "../../analyzer-shallow/e2immu-shallow-aapi/src/main/resources/json";
            File file = new File(JSON);
            assertTrue(file.isDirectory());
            AnnotatedAPIConfiguration annotatedAPIConfiguration = new AnnotatedAPIConfigurationImpl.Builder()
                    .addAnalyzedAnnotatedApiDirs(JSON)
                    .build();
            new LoadAnalyzedAnnotatedAPI().go(javaInspector, annotatedAPIConfiguration);
        }

        javaInspector.parse(true);
        runtime = javaInspector.runtime();
        analyzer = new Analyzer(runtime);
        prepAnalyzer = new PrepAnalyzer(runtime);
    }

    protected List<Info> prepWork(TypeInfo typeInfo) {
        ComputeHC computeHC = new ComputeHC(runtime);
        computeHC.doPredefinedObjects(runtime);
        computeHC.doType(Function.class, List.class, Set.class, ArrayList.class, Map.class, HashMap.class,
                Collection.class, Collections.class, Exception.class, RuntimeException.class);

        return prepAnalyzer.doPrimaryType(typeInfo);
    }

    private void prepType(TypeInfo typeInfo) {
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepType(prepAnalyzer, typeInfo);
    }

    private void prepType(PrepAnalyzer prepAnalyzer, TypeInfo typeInfo) {
        typeInfo.subTypes().forEach(st -> prepType(prepAnalyzer, st));
        typeInfo.constructorAndMethodStream().forEach(prepAnalyzer::doMethod);
    }
}
