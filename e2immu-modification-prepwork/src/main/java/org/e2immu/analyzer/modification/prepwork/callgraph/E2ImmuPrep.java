package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.parser.ParseResult;
import org.e2immu.language.inspection.api.parser.Summary;
import org.e2immu.util.internal.graph.G;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public record E2ImmuPrep(Runtime runtime,
                         JavaInspector javaInspector,
                         ParseResult parseResult,
                         G<Info> infoGraph,
                         List<Info> analysisOrder,
                         Set<MethodInfo> recursiveMethods) {
    public static E2ImmuPrep makeDoNotAcceptExternals(JavaInspector javaInspector, Summary summary) {
        return makeDoNotAcceptExternals(javaInspector, summary.parseResult());
    }

    public static E2ImmuPrep makeDoNotAcceptExternals(JavaInspector javaInspector, ParseResult parseResult) {
        return make(javaInspector, parseResult, t -> false);
    }

    public static E2ImmuPrep makeAcceptExternalsButNotJdk(JavaInspector javaInspector, ParseResult parseResult) {
        return make(javaInspector, parseResult, t -> t.compilationUnit() != null && !t.compilationUnit().partOfJdk());
    }

    public static E2ImmuPrep make(JavaInspector javaInspector, ParseResult parseResult, Predicate<TypeInfo> externalsToAccept) {
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(javaInspector.runtime());
        prepAnalyzer.initialize(javaInspector.compiledTypesManager().typesLoaded());
        ComputeCallGraph ccg = prepAnalyzer.doPrimaryTypesReturnComputeCallGraph(Set.copyOf(parseResult.primaryTypes()),
                externalsToAccept);
        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        List<Info> order = cao.go(ccg.graph());
        return new E2ImmuPrep(javaInspector.runtime(), javaInspector, parseResult, ccg.graph(), order, ccg.recursiveMethods());
    }
}
