package org.e2immu.analyzer.modification.prepwork.hct;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestComputeHiddenContent {

    @Test
    public void test() throws IOException {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.e2immu.analyzer.shallow")).setLevel(Level.DEBUG);

        InputConfigurationImpl.Builder builder = new InputConfigurationImpl.Builder()
                .addClassPath(InputConfigurationImpl.DEFAULT_CLASSPATH);
        InputConfiguration inputConfiguration = builder.build();
        JavaInspector javaInspector = new JavaInspectorImpl();
        javaInspector.initialize(inputConfiguration);

        javaInspector.preload("java.util");

        ComputeHiddenContent chc = new ComputeHiddenContent(javaInspector.runtime());

        TypeInfo list = javaInspector.compiledTypesManager().get(List.class);
        HiddenContentTypes hctList = chc.compute(list);
        assertEquals("0=E", hctList.detailedSortedTypes());

        TypeInfo mapEntry = javaInspector.compiledTypesManager().get(Map.Entry.class);
        HiddenContentTypes hctMapEntry = chc.compute(mapEntry);
        assertEquals("0=K, 1=V", hctMapEntry.detailedSortedTypes());
    }
}
