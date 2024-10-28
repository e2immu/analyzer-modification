package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        MethodInfo overrideWithMostHiddenContent = overloadWithMostHiddenContent(methodInfo);
        ParameterizedType returnType = overrideWithMostHiddenContent.returnType();
        HiddenContentTypes hctOverride = overrideWithMostHiddenContent.analysis()
                .getOrNull(HiddenContentTypes.HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
        assert hctOverride != null : "No HCT for " + overrideWithMostHiddenContent;
        HiddenContentSelector hcs;
        if (!methodInfo.analysis().haveAnalyzedValueFor(HCS_METHOD) && !methodInfo.isConstructor()) {
            hcs = HiddenContentSelector.selectAll(hctOverride, returnType);
            methodInfo.analysis().set(HCS_METHOD, hcs);
        } else {
            hcs = null;
        }
        methodInfo.parameters().forEach(pi -> {
            if (!pi.analysis().haveAnalyzedValueFor(HCS_PARAMETER)) {
                ParameterizedType pt = overrideWithMostHiddenContent.parameters().get(pi.index()).parameterizedType();
                HiddenContentSelector hcsPa = HiddenContentSelector.selectAll(hctOverride, pt);
                pi.analysis().set(HCS_PARAMETER, hcsPa);
            }
        });
        return hcs;
    }


    private MethodInfo overloadWithMostHiddenContent(MethodInfo methodInfo) {
        Set<MethodInfo> overrides = methodInfo.overrides();
        if (overrides.isEmpty()) return methodInfo;
        overrides.forEach(mi -> doType(mi.typeInfo()));
        if (overrides.size() == 1) return overrides.stream().findFirst().orElseThrow();
        Map<MethodInfo, Integer> map = overrides.stream()
                .collect(Collectors.toUnmodifiableMap(m -> m, this::countHiddenContent));
        return overrides.stream().min((o1, o2) -> map.get(o2) - map.get(o1)).orElseThrow();
    }

    private int countHiddenContent(MethodInfo methodInfo) {
        HiddenContentTypes hct = methodInfo.analysis().getOrNull(HiddenContentTypes.HIDDEN_CONTENT_TYPES,
                HiddenContentTypes.class);
        assert hct != null;
        Stream<ParameterizedType> types = Stream.concat(Stream.of(methodInfo.returnType()),
                methodInfo.parameters().stream().map(Variable::parameterizedType));
        return (int) types.filter(t -> hct.indexOfOrNull(t) != null).count();
    }

}
