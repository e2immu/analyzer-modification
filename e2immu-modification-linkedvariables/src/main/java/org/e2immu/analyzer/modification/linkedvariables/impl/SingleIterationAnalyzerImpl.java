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
    private final TypeModIndyAnalyzer typeModIndyAnalyzer;
    private final TypeImmutableAnalyzer typeImmutableAnalyzer;
    private final TypeIndependentAnalyzer typeIndependentAnalyzer;
    private final ShallowTypeAnalyzer shallowTypeAnalyzer;

    private record OutputImpl(List<Throwable> problemsRaised, G<Info> waitFor, Map<String, Integer> infoHistogram)
            implements Output {
    }

    public SingleIterationAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        this.runtime = runtime;
        this.configuration = configuration;
        methodModAnalyzer = new MethodModAnalyzerImpl(runtime, configuration);
        fieldAnalyzer = new FieldAnalyzerImpl(runtime);
        typeModIndyAnalyzer = new TypeModIndyAnalyzerImpl(runtime, configuration);
        typeImmutableAnalyzer = new TypeImmutableAnalyzerImpl(configuration);
        typeIndependentAnalyzer = new TypeIndependentAnalyzerImpl(configuration);
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
            } else if (info instanceof TypeInfo typeInfo) {
                Analyzer.Output output1 = typeModIndyAnalyzer.go(typeInfo, methodsWaitFor);
                myProblemsRaised.addAll(output1.problemsRaised());

                TypeIndependentAnalyzer.Output output2 = typeIndependentAnalyzer.go(typeInfo,
                        activateCycleBreaking);
                myProblemsRaised.addAll(output2.problemsRaised());

                TypeImmutableAnalyzer.Output output3 = typeImmutableAnalyzer.go(typeInfo,
                        activateCycleBreaking);
                myProblemsRaised.addAll(output3.problemsRaised());
                if (typeInfo.isPrimaryType()) primaryTypes.add(typeInfo);
            }
        }
        AbstractMethodAnalyzer abstractMethodAnalyzer = new AbstractMethodAnalyzerImpl(primaryTypes);
        myProblemsRaised.addAll(abstractMethodAnalyzer.go().problemsRaised());

        /*
        run once more, because the abstract method analyzer may have resolved independence and modification values
        for abstract methods.
         */
        for (Info info : analysisOrder) {
            if (info instanceof TypeInfo typeInfo) { // FIXME can be more efficient
                typeIndependentAnalyzer.go(typeInfo, activateCycleBreaking);
                typeImmutableAnalyzer.go(typeInfo, activateCycleBreaking);
            }
        }

        G.Builder<Info> builder = new G.Builder<>(Long::sum);

        return new OutputImpl(myProblemsRaised, builder.build(), Map.of());
    }
}
