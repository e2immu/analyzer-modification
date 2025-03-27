package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeAnalysisOrder;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeCallGraph;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField;
import org.e2immu.analyzer.modification.prepwork.getset.GetSetHelper;
import org.e2immu.analyzer.modification.prepwork.hcs.ComputeHCS;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.expression.ConstructorCall;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.Block;
import org.e2immu.util.internal.graph.G;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

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
public class PrepAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrepAnalyzer.class);
    private final MethodAnalyzer methodAnalyzer;
    private final ComputeHiddenContent computeHiddenContent;
    private final ComputeHCS computeHCS;
    private final Runtime runtime;
    final boolean recurseIntoAnonymous;

    public PrepAnalyzer(Runtime runtime) {
        this(runtime, true);
    }

    public PrepAnalyzer(Runtime runtime, boolean recurseIntoAnonymous) {
        methodAnalyzer = new MethodAnalyzer(runtime, this);
        computeHiddenContent = new ComputeHiddenContent(runtime);
        computeHCS = new ComputeHCS(runtime);
        this.runtime = runtime;
        this.recurseIntoAnonymous = recurseIntoAnonymous;
    }

    /*
    we go via the FQN because we're in the process of translating them.
     */
    public void doMethod(MethodInfo methodInfo) {
        doMethod(methodInfo, methodInfo.methodBody());
    }

    public void doMethod(MethodInfo methodInfo, Block methodBlock) {
        methodAnalyzer.doMethod(methodInfo, methodBlock);
    }

    public List<Info> doPrimaryTypes(Set<TypeInfo> primaryTypes) {
        G<Info> callGraph = doPrimaryTypesReturnGraph(primaryTypes);
        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        return cao.go(callGraph);
    }

    public G<Info> doPrimaryTypesReturnGraph(Set<TypeInfo> primaryTypes) {
        for (TypeInfo primaryType : primaryTypes) {
            assert primaryType.isPrimaryType();
            doType(primaryType);
        }
        ComputeCallGraph ccg = new ComputeCallGraph(runtime, primaryTypes, ti -> false);
        G<Info> cg = ccg.go().graph();
        ccg.setRecursiveMethods();
        ComputePartOfConstructionFinalField cp = new ComputePartOfConstructionFinalField();
        for (TypeInfo primaryType : primaryTypes) {
            cp.go(primaryType, cg);
        }
        return cg;
    }

    public List<Info> doPrimaryType(TypeInfo typeInfo) {
        return doPrimaryTypes(Set.of(typeInfo));
    }

    void doType(TypeInfo typeInfo) {
        List<MethodInfo> gettersAndSetters = new LinkedList<>();
        List<MethodInfo> otherConstructorsAndMethods = new LinkedList<>();

        doType(typeInfo, gettersAndSetters, otherConstructorsAndMethods);

        /* now do the methods: first getters and setters, then the others
           why? because we must create variables in VariableData for each call to a getter
           therefore, all getters need to be known before they are being used.

           this is the most simple form of analysis order required here.
           the "linkedvariables" analyzer requires a more complicated one, computed in the statements below.
        */

        gettersAndSetters.forEach(this::doMethod);
        otherConstructorsAndMethods.forEach(this::doMethod);
    }

    private void doType(TypeInfo typeInfo, List<MethodInfo> gettersAndSetters, List<MethodInfo> otherConstructorsAndMethods) {
        LOGGER.debug("Do type {}", typeInfo);
        HiddenContentTypes hctType = typeInfo.analysis().getOrCreate(HIDDEN_CONTENT_TYPES, () ->
                computeHiddenContent.compute(typeInfo));

        // recurse
        typeInfo.subTypes().forEach(this::doType);
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            if (!mi.analysis().haveAnalyzedValueFor(HIDDEN_CONTENT_TYPES)) {
                HiddenContentTypes hctMethod = computeHiddenContent.compute(hctType, mi);
                mi.analysis().set(HIDDEN_CONTENT_TYPES, hctMethod);
            }
            computeHCS.doHiddenContentSelector(mi);
            boolean isGetSet = GetSetHelper.doGetSetAnalysis(mi, mi.methodBody());
            if (isGetSet) {
                gettersAndSetters.add(mi);
            } else {
                otherConstructorsAndMethods.add(mi);
            }
        });
        typeInfo.fields().forEach(methodAnalyzer::doInitializerExpression);
    }

    // called from MethodAnalyzer
    void handleSyntheticArrayConstructor(ConstructorCall cc) {
        if (!cc.constructor().analysis().haveAnalyzedValueFor(HIDDEN_CONTENT_TYPES)) {
            // synthetic array constructors are not stored listed in typeInfo.constructorAndMethodStream()
            HiddenContentTypes hctConstructorType = cc.constructor().typeInfo().analysis().getOrCreate(HIDDEN_CONTENT_TYPES,
                    () -> computeHiddenContent.compute(cc.constructor().typeInfo()));
            HiddenContentTypes hctMethod = computeHiddenContent.compute(hctConstructorType, cc.constructor());
            cc.constructor().analysis().set(HIDDEN_CONTENT_TYPES, hctMethod);
            computeHCS.doHiddenContentSelector(cc.constructor());
            // not adding it to "otherConstructorsAndMethods"
        }
    }

    public void initialize(List<TypeInfo> typesLoaded) {
        computeHCS.doPrimitives();
        // while the annotated APIs have HCT/HCS values for a number of types, this line takes care of the rest
        typesLoaded.stream().filter(TypeInfo::isPrimaryType).forEach(computeHCS::doType);
    }
}
