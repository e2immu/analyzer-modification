package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import ch.qos.logback.classic.Level;
import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.shallow.analyzer.Composer;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class TestCloneBench extends CommonTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestCloneBench.class);

    public TestCloneBench() {
        super(true, "jmods/java.desktop.jmod",
                "jmods/java.compiler.jmod",
                "jmods/java.datatransfer.jmod",
                "jmods/java.sql.jmod",
                "jmods/java.logging.jmod",
                "jmods/java.instrument.jmod",
                "jmods/java.rmi.jmod",
                "jmods/java.management.jmod");
    }

    public void process(String name, AtomicInteger counter, Map<MethodInfo, Integer> typeHistogram) throws IOException {
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
            process(javaFile, outFile, counter.incrementAndGet(), typeHistogram);
        }
    }

    private void process(File javaFile, File outFile, int count, Map<MethodInfo, Integer> typeHistogram) throws IOException {
        String input = Files.readString(javaFile.toPath());
        LOGGER.info("Start parsing #{}, {}, file of size {}", count, javaFile, input.length());

        TypeInfo typeInfo = javaInspector.parse(input);

        List<Info> analysisOrder = prepWork(typeInfo);
        analyzer.doPrimaryType(typeInfo, analysisOrder);

        String printed = printType(typeInfo);
        Files.writeString(outFile.toPath(), printed, StandardCharsets.UTF_8);

        analysisOrder.stream().filter(info -> info instanceof MethodInfo)
                .forEach(info -> {
                    MethodInfo mi = (MethodInfo) info;
                    if (!mi.isAbstract() && !mi.isSyntheticConstructor()) {
                        assertNotNull(mi.methodBody(), "For method " + mi);
                        mi.methodBody().visit(e -> {
                            if (e instanceof MethodCall mc && mc.methodInfo().typeInfo().primaryType() != typeInfo) {
                                typeHistogram.merge(mc.methodInfo(), 1, Integer::sum);
                            }
                            return true;
                        });
                    }
                });
    }

    private static final String[] DIRS = {"bubblesort_for_withunit", "collections_layered",
            "dowhile_pure_compiles", "dowhile_pure_selected_withunit",
            "foreach_pure_compiles", "foreach_selection1_withunit",
            "fors_pure_compiles", "fors_pure_selected_withunit"
    };

    @Test
    public void test() throws IOException {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("graph-algorithm")).setLevel(Level.WARN);

        AtomicInteger counter = new AtomicInteger();
        Map<MethodInfo, Integer> typeHistogram = new HashMap<>();

        for (String dir : DIRS) {
            process(dir, counter, typeHistogram);
        }

        LOGGER.info("JDK calls:");
        Composer composer = new Composer(javaInspector.runtime(),
                "org.e2immu.analyzer.shallow.aapi.java", w -> w.access().isPublic());
        List<TypeInfo> toCompose =
                typeHistogram.entrySet().stream()
                        .sorted((e1, e2) -> e2.getValue() - e1.getValue())
                        .map(e -> {
                            MethodInfo method = e.getKey();
                            Stream<MethodInfo> stream = Stream.concat(Stream.of(method), method.overrides().stream());
                            boolean alreadyDone = stream.anyMatch(mi -> mi.analysis()
                                    .getOrDefault(PropertyImpl.ANNOTATED_API, ValueImpl.BoolImpl.FALSE).isTrue());
                            LOGGER.info("{} {}", e.getValue(), method + "  " + (alreadyDone ? "" : "ADD TO A-API"));
                            if (!alreadyDone) {
                                return method.primaryType();
                            }
                            return null;
                        }).filter(Objects::nonNull).distinct().toList();
        Collection<TypeInfo> aapiTypes = composer.compose(toCompose);
        composer.write(aapiTypes, "build/aapis");

        Map<String, Integer> problemHistogram = analyzer.getProblemsRaised().stream()
                .collect(Collectors.toUnmodifiableMap(t -> t == null || t.getMessage() == null ? "?" : t.getMessage(), t -> 1, Integer::sum));
        problemHistogram.entrySet().stream().sorted((e1, e2) -> e2.getValue() - e1.getValue()).forEach(e -> {
            LOGGER.info("Error freq {}: {}", e.getValue(), e.getKey());
        });
        int numErrors = analyzer.getProblemsRaised().size();
        assertEquals(0, numErrors, "Found " + numErrors + " errors parsing " + counter.get()
                                   + " files. Histogram: " + analyzer.getHistogram());
    }
}
