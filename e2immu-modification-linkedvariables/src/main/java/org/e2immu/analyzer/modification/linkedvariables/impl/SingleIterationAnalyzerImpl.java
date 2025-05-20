package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.defaults.ShallowTypeAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.*;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;

import java.util.*;

public class SingleIterationAnalyzerImpl implements SingleIterationAnalyzer, ModAnalyzerForTesting {
    private final Runtime runtime;
    private final IteratingAnalyzer.Configuration configuration;
    private final MethodModAnalyzer methodModAnalyzer;
    private final FieldAnalyzer fieldAnalyzer;
    private final PrimaryTypeModIndyAnalyzer primaryTypeModIndyAnalyzer;
    private final PrimaryTypeImmutableAnalyzer primaryTypeImmutableAnalyzer;
    private final ShallowTypeAnalyzer shallowTypeAnalyzer;

    private record OutputImpl(List<Throwable> problemsRaised, G<Info> waitFor, Map<String, Integer> infoHistogram)
            implements Output {
    }

    public SingleIterationAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        this.runtime = runtime;
        this.configuration = configuration;
        methodModAnalyzer = new MethodModAnalyzerImpl(runtime, configuration);
        fieldAnalyzer = new FieldAnalyzerImpl(runtime);
        primaryTypeModIndyAnalyzer = new PrimaryTypeModIndyAnalyzerImpl(runtime, configuration);
        primaryTypeImmutableAnalyzer = new PrimaryTypeImmutableAnalyzerImpl(configuration);
        shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, Info::annotations, false);
    }

    @Override
    public List<Throwable> go(List<Info> analysisOrder) {
        return go(analysisOrder, false).problemsRaised();
    }

    @Override
    public Output go(List<Info> analysisOrder, boolean activateCycleBreaking) {
        List<Throwable> myProblemsRaised = new ArrayList<>();
        Map<MethodInfo, Set<MethodInfo>> methodsWaitFor = new HashMap<>();
        Set<TypeInfo> primaryTypes = new HashSet<>();
        Set<TypeInfo> abstractTypes = new HashSet<>();
        for (Info info : analysisOrder) {
            if (info instanceof MethodInfo methodInfo) {
                if (methodInfo.isAbstract() && abstractTypes.add(info.typeInfo())) {
                    shallowTypeAnalyzer.analyze(info.typeInfo());
                }
                MethodModAnalyzer.Output output = methodModAnalyzer.go(methodInfo, activateCycleBreaking);
                methodsWaitFor.put(methodInfo, output.waitForMethods());
            } else if (info instanceof FieldInfo fieldInfo) {
                if (fieldInfo.owner().isAbstract()) {
                    shallowTypeAnalyzer.analyzeField(fieldInfo);
                }
                FieldAnalyzer.Output output = fieldAnalyzer.go(fieldInfo);
                myProblemsRaised.addAll(output.problemsRaised());
            } else if (info instanceof TypeInfo typeInfo && typeInfo.isPrimaryType()) {
                Analyzer.Output output1 = primaryTypeModIndyAnalyzer.go(typeInfo, methodsWaitFor);
                myProblemsRaised.addAll(output1.problemsRaised());

                PrimaryTypeImmutableAnalyzer.Output output2 = primaryTypeImmutableAnalyzer.go(typeInfo,
                        activateCycleBreaking);
                myProblemsRaised.addAll(output2.problemsRaised());
                primaryTypes.add(typeInfo);
            }
        }
        AbstractMethodAnalyzer abstractMethodAnalyzer = new AbstractMethodAnalyzerImpl(primaryTypes);
        myProblemsRaised.addAll(abstractMethodAnalyzer.go().problemsRaised());
        for (TypeInfo primaryType : primaryTypes) {
            primaryTypeImmutableAnalyzer.go(primaryType, activateCycleBreaking);
        }
        
        G.Builder<Info> builder = new G.Builder<>(Long::sum);

        return new OutputImpl(myProblemsRaised, builder.build(), Map.of());
    }
}
