package org.e2immu.analyzer.modification.prepwork.clonebench;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
IMPORTANT: use "analyzed" branch of "testarchive".
A small number of files have been modified wrt the main branch, for this test to run.
 */
public class TestCloneBenchMethodHistogram extends CommonTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestCloneBenchMethodHistogram.class);

    public TestCloneBenchMethodHistogram() {
        super("jmod:java.desktop",
                "jmod:java.compiler",
                "jmod:java.datatransfer",
                "jmod:java.sql",
                "jmod:java.logging",
                "jmod:java.instrument",
                "jmod:java.rmi",
                "jmod:java.management");
    }

    public void process(String name, Map<String, Integer> methodHistogram) throws IOException {
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime);

        String directory = "../../testtransform/" + name + "/src/main/java/";
        File src = new File(directory);
        assertTrue(src.isDirectory());
        File[] javaFiles = src.listFiles(f -> f.getName().endsWith(".java") && !isProcessed(f.getName()));
        assertNotNull(javaFiles);
        LOGGER.info("Found {} java files in {}", javaFiles.length, directory);
        for (File javaFile : javaFiles) {
            process(analyzer, javaFile, methodHistogram);
        }
    }

    private static final Pattern PROCESSED = Pattern.compile(".+_t(_o\\d)?.java");

    private static boolean isProcessed(String name) {
        return PROCESSED.matcher(name).matches();
    }

    private void process(PrepAnalyzer analyzer, File javaFile, Map<String, Integer> methodHistogram) throws IOException {
        String input = Files.readString(javaFile.toPath());
        LOGGER.info("Start parsing {}, file of size {}", javaFile, input.length());
        TypeInfo typeInfo = javaInspector.parse(input);
        List<Info> analysisOrder = analyzer.doPrimaryType(typeInfo);
        LOGGER.info("-    analysis order size {}", analysisOrder.size());
        analysisOrder.stream().filter(info -> info instanceof MethodInfo)
                .forEach(info -> {
                    MethodInfo mi = (MethodInfo) info;
                    mi.methodBody().visit(e -> {
                        if (e instanceof MethodCall mc && mc.methodInfo().typeInfo().primaryType() != typeInfo) {
                            methodHistogram.merge(mc.methodInfo().fullyQualifiedName(), 1, Integer::sum);
                        }
                        return true;
                    });
                });
    }

    @Test
    public void test() throws IOException {
        Map<String, Integer> methodHistogram = new HashMap<>();
        process("testDoWhile", methodHistogram);
        process("testFor", methodHistogram);
        process("testForBubbleSort", methodHistogram);
        process("testForeachPureCompiles", methodHistogram);
        process("testSwitchFor", methodHistogram);
        process("testSwitchPureCompiles", methodHistogram);
        process("testTry", methodHistogram);
        process("testTryResource", methodHistogram);
        process("testWhile", methodHistogram);

        methodHistogram.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue() - e1.getValue())
                .forEach(e -> LOGGER.info("{} {}", e.getValue(), e.getKey()));
    }
}
