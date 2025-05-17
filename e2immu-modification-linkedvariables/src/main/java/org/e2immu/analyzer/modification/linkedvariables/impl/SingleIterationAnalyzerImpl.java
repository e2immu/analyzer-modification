package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.MethodModAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.SingleIterationAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;

import java.util.List;

public class SingleIterationAnalyzerImpl implements SingleIterationAnalyzer, ModAnalyzerForTesting {
    private final Runtime runtime;
    private final IteratingAnalyzer.Configuration configuration;
    private final MethodModAnalyzer methodModAnalyzer;

    public SingleIterationAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        this.runtime = runtime;
        this.configuration = configuration;
        methodModAnalyzer = new MethodModAnalyzerImpl(runtime, configuration);
    }

    @Override
    public List<Throwable> go(List<Info> analysisOrder) {
       Output output = go(analysisOrder, false);
       return output.problemsRaised();
    }

    @Override
    public Output go(List<Info> analysisOrder, boolean activateCycleBreaking) {
        return null;
    }
}
