package org.e2immu.analyzer.modification.prepwork.hct;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.io.CodecImpl;
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

public class TestComputeHiddenContent extends CommonTest {

    @Test
    public void test() {
        ComputeHiddenContent chc = new ComputeHiddenContent(javaInspector.runtime());

        TypeInfo list = javaInspector.compiledTypesManager().get(List.class);
        HiddenContentTypes hctList = chc.compute(list);
        assertEquals("0=E", hctList.detailedSortedTypes());

        Codec codec = new CodecImpl(null, null); // we don't have to decode
        Codec.EncodedValue ev = hctList.encode(codec);
        assertEquals("{\"E\":true,\"M\":1,0:\"E\"}", ev.toString());

        TypeInfo mapEntry = javaInspector.compiledTypesManager().get(Map.Entry.class);
        HiddenContentTypes hctMapEntry = chc.compute(mapEntry);
        assertEquals("0=K, 1=V", hctMapEntry.detailedSortedTypes());

        Codec.EncodedValue ev2 = hctMapEntry.encode(codec);
        assertEquals("{\"E\":true,\"M\":2,0:\"K\",1:\"V\"}", ev2.toString());
    }
}
