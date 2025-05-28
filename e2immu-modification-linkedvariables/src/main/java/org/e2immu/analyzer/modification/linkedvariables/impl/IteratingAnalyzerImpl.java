package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalyzerException;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.SingleIterationAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.op.Cycles;
import org.e2immu.util.internal.graph.op.Linearize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static class ConfigurationBuilder{
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
        while (true) {
            ++iterations;
            LOGGER.info("Start iteration {}", iterations);
            SingleIterationAnalyzer.Output output = singleIterationAnalyzer.go(analysisOrder, false);
            G<Info> waitFor = output.waitFor();
            analyzerExceptions.addAll(output.analyzerExceptions());
            boolean done = waitFor.vertices().isEmpty();
            if (iterations == configuration.maxIterations() || done) {
                LOGGER.info("Stop iterating after {} iterations, done? {}", iterations, done);
                return new OutputImpl(waitFor, new Cycles<>(Set.of()), iterations,
                        output.infoHistogram(), analyzerExceptions);
            }
            int waitForSize = waitFor.vertices().size();
            if (waitForSize >= prevWaitingForSize) {
                Linearize.Result<Info> result = Linearize.linearize(waitFor);
                Cycles<Info> cycles = result.remainingCycles();
                LOGGER.info("No improvements anymore, have {} cycles after {} iterations", cycles.size(), iterations);
                assert !cycles.isEmpty();
                if (configuration.stopWhenCycleDetectedAndNoImprovements()) {
                    return new OutputImpl(waitFor, cycles, iterations, output.infoHistogram(), analyzerExceptions);
                }
                ++iterations;
                SingleIterationAnalyzer.Output output2 = singleIterationAnalyzer.go(analysisOrder, true);
                analyzerExceptions.addAll(output.analyzerExceptions());

                G<Info> waitFor2 = output2.waitFor();
                assert waitFor2.vertices().isEmpty();
                return new OutputImpl(waitFor2, new Cycles<>(Set.of()), iterations,
                        output.infoHistogram(), analyzerExceptions);
            }
            LOGGER.info("WaitingFor now {}, iterating again", waitForSize);
            prevWaitingForSize = waitForSize;
        }
    }
}