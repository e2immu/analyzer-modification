package org.e2immu.analyzer.modification.common.defaults;

import org.e2immu.annotation.Modified;
import org.e2immu.annotation.NotModified;
import org.e2immu.language.cst.api.expression.AnnotationExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.analysis.Message;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.impl.analysis.MessageImpl;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.op.Linearize;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

public class ShallowAnalyzer {
    private final Runtime runtime;
    private final AnnotationProvider annotationProvider;
    private List<TypeInfo> allTypes;
    private G<TypeInfo> graph;
    private List<TypeInfo> sorted;
    private final List<Message> messages = new LinkedList<>();

    public ShallowAnalyzer(Runtime runtime, AnnotationProvider annotationProvider) {
        this.runtime = runtime;
        this.annotationProvider = annotationProvider;
    }

    public List<TypeInfo> go(List<TypeInfo> types) {
        ShallowTypeAnalyzer shallowTypeAnalyzer = new ShallowTypeAnalyzer(runtime, annotationProvider);
        ShallowMethodAnalyzer shallowMethodAnalyzer = new ShallowMethodAnalyzer(runtime, annotationProvider);
        allTypes = types.stream().flatMap(TypeInfo::recursiveSubTypeStream)
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
        graph = graphBuilder.build();
        Linearize.Result<TypeInfo> linearize = Linearize.linearize(graph, Linearize.LinearizationMode.ALL);
        sorted = linearize.asList(Comparator.comparing(TypeInfo::fullyQualifiedName));
        for (TypeInfo typeInfo : sorted) {
            shallowTypeAnalyzer.analyze(typeInfo);
        }
        for (TypeInfo typeInfo : sorted) {
            shallowTypeAnalyzer.analyzeFields(typeInfo);
            typeInfo.constructorAndMethodStream()
                    .filter(MethodInfo::isPublic)
                    .forEach(shallowMethodAnalyzer::analyze);
        }
        for (TypeInfo typeInfo : sorted) {
            shallowTypeAnalyzer.check(typeInfo);
        }
        messages.addAll(shallowMethodAnalyzer.messages());
        return sorted;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public List<TypeInfo> getAllTypes() {
        return allTypes;
    }

    public G<TypeInfo> getGraph() {
        return graph;
    }

    public List<TypeInfo> getSorted() {
        return sorted;
    }

    public List<Message> messages() {
        return messages;
    }
}
