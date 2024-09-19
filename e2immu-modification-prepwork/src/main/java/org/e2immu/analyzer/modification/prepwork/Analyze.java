package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.runtime.Runtime;

/*
do all the analysis of this phase

at the level of the type
- hidden content analysis
- call graph, call cycle, partOfConstruction
- simple immutability status

at the level of the method
- variable data, variable info, assignments
- simple modification status
 */
public class Analyze {
    private final Runtime runtime;
    private final AnalyzeMethod analyzeMethod;

    public Analyze(Runtime runtime) {
        this.runtime = runtime;
        this.analyzeMethod = new AnalyzeMethod(runtime);
    }

    public void doMethod(MethodInfo methodInfo) {
        analyzeMethod.doMethod(methodInfo);
    }
}
