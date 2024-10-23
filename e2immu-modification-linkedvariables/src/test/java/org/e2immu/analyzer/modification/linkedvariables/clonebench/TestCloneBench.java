package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import ch.qos.logback.classic.Level;
import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.output.Formatter;
import org.e2immu.language.cst.api.output.OutputBuilder;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.analyzer.shallow.analyzer.DecoratorImpl;
import org.e2immu.language.cst.print.FormatterImpl;
import org.e2immu.language.cst.print.FormattingOptionsImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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

    public void process(String name, AtomicInteger counter) throws IOException {
        String directory = "../../testarchive/" + name + "/src/main/java/";
        File src = new File(directory);
        assertTrue(src.isDirectory());
        File analyzedDir = new File(src.getParent(), "analyzed");
        if (analyzedDir.mkdirs()) {
            LOGGER.info("Created {}", analyzedDir);
        }
        File[] javaFiles = src.listFiles(f -> f.getName().endsWith(".java") && !f.getName().endsWith("_t.java"));
        assertNotNull(javaFiles);
        LOGGER.info("Found {} java files in {}", javaFiles.length, directory);
        for (File javaFile : javaFiles) {
            File outFile = new File(analyzedDir, javaFile.getName());
            process(javaFile, outFile, counter.incrementAndGet());
        }
    }

    private void process(File javaFile, File outFile, int count) throws IOException {
        String input = Files.readString(javaFile.toPath());
        LOGGER.info("Start parsing #{}, {}, file of size {}", count, javaFile, input.length());
        TypeInfo typeInfo = javaInspector.parse(input);
        List<Info> analysisOrder = prepAnalyzer.doPrimaryType(typeInfo);
        analyzer.doPrimaryType(typeInfo, analysisOrder);

        String printed = printType(typeInfo);
        Files.writeString(outFile.toPath(), printed, StandardCharsets.UTF_8);
    }

    protected String printType(TypeInfo newType) {
        Qualification.Decorator decorator = new DecoratorImpl(runtime);
        OutputBuilder ob = newType.print(runtime.qualificationQualifyFromPrimaryType(decorator), true);
        Formatter formatter = new FormatterImpl(runtime, new FormattingOptionsImpl.Builder().build());
        return formatter.write(ob);
    }

    @Test
    public void test() throws IOException {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("graph-algorithm")).setLevel(Level.INFO);
        AtomicInteger counter = new AtomicInteger();
        process("dowhile_pure_compiles", counter);
  /*      process("testFor", counter);
        process("testForBubbleSort", counter);
        process("testForeachPureCompiles", counter);
        process("testSwitchFor", counter);
        process("testSwitchPureCompiles", counter);
        process("testTry", counter);
        process("testTryResource", counter);
        process("testWhile", counter);*/
        int numErrors = analyzer.getProblemsRaised().size();
        assertEquals(0, numErrors, "Found " + numErrors + " errors parsing " + counter.get() + " files. Histogram: " + analyzer.getHistogram());
    }
}
