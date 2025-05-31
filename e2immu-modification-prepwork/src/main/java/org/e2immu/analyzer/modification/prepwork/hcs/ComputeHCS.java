package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;

import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

public class ComputeHCS {

    private final ComputeHiddenContent chc;
    private final Runtime runtime;

    public ComputeHCS(Runtime runtime) {
        this.chc = new ComputeHiddenContent(runtime);
        this.runtime = runtime;
    }

    public void doPrimitives() {
        for (TypeInfo typeInfo : runtime.primitives()) {
            doType(typeInfo);
        }
    }

    public void doType(TypeInfo typeInfo) {
        HiddenContentTypes hctType = typeInfo.analysis().getOrCreate(HIDDEN_CONTENT_TYPES, () -> chc.compute(typeInfo));
        typeInfo.subTypes().forEach(this::doType);
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            HiddenContentTypes hctMethod = mi.analysis().getOrCreate(HIDDEN_CONTENT_TYPES, () -> chc.compute(hctType, mi));
            mi.analysis().getOrCreate(HCS_METHOD, () -> {
                if (mi.isConstructor()) {
                    return HiddenContentSelector.selectAll(hctType, typeInfo.asParameterizedType());
                }
                return HiddenContentSelector.selectAll(hctMethod, mi.returnType());
            });
            for (ParameterInfo pi : mi.parameters()) {
                pi.analysis().getOrCreate(HCS_PARAMETER, () ->
                        HiddenContentSelector.selectAll(hctMethod, pi.parameterizedType()));
            }
        });
    }

    public HiddenContentSelector doHiddenContentSelector(MethodInfo methodInfo) {
        ParameterizedType returnType = methodInfo.returnType();
        HiddenContentTypes hctMethod = methodInfo.analysis()
                .getOrCreate(HiddenContentTypes.HIDDEN_CONTENT_TYPES, () -> {
                    HiddenContentTypes hctType = methodInfo.typeInfo().analysis().getOrCreate(HIDDEN_CONTENT_TYPES,
                            () -> chc.compute(methodInfo.typeInfo()));
                    return methodInfo.analysis().getOrCreate(HIDDEN_CONTENT_TYPES, () -> chc.compute(hctType, methodInfo));
                });
        assert hctMethod != null : "No HCT for " + methodInfo;
        HiddenContentSelector hcs;
        if (!methodInfo.analysis().haveAnalyzedValueFor(HCS_METHOD)) {
            if (methodInfo.isConstructor()) {
                hcs = HiddenContentSelector.selectAll(hctMethod, methodInfo.typeInfo().asParameterizedType());
            } else {
                hcs = HiddenContentSelector.selectAll(hctMethod, returnType);
            }
            methodInfo.analysis().set(HCS_METHOD, hcs);
        } else {
            hcs = methodInfo.analysis().getOrNull(HCS_METHOD, HiddenContentSelector.class);
        }
        methodInfo.parameters().forEach(pi -> {
            if (!pi.analysis().haveAnalyzedValueFor(HCS_PARAMETER)) {
                ParameterizedType pt = methodInfo.parameters().get(pi.index()).parameterizedType();
                HiddenContentSelector hcsPa = HiddenContentSelector.selectAll(hctMethod, pt);
                pi.analysis().set(HCS_PARAMETER, hcsPa);
            }
        });
        return hcs;
    }

    public static HiddenContentSelector safeHcsMethod(Runtime runtime, MethodInfo methodInfo) {
        HiddenContentSelector hcsMethod = methodInfo.analysis().getOrNull(HCS_METHOD, HiddenContentSelector.class);
        if (hcsMethod == null && methodInfo.isSyntheticArrayConstructor()) {
            ComputeHiddenContent computeHiddenContent = new ComputeHiddenContent(runtime);
            HiddenContentTypes hctType = methodInfo.typeInfo().analysis().getOrCreate(HIDDEN_CONTENT_TYPES,
                    () -> computeHiddenContent.compute(methodInfo.typeInfo()));
            HiddenContentTypes hctMethod = computeHiddenContent.compute(hctType, methodInfo);
            methodInfo.analysis().set(HIDDEN_CONTENT_TYPES, hctMethod);
            return new ComputeHCS(runtime).doHiddenContentSelector(methodInfo);
        }
        if (hcsMethod == null) {
            throw new UnsupportedOperationException("Have no hidden content selector computed for " + methodInfo);
        }
        return hcsMethod;
    }
}
