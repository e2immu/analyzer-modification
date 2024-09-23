package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.op.Linearize;

import java.util.*;
import java.util.stream.Collectors;

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
    private final G.Builder<String> graphBuilder = new G.Builder<>(Long::sum);
    private final Set<MethodInfo> copyMethods;

    // for testing
    public Analyze(Runtime runtime) {
        this(runtime, Set.of());
    }

    public Analyze(Runtime runtime, Set<MethodInfo> copyMethods) {
        this.runtime = runtime;
        this.copyMethods = copyMethods;
    }

    public void copyModifications(TypeInfo typeInfo) {
        Map<String, MethodInfo> byFqn = typeInfo.constructorAndMethodStream()
                .collect(Collectors.toUnmodifiableMap(MethodInfo::fullyQualifiedName, mi -> mi));
        G<String> graph = graphBuilder.build();
        Linearize.Result<String> result = Linearize.linearize(graph);
        List<String> order = result.asList(Comparator.naturalOrder());
        for (String methodFqn : order) {
            MethodInfo methodInfo = byFqn.get(methodFqn);
            boolean mm = methodInfo.analysis().getOrDefault(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
            if (mm) {
                Map<V<String>, Long> dep = graph.edges(graph.vertex(methodFqn));
                if (dep != null) {
                    for (V<String> v : dep.keySet()) {
                        MethodInfo target = byFqn.get(v.t());
                        if (!target.analysis().haveAnalyzedValueFor(PropertyImpl.MODIFIED_METHOD)) {
                            target.analysis().set(PropertyImpl.MODIFIED_METHOD, ValueImpl.BoolImpl.TRUE);
                        }
                    }
                }
            }
        }
    }

    /*
    we go via the FQN because we're in the process of translating them.
     */
    public void doMethod(MethodInfo methodInfo) {
        AnalyzeMethod am = new AnalyzeMethod(runtime, copyMethods);
        am.doMethod(methodInfo);
        for (Map.Entry<MethodInfo, Set<MethodInfo>> e : am.getCopyModificationStatusFromTo().entrySet()) {
            graphBuilder.add(e.getKey().fullyQualifiedName(), e.getValue().stream().map(MethodInfo::fullyQualifiedName).toList());
        }
    }
}
