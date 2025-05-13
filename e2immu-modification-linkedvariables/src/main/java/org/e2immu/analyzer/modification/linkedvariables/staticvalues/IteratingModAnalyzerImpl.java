package org.e2immu.analyzer.modification.linkedvariables.staticvalues;

import org.e2immu.analyzer.modification.linkedvariables.IteratingModAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.ModAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.ModAnalyzerImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.op.Cycles;
import org.e2immu.util.internal.graph.op.Linearize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class IteratingModAnalyzerImpl implements IteratingModAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(IteratingModAnalyzerImpl.class);

    private final Runtime runtime;

    public IteratingModAnalyzerImpl(Runtime runtime) {
        this.runtime = runtime;
    }

    public record ConfigurationImpl(int maxIterations,
                                    boolean stopWhenCycleDetectedAndNoImprovements,
                                    boolean storeErrorsInPVMap) implements Configuration {
    }

    public static class OutputImpl implements Output {
        private final G<Info> waitingFor;
        private final Cycles<Info> cycles;
        private final int iterations;
        private final Map<String, Integer> infoHistogram;
        private final List<Throwable> problemsRaised;

        public OutputImpl(G<Info> waitingFor,
                          Cycles<Info> cycles,
                          int iterations,
                          Map<String, Integer> infoHistogram,
                          List<Throwable> problemsRaised) {
            this.waitingFor = waitingFor;
            this.iterations = iterations;
            this.infoHistogram = infoHistogram;
            this.problemsRaised = problemsRaised;
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
        public List<Throwable> problemsRaised() {
            return problemsRaised;
        }
    }

    @Override
    public Output analyze(List<Info> analysisOrder, Configuration configuration) {
        int iterations = 0;
        int prevWaitingForSize = Integer.MAX_VALUE;
        while (true) {
            ++iterations;
            ModAnalyzer modAnalyzer = new ModAnalyzerImpl(runtime, configuration.storeErrorsInPVMap(),
                    false);
            modAnalyzer.go(analysisOrder);
            G<Info> waitingFor = modAnalyzer.buildWaitingFor();

            boolean done = waitingFor.vertices().isEmpty();
            if (iterations == configuration.maxIterations() || done) {
                LOGGER.info("Stop iterating after {} iterations, done? {}", iterations, done);
                return new OutputImpl(waitingFor, new Cycles<>(Set.of()), iterations,
                        modAnalyzer.getHistogram(), modAnalyzer.getProblemsRaised());
            }
            int waitingForSize = waitingFor.vertices().size();
            if (waitingForSize >= prevWaitingForSize) {
                Linearize.Result<Info> result = Linearize.linearize(waitingFor);
                Cycles<Info> cycles = result.remainingCycles();
                LOGGER.info("No improvements anymore, have {} cycles", cycles.size());
                assert !cycles.isEmpty();
                if (configuration.stopWhenCycleDetectedAndNoImprovements()) {
                    return new OutputImpl(waitingFor, cycles, iterations, modAnalyzer.getHistogram(),
                            modAnalyzer.getProblemsRaised());
                }
                ++iterations;
                ModAnalyzer modAnalyzer2 = new ModAnalyzerImpl(runtime, configuration.storeErrorsInPVMap(),
                        true);
                G<Info> waitingFor2 = modAnalyzer2.buildWaitingFor();
                assert waitingFor2.vertices().isEmpty();
                return new OutputImpl(waitingFor2, new Cycles<>(Set.of()), iterations,
                        modAnalyzer.getHistogram(), modAnalyzer.getProblemsRaised());
            }
            LOGGER.info("WaitingFor now {}, iterating again", waitingForSize);
            prevWaitingForSize = waitingForSize;
        }
    }
}