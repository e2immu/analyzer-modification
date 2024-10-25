package org.e2immu.analyzer.modification.linkedvariables;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfiguration;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfigurationImpl;
import org.e2immu.analyzer.shallow.analyzer.DecoratorImpl;
import org.e2immu.analyzer.shallow.analyzer.LoadAnalyzedAnnotatedAPI;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.output.Formatter;
import org.e2immu.language.cst.api.output.OutputBuilder;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.print.FormatterImpl;
import org.e2immu.language.cst.print.FormattingOptionsImpl;
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
    protected Analyzer analyzer;
    protected PrepAnalyzer prepAnalyzer;
    protected final boolean storeErrorsInPVMap;

    protected CommonTest(String... extraClassPath) {
        this(false, extraClassPath);
    }

    protected CommonTest(boolean storeErrorsInPVMap, String... extraClassPath) {
        this.extraClassPath = extraClassPath;
        this.storeErrorsInPVMap = storeErrorsInPVMap;
    }

    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("graph-algorithm")).setLevel(Level.DEBUG);
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        javaInspector = new JavaInspectorImpl();
        InputConfigurationImpl.Builder builder = new InputConfigurationImpl.Builder()
                .addClassPath(InputConfigurationImpl.DEFAULT_CLASSPATH)
                .addClassPath("jmods/java.desktop.jmod")
                .addClassPath("jmods/java.datatransfer.jmod")
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

        String JSON = "../../analyzer-shallow/e2immu-shallow-aapi/src/main/resources/json";
        File file = new File(JSON);
        assertTrue(file.isDirectory());
        AnnotatedAPIConfiguration annotatedAPIConfiguration = new AnnotatedAPIConfigurationImpl.Builder()
                .addAnalyzedAnnotatedApiDirs(JSON)
                .build();
        new LoadAnalyzedAnnotatedAPI().go(javaInspector, annotatedAPIConfiguration);

        javaInspector.parse(true);
        runtime = javaInspector.runtime();
        prepAnalyzer = new PrepAnalyzer(runtime);
        List<TypeInfo> typesLoaded = javaInspector.compiledTypesManager().typesLoaded();
        assertTrue(typesLoaded.stream().anyMatch(ti -> "java.lang.Exception".equals(ti.fullyQualifiedName())));
        prepAnalyzer.initialize(typesLoaded);
        analyzer = new Analyzer(runtime, storeErrorsInPVMap);
    }

    protected List<Info> prepWork(TypeInfo typeInfo) {
        return prepAnalyzer.doPrimaryType(typeInfo);
    }

    protected String printType(TypeInfo newType) {
        Qualification.Decorator decorator = new DecoratorImpl(runtime);
        OutputBuilder ob = newType.print(runtime.qualificationQualifyFromPrimaryType(decorator), true);
        Formatter formatter = new FormatterImpl(runtime, new FormattingOptionsImpl.Builder().build());
        return formatter.write(ob);
    }
}
