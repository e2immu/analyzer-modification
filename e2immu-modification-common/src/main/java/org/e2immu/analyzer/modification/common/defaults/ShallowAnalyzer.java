package org.e2immu.analyzer.modification.common.defaults;

import org.e2immu.language.cst.api.analysis.Message;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.op.Linearize;

import java.util.*;
import java.util.stream.Stream;

public class ShallowAnalyzer {
    private final Runtime runtime;
    private final AnnotationProvider annotationProvider;
    private final List<Message> messages = new LinkedList<>();

    /*
    from type = from return type, field type, parameter type
    from owner = from type containing method, field, parameter
    from field = for final field, ...
    from parameter = for identity
    from method = for identity, fluent
     */
    public enum AnnotationOrigin {ANNOTATED, FROM_OVERRIDE, FROM_TYPE, FROM_OWNER, FROM_PARAMETER, FROM_METHOD, FROM_FIELD, DEFAULT}

    public record InfoData(Map<Property, AnnotationOrigin> originMap) {
        public AnnotationOrigin origin(AnnotationExpression ae) {
            return originMap.getOrDefault(ae.typeInfo(), AnnotationOrigin.DEFAULT);
        }

        public void put(Property property, AnnotationOrigin origin) {
            originMap.put(property, origin);
        }
    }

    public record Result(Map<Info, InfoData> dataMap,
                         List<TypeInfo> allTypes,
                         G<TypeInfo> typeGraph,
                         List<TypeInfo> sorted,
                         List<Message> messages) {
    }

    public ShallowAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        this.runtime = runtime;
        this.annotationProvider = annotationProvider;
    }

    public Result go(List<TypeInfo> types) {
        ShallowTypeAnalyzer shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, annotationProvider);
        ShallowMethodAnalyzer shallowMethodAnalyzer = new ShallowMethodAnalyzer(runtime, annotationProvider);
        List<TypeInfo> allTypes = types.stream().flatMap(TypeInfo::recursiveSubTypeStream)
                .filter(TypeInfo::isPublic)
                .flatMap(t -> Stream.concat(Stream.of(t), t.recursiveSuperTypeStream()))
                .distinct()
                .toList();
        G.Builder<TypeInfo> graphBuilder = new G.Builder<>(Long::sum);
        for (TypeInfo typeInfo : allTypes) {
            List<TypeInfo> allSuperTypes = typeInfo.recursiveSuperTypeStream()
                    .filter(TypeInfo::isPublic)
                    .toList();
            graphBuilder.add(typeInfo, allSuperTypes);
        }
        G<TypeInfo> typeGraph = graphBuilder.build();
        Linearize.Result<TypeInfo> linearize = Linearize.linearize(typeGraph, Linearize.LinearizationMode.ALL);
        List<TypeInfo> sorted = linearize.asList(Comparator.comparing(TypeInfo::fullyQualifiedName));
        Map<Info, InfoData> dataMap = new HashMap<>();
        for (TypeInfo typeInfo : sorted) {
            dataMap.putAll(shallowTypeAnalyzer.analyze(typeInfo));
        }
        for (TypeInfo typeInfo : sorted) {
            dataMap.putAll(shallowTypeAnalyzer.analyzeFields(typeInfo));
            typeInfo.constructorAndMethodStream()
                    .filter(MethodInfo::isPublic)
                    .forEach(mi -> dataMap.putAll(shallowMethodAnalyzer.analyze(mi)));
        }
        for (TypeInfo typeInfo : sorted) {
            shallowTypeAnalyzer.check(typeInfo);
        }
        messages.addAll(shallowMethodAnalyzer.messages());
        return new Result(dataMap, allTypes, typeGraph, sorted, messages);
    }
}
