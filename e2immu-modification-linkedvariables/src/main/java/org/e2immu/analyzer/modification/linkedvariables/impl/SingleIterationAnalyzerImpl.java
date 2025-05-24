package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalyzerException;
import org.e2immu.analyzer.modification.common.defaults.ShallowTypeAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.*;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;

import java.util.*;

public class SingleIterationAnalyzerImpl implements SingleIterationAnalyzer, ModAnalyzerForTesting {
    private final IteratingAnalyzer.Configuration configuration;
    private final MethodModAnalyzer methodModAnalyzer;
    private final FieldAnalyzer fieldAnalyzer;
    private final TypeModIndyAnalyzer typeModIndyAnalyzer;
    private final TypeImmutableAnalyzer typeImmutableAnalyzer;
    private final TypeIndependentAnalyzer typeIndependentAnalyzer;
    private final ShallowTypeAnalyzer shallowTypeAnalyzer;
    private final TypeContainerAnalyzer typeContainerAnalyzer;

    private record OutputImpl(List<AnalyzerException> analyzerExceptions, G<Info> waitFor,
                              Map<String, Integer> infoHistogram)
            implements Output {
    }

    public SingleIterationAnalyzerImpl(Runtime runtime, IteratingAnalyzer.Configuration configuration) {
        this.configuration = configuration;
        methodModAnalyzer = new MethodModAnalyzerImpl(runtime, configuration);
        fieldAnalyzer = new FieldAnalyzerImpl(runtime, configuration);
        typeModIndyAnalyzer = new TypeModIndyAnalyzerImpl(runtime, configuration);
        typeImmutableAnalyzer = new TypeImmutableAnalyzerImpl(configuration);
        typeIndependentAnalyzer = new TypeIndependentAnalyzerImpl(configuration);
        shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, Element::annotations, false);
        typeContainerAnalyzer = new TypeContainerAnalyzerImpl(configuration);
    }

    @Override
    public List<AnalyzerException> go(List<Info> analysisOrder) {
        return go(analysisOrder, false).analyzerExceptions();
    }

    @Override
    public Output go(List<Info> analysisOrder, boolean activateCycleBreaking) {
        List<AnalyzerException> analyzerExceptions = new ArrayList<>();
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
                analyzerExceptions.addAll(output.analyzerExceptions());
            } else if (info instanceof FieldInfo fieldInfo) {
                if (fieldInfo.owner().isAbstract()) {
                    shallowTypeAnalyzer.analyzeField(fieldInfo);
                }
                FieldAnalyzer.Output output = fieldAnalyzer.go(fieldInfo);
                analyzerExceptions.addAll(output.analyzerExceptions());
            } else if (info instanceof TypeInfo typeInfo) {
                Analyzer.Output output1 = typeModIndyAnalyzer.go(typeInfo, methodsWaitFor);
                analyzerExceptions.addAll(output1.analyzerExceptions());

                TypeIndependentAnalyzer.Output output2 = typeIndependentAnalyzer.go(typeInfo,
                        activateCycleBreaking);
                analyzerExceptions.addAll(output2.analyzerExceptions());

                TypeImmutableAnalyzer.Output output3 = typeImmutableAnalyzer.go(typeInfo,
                        activateCycleBreaking);
                analyzerExceptions.addAll(output3.analyzerExceptions());
                if (typeInfo.isPrimaryType()) primaryTypes.add(typeInfo);
            }
        }
        AbstractMethodAnalyzer abstractMethodAnalyzer = new AbstractMethodAnalyzerImpl(configuration, primaryTypes);
        analyzerExceptions.addAll(abstractMethodAnalyzer.go().analyzerExceptions());

        /*
        run once more, because the abstract method analyzer may have resolved independence and modification values
        for abstract methods.
         */
        for (Info info : analysisOrder) {
            if (info instanceof TypeInfo typeInfo) { // FIXME can be more efficient
                TypeIndependentAnalyzer.Output output1 = typeIndependentAnalyzer.go(typeInfo, activateCycleBreaking);
                analyzerExceptions.addAll(output1.analyzerExceptions());
                TypeImmutableAnalyzer.Output output2 = typeImmutableAnalyzer.go(typeInfo, activateCycleBreaking);
                analyzerExceptions.addAll(output2.analyzerExceptions());
                TypeContainerAnalyzer.Output output3 = typeContainerAnalyzer.go(typeInfo);
                analyzerExceptions.addAll(output3.analyzerExceptions());
            }
        }

        G.Builder<Info> builder = new G.Builder<>(Long::sum);

        return new OutputImpl(analyzerExceptions, builder.build(), Map.of());
    }
}
