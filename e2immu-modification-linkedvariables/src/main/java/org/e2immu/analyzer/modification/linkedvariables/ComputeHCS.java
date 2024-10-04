package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;

import java.util.Arrays;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

public class ComputeHC {
    private final ComputeHiddenContent chc;
    private final Runtime runtime;

    public ComputeHC(Runtime runtime) {
        this.chc = new ComputeHiddenContent(runtime);
        this.runtime = runtime;
    }

    public void doPredefinedObjects(Runtime runtime) {
        for (TypeInfo typeInfo : runtime.primitives()) {
            doTypeInternally(typeInfo);
        }
        for (TypeInfo typeInfo : runtime.predefinedObjects()) {
            doTypeInternally(typeInfo);
        }
    }

    public void doType(Class<?>... classes) {
        Arrays.stream(classes)
                .map(c -> runtime.getFullyQualified(c, true))
                .forEach(this::doType);
    }

    public void doType(TypeInfo typeInfo) {
        doTypeInternally(typeInfo);
    }

    private void doTypeInternally(TypeInfo typeInfo) {
        if(typeInfo.analysis().haveAnalyzedValueFor(HIDDEN_CONTENT_TYPES)) return;
        HiddenContentTypes hctType = chc.compute(typeInfo);
        typeInfo.analysis().set(HIDDEN_CONTENT_TYPES, hctType);
        typeInfo.subTypes().forEach(this::doTypeInternally);
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            HiddenContentTypes hctMethod = chc.compute(hctType, mi);
            mi.analysis().set(HIDDEN_CONTENT_TYPES, hctMethod);
            if (!mi.isConstructor()) {
                HiddenContentSelector hcsMethod = HiddenContentSelector.selectAll(hctMethod, mi.returnType());
                mi.analysis().set(HCS_METHOD, hcsMethod);
            }
            for (ParameterInfo pi : mi.parameters()) {
                HiddenContentSelector hcsPi = HiddenContentSelector.selectAll(hctMethod, pi.parameterizedType());
                pi.analysis().set(HCS_PARAMETER, hcsPi);
            }
        });
    }
}
