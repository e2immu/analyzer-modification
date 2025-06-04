package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalyzerException;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.SingleIterationAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.analyser.TypeGraphIO;
import org.e2immu.util.internal.graph.op.Cycle;
import org.e2immu.util.internal.graph.op.Cycles;
import org.e2immu.util.internal.graph.op.Linearize;
import org.e2immu.util.internal.graph.op.ShortestCycleDijkstra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class IteratingAnalyzerImpl implements IteratingAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(IteratingAnalyzerImpl.class);

    private final Runtime runtime;

    public IteratingAnalyzerImpl(Runtime runtime) {
        this.runtime = runtime;
    }

    public record ConfigurationImpl(int maxIterations,
                                    boolean stopWhenCycleDetectedAndNoImprovements,
                                    boolean storeErrors,
                                    CycleBreakingStrategy cycleBreakingStrategy,
                                    boolean trackObjectCreations) implements Configuration {
    }

    public static class ConfigurationBuilder {
        private int maxIterations = 1;
        private boolean stopWhenCycleDetectedAndNoImprovements;
        private boolean storeErrors;
        private boolean trackObjectCreations;
        private CycleBreakingStrategy cycleBreakingStrategy = CycleBreakingStrategy.NONE;

        public ConfigurationBuilder setStoreErrors(boolean storeErrors) {
            this.storeErrors = storeErrors;
            return this;
        }

        public ConfigurationBuilder setCycleBreakingStrategy(CycleBreakingStrategy cycleBreakingStrategy) {
            this.cycleBreakingStrategy = cycleBreakingStrategy;
            return this;
        }

        public ConfigurationBuilder setStopWhenCycleDetectedAndNoImprovements(boolean stopWhenCycleDetectedAndNoImprovements) {
            this.stopWhenCycleDetectedAndNoImprovements = stopWhenCycleDetectedAndNoImprovements;
            return this;
        }

        public ConfigurationBuilder setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public ConfigurationBuilder setTrackObjectCreations(boolean trackObjectCreations) {
            this.trackObjectCreations = trackObjectCreations;
            return this;
        }

        public Configuration build() {
            return new ConfigurationImpl(maxIterations, stopWhenCycleDetectedAndNoImprovements, storeErrors,
                    cycleBreakingStrategy, trackObjectCreations);
        }
    }

    public static class OutputImpl implements Output {
        private final G<Info> waitingFor;
        private final Cycles<Info> cycles;
        private final int iterations;
        private final Map<String, Integer> infoHistogram;
        private final List<AnalyzerException> analyzerExceptions;

        public OutputImpl(G<Info> waitingFor,
                          Cycles<Info> cycles,
                          int iterations,
                          Map<String, Integer> infoHistogram,
                          List<AnalyzerException> analyzerExceptions) {
            this.waitingFor = waitingFor;
            this.iterations = iterations;
            this.infoHistogram = infoHistogram;
            this.analyzerExceptions = analyzerExceptions;
            this.cycles = cycles;
        }

        @Override
        public G<Info> waitingFor() {
            return waitingFor;
        }

        @Override
        public Cycles<Info> cyclesInWaitingFor() {
            return cycles;
        }

        @Override
        public int iterations() {
            return iterations;
        }

        @Override
        public Map<String, Integer> infoHistogram() {
            return infoHistogram;
        }

        @Override
        public List<AnalyzerException> analyzerExceptions() {
            return analyzerExceptions;
        }
    }

    @Override
    public Output analyze(List<Info> analysisOrder, Configuration configuration) {
        int iterations = 0;
        int prevWaitingForSize = Integer.MAX_VALUE;
        SingleIterationAnalyzer singleIterationAnalyzer = new SingleIterationAnalyzerImpl(runtime, configuration);
        List<AnalyzerException> analyzerExceptions = new LinkedList<>();
        boolean cycleBreakingActive = false;
        while (true) {
            ++iterations;
            LOGGER.info("\033[31;1;4mStart iteration {}\033[0m, cycle breaking active? {}", iterations, cycleBreakingActive);
            SingleIterationAnalyzer.Output output = singleIterationAnalyzer.go(analysisOrder, cycleBreakingActive);
            G<Info> waitFor = output.waitFor();
            analyzerExceptions.addAll(output.analyzerExceptions());
            boolean done = waitFor.vertices().isEmpty();
            if (iterations == configuration.maxIterations() || done) {
                LOGGER.info("Stop iterating after {} iterations, done? {}", iterations, done);
                return new OutputImpl(waitFor, new Cycles<>(Set.of()), iterations,
                        output.infoHistogram(), analyzerExceptions);
            }
            int waitForSize = waitFor.vertices().size();
            boolean noImprovement = waitForSize >= prevWaitingForSize;
            if (noImprovement || cycleBreakingActive) {
                Linearize.Result<Info> result = Linearize.linearize(waitFor);
                Cycles<Info> cycles = result.remainingCycles();
                if (cycleBreakingActive) {
                    LOGGER.info("WaitingFor now {} in cycle breaking, cycles:", waitForSize);
                    printCycles(output.waitFor(), cycles);
                } else {
                    LOGGER.info("No improvements anymore, have {} cycles after {} iterations", cycles.size(), iterations);
                    printCycles(output.waitFor(), cycles);
                    assert !cycles.isEmpty();
                    if (configuration.stopWhenCycleDetectedAndNoImprovements()) {
                        return new OutputImpl(waitFor, cycles, iterations, output.infoHistogram(), analyzerExceptions);
                    }
                    LOGGER.info("Activating cycle breaking");
                    cycleBreakingActive = true;
                }
            } else {
                LOGGER.info("WaitingFor now {}, iterating again", waitForSize);
            }
            prevWaitingForSize = waitForSize;
        }
    }

    private void printCycles(G<Info> graph, Cycles<Info> cycles) {
        for (Cycle<Info> cycle : cycles) {
            File file = new File("build/graph_" + Math.abs(new Random().nextLong()) + ".gml");
            try {
                TypeGraphIO.dumpGraph(file, graph.subGraph(cycle.vertices()));
            } catch (IOException e) {
                LOGGER.error("IO exception", e);
                throw new RuntimeException(e);
            }
            LOGGER.info("Cycle of size {}, dumped to {}:", cycle.size(), file);
            cycle.vertices().stream().sorted(Comparator.comparing(v -> v.t().toString()))
                    .forEach(v -> {
                        List<V<Info>> path = computeShortestRoundTrip(v, graph);
                        LOGGER.info("  + {}", path == null ? v + " [NO PATH]"
                                : path.stream().map(vv -> vv.t().toString()).collect(Collectors.joining(" -> ")));
                    });
        }
    }

    private static <T> List<V<T>> computeShortestRoundTrip(V<T> start, G<T> graph) {
        ShortestCycleDijkstra.Cycle<T> cycle = ShortestCycleDijkstra.shortestCycle(graph, start);
        if (cycle == null) return null;
        return cycle.vertices();
    }
}