package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeAnalysisOrder;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeCallGraph;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField;
import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.expression.ConstructorCall;
import org.e2immu.language.cst.api.expression.Lambda;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.Block;
import org.e2immu.util.internal.graph.G;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
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

    public PrepAnalyzer(Runtime runtime) {
        methodAnalyzer = new MethodAnalyzer(runtime);
        computeHiddenContent = new ComputeHiddenContent(runtime);
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

    public List<Info> doPrimaryType(TypeInfo typeInfo, boolean isShallow) {
        doType(typeInfo);

        ComputeCallGraph ccg = new ComputeCallGraph(typeInfo);
        ccg.setRecursiveMethods();
        G<Info> cg = ccg.go().graph();
        if (!isShallow) {
            ComputePartOfConstructionFinalField cp = new ComputePartOfConstructionFinalField();
            cp.go(typeInfo, cg);
        }
        ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
        return cao.go(cg);
    }

    private void doType(TypeInfo typeInfo) {
        LOGGER.debug("Do type {}", typeInfo);
        HiddenContentTypes hctType = computeHiddenContent.compute(typeInfo);
        typeInfo.analysis().set(HIDDEN_CONTENT_TYPES, hctType);

        // recurse
        typeInfo.subTypes().forEach(this::doType);
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            mi.methodBody().visit(e -> {
                if (e instanceof Lambda lambda) {
                    doType(lambda.methodInfo().typeInfo());
                    return false;
                }
                if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                    doType(cc.anonymousClass());
                    return false;
                }
                return true;
            });
            HiddenContentTypes hctMethod = computeHiddenContent.compute(hctType, mi);
            mi.analysis().set(HIDDEN_CONTENT_TYPES, hctMethod);

            if (!mi.isConstructor()) {
                HiddenContentSelector hcsMethod = HiddenContentSelector.selectAll(hctMethod, mi.returnType());
                mi.analysis().set(HCS_METHOD, hcsMethod);
            }
            for (ParameterInfo pi : mi.parameters()) {
                HiddenContentSelector hcsPi = HiddenContentSelector.selectAll(hctMethod, pi.parameterizedType());
                pi.analysis().set(HCS_PARAMETER, hcsPi);
            }

            doMethod(mi);
        });
    }
}
