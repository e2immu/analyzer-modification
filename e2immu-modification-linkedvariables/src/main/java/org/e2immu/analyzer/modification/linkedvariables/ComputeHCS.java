package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.runtime.Runtime;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;

public class ComputeHCS {
    private final ComputeHiddenContent chc;

    public ComputeHCS(Runtime runtime) {
        this.chc = new ComputeHiddenContent(runtime);
    }

    public HiddenContentSelector hcsOfMethod(MethodInfo methodInfo) {
        HiddenContentTypes hctMethod = methodInfo.analysis().getOrDefault(HIDDEN_CONTENT_TYPES,
                NO_VALUE);
        if (hctMethod == NO_VALUE) {
            HiddenContentTypes hctType = methodInfo.typeInfo().analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
            if (hctType == NO_VALUE) {
                hctType = chc.compute(methodInfo.typeInfo());
                methodInfo.typeInfo().analysis().set(HIDDEN_CONTENT_TYPES, hctType);
            }
            hctMethod = chc.compute(hctType, methodInfo);
        }
        return HiddenContentSelector.selectAll(hctMethod, methodInfo.returnType());
    }
}
