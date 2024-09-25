package org.e2immu.analyzer.modification.linkedvariables;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.Analyze;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfiguration;
import org.e2immu.analyzer.shallow.analyzer.AnnotatedAPIConfigurationImpl;
import org.e2immu.analyzer.shallow.analyzer.LoadAnalyzedAnnotatedAPI;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.output.Formatter;
import org.e2immu.language.cst.api.output.OutputBuilder;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.impl.analysis.DecoratorImpl;
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
import java.util.List;
import java.util.Set;

import static org.e2immu.language.inspection.integration.JavaInspectorImpl.JAR_WITH_PATH_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CommonTest {
    protected JavaInspector javaInspector;
    protected Runtime runtime;
    protected final String[] extraClassPath;
    protected final boolean loadAnnotatedAPIs;

    protected CommonTest() {
        this(false, new String[]{});
    }

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
            AnnotatedAPIConfiguration annotatedAPIConfiguration = new AnnotatedAPIConfigurationImpl.Builder()
                    .addAnalyzedAnnotatedApiDirs("../../analyzer-shallow/e2immu-shallow-aapi/src/main/resources/json")
                    .build();
            new LoadAnalyzedAnnotatedAPI().go(javaInspector, annotatedAPIConfiguration);
        }

        javaInspector.parse(true);
        runtime = javaInspector.runtime();
    }

    protected void prepWork(TypeInfo typeInfo) {
        TypeInfo list = javaInspector.compiledTypesManager().get(List.class);

        MethodInfo get = list.findUniqueMethod("get", 1);
        assertEquals("java.util.List.get(int)", get.fullyQualifiedName());
        HiddenContentSelector getHcs = new ComputeHCS(runtime).hcsOfMethod(get);
        assertEquals("*", getHcs.toString());
        get.analysis().set(HiddenContentSelector.HCS_METHOD, getHcs);

        MethodInfo add = list.findUniqueMethod("add", 1);
        assertEquals("java.util.List.add(E)", add.fullyQualifiedName());
        HiddenContentSelector addHcs = new ComputeHCS(runtime).hcsOfMethod(add);
        assertEquals("X", addHcs.toString()); // returns 'true', constant
        add.analysis().set(HiddenContentSelector.HCS_METHOD, addHcs);
        ParameterInfo add0 = add.parameters().get(0);
        HiddenContentSelector add0Hcs = new ComputeHCS(runtime).hcsOfParameter(add0);
        add0.analysis().set(HiddenContentSelector.HCS_PARAMETER, add0Hcs);
        assertEquals("*", add0Hcs.toString());

        TypeInfo set = javaInspector.compiledTypesManager().get(Set.class);

        MethodInfo setAdd = set.findUniqueMethod("add", 1);
        assertEquals("java.util.Set.add(E)", setAdd.fullyQualifiedName());
        HiddenContentSelector setAddHcs = new ComputeHCS(runtime).hcsOfMethod(setAdd);
        assertEquals("X", setAddHcs.toString()); // returns 'true', constant
        setAdd.analysis().set(HiddenContentSelector.HCS_METHOD, setAddHcs);
        ParameterInfo setAdd0 = setAdd.parameters().get(0);
        HiddenContentSelector setAdd0Hcs = new ComputeHCS(runtime).hcsOfParameter(setAdd0);
        setAdd0.analysis().set(HiddenContentSelector.HCS_PARAMETER, setAdd0Hcs);
        assertEquals("*", setAdd0Hcs.toString());

        ComputeHiddenContent chc = new ComputeHiddenContent(runtime);
        HiddenContentTypes hct = chc.compute(typeInfo);
        Analyze analyze = new Analyze(runtime);
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            HiddenContentTypes hctMethod = chc.compute(hct, mi);
            mi.analysis().set(HiddenContentTypes.HIDDEN_CONTENT_TYPES, hctMethod);
            analyze.doMethod(mi);
        });
        analyze.copyModifications(typeInfo);
    }
}
