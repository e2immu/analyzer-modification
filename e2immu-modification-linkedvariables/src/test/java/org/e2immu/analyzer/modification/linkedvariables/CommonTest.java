package org.e2immu.analyzer.modification.linkedvariables;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.shallow.analyzer.*;
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

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommonTest {
    protected JavaInspector javaInspector;
    protected Runtime runtime;
    protected final String[] extraClassPath;
    protected ModAnalyzer analyzer;
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
                .addClassPath(InputConfigurationImpl.GRADLE_DEFAULT)
                .addClassPath(ToolChain.CLASSPATH_JUNIT)
                .addClassPath(ToolChain.CLASSPATH_SLF4J_LOGBACK);
        for (String extra : extraClassPath) {
            builder.addClassPath(extra);
        }
        InputConfiguration inputConfiguration = builder.build();
        javaInspector.initialize(inputConfiguration);
        javaInspector.preload("java.util");

        AnnotatedAPIConfiguration annotatedAPIConfiguration = new AnnotatedAPIConfigurationImpl.Builder()
                .addAnalyzedAnnotatedApiDirs(ToolChain.currentJdkAnalyzedPackages())
                .addAnalyzedAnnotatedApiDirs(ToolChain.commonLibsAnalyzedPackages())
                .build();
        new LoadAnalyzedPackageFiles().go(javaInspector, annotatedAPIConfiguration);

        javaInspector.parse(JavaInspectorImpl.FAIL_FAST);
        runtime = javaInspector.runtime();
        prepAnalyzer = new PrepAnalyzer(runtime);

        analyzer = new ModAnalyzerImpl(runtime, storeErrorsInPVMap);
    }

    protected List<Info> prepWork(TypeInfo typeInfo) {
        List<TypeInfo> typesLoaded = javaInspector.compiledTypesManager().typesLoaded();
        assertTrue(typesLoaded.stream().anyMatch(ti -> "java.util.ArrayList".equals(ti.fullyQualifiedName())));
        prepAnalyzer.initialize(typesLoaded);

        return prepAnalyzer.doPrimaryType(typeInfo);
    }

    protected String printType(TypeInfo newType) {
        Qualification.Decorator decorator = new DecoratorImpl(runtime);
        OutputBuilder ob = runtime.newTypePrinter(newType, false).print(javaInspector.importComputer(4),
                runtime.qualificationQualifyFromPrimaryType(decorator), true);
        Formatter formatter = new FormatterImpl(runtime, new FormattingOptionsImpl.Builder().build());
        return formatter.write(ob);
    }
}
