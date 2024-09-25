package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;

public class ComputeHCS {
    private final ComputeHiddenContent chc;

    public ComputeHCS(Runtime runtime) {
        this.chc = new ComputeHiddenContent(runtime);
    }

    public HiddenContentTypes hctOfType(TypeInfo typeInfo) {
        HiddenContentTypes hctType = typeInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        if (hctType == NO_VALUE) {
            hctType = chc.compute(typeInfo);
            typeInfo.analysis().set(HIDDEN_CONTENT_TYPES, hctType);
        }
        return hctType;
    }

    public HiddenContentTypes hctOfMethod(MethodInfo methodInfo, HiddenContentTypes hctMethod) {
        if (hctMethod == NO_VALUE) {
            HiddenContentTypes hctType = hctOfType(methodInfo.typeInfo());
            hctMethod = chc.compute(hctType, methodInfo);
        }
        return hctMethod;
    }

    public HiddenContentSelector hcsOfMethod(MethodInfo methodInfo) {
        HiddenContentTypes hctMethod = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES,
                NO_VALUE);
        hctMethod = hctOfMethod(methodInfo, hctMethod);
        return HiddenContentSelector.selectAll(hctMethod, methodInfo.returnType());
    }

    public HiddenContentSelector hcsOfParameter(ParameterInfo parameterInfo) {
        HiddenContentTypes hctOfMethod = hctOfMethod(parameterInfo.methodInfo(), hctOfType(parameterInfo.typeInfo()));
        return HiddenContentSelector.selectAll(hctOfMethod, parameterInfo.parameterizedType());
    }
}
