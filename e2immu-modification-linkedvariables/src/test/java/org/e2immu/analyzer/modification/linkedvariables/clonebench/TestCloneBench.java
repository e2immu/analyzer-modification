package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCloneBench extends CommonTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestCloneBench.class);

    public TestCloneBench() {
        super("jmods/java.desktop.jmod",
                "jmods/java.compiler.jmod",
                "jmods/java.datatransfer.jmod",
                "jmods/java.sql.jmod",
                "jmods/java.logging.jmod",
                "jmods/java.instrument.jmod",
                "jmods/java.rmi.jmod",
                "jmods/java.management.jmod");
    }

    public void process(String name) throws IOException {
        String directory = "../../testtransform/" + name + "/src/main/java/";
        File src = new File(directory);
        assertTrue(src.isDirectory());
        File[] javaFiles = src.listFiles(f -> f.getName().endsWith(".java") && !f.getName().endsWith("_t.java"));
        assertNotNull(javaFiles);
        LOGGER.info("Found {} java files in {}", javaFiles.length, directory);
        for (File javaFile : javaFiles) {
            process(javaFile);
        }
    }

    private void process(File javaFile) throws IOException {
        String input = Files.readString(javaFile.toPath());
        LOGGER.info("Start parsing {}, file of size {}", javaFile, input.length());
        TypeInfo typeInfo = javaInspector.parse(input);
        List<Info> analysisOrder = prepAnalyzer.doPrimaryType(typeInfo);
        analyzer.doPrimaryType(typeInfo, analysisOrder);
    }

    @Test
    public void test() throws IOException {
        process("testDoWhile");
        process("testFor");
        process("testForBubbleSort");
        process("testForeachPureCompiles");
        process("testSwitchFor");
        process("testSwitchPureCompiles");
        process("testTry");
        process("testTryResource");
        process("testWhile");
    }
}
