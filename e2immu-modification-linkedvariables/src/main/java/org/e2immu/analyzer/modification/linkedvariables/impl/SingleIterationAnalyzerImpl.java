package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.SingleIterationAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.runtime.Runtime;

import java.util.List;

public class SingleIterationAnalyzerImpl implements SingleIterationAnalyzer {
    private final Runtime runtime;
    private final IteratingAnalyzer.Configuration configuration;

    public SingleIterationAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        this.runtime = runtime;
        this.configuration = configuration;
    }

    @Override
    public Output go(List<Info> analysisOrder, boolean activateCycleBreaking) {
        return null;
    }
}
