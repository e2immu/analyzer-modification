package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.AbstractMethodAnalyzer;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;

public class AbstractMethodAnalyzerImpl extends CommonAnalyzerImpl implements AbstractMethodAnalyzer {

    // given an abstract method, which are its concrete implementations?
    final Map<MethodInfo, Set<MethodInfo>> concreteImplementationsOfAbstractMethods = new HashMap<>();

    public AbstractMethodAnalyzerImpl(Set<TypeInfo> primaryTypes) {
        primaryTypes.stream().flatMap(TypeInfo::recursiveSubTypeStream).forEach(typeInfo ->
                typeInfo.methodStream().filter(mi -> !mi.isAbstract()).forEach(mi ->
                        mi.overrides().stream()
                                // override of the non-abstract method must be in our source code, and must be abstract itself
                                .filter(MethodInfo::isAbstract)
                                .filter(ov -> primaryTypes.contains(ov.typeInfo().primaryType()))
                                .forEach(ov ->
                                        concreteImplementationsOfAbstractMethods
                                                .computeIfAbsent(ov, m -> new HashSet<>()).add(mi))));
    }

    private record OutputImpl(List<Throwable> problemsRaised, Set<MethodInfo> waitForMethods) implements Output {
    }

    @Override
    public Output go() {
        Set<MethodInfo> waitForMethods = new HashSet<>();
        Iterator<Map.Entry<MethodInfo, Set<MethodInfo>>> iterator = concreteImplementationsOfAbstractMethods.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MethodInfo, Set<MethodInfo>> entry = iterator.next();
            MethodInfo methodInfo = entry.getKey();
            assert methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class) == null
                   || methodInfo.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class) == null
                   || methodInfo.parameters().stream().anyMatch(pi ->
                    pi.analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class) == null
                    || pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class) == null);
            Set<MethodInfo> waitForOfMethod = resolve(methodInfo, entry.getValue());
            if (waitForOfMethod.isEmpty()) {
                iterator.remove();
            } else {
                waitForMethods.addAll(waitForOfMethod);
            }
        }
        return new OutputImpl(List.of(), waitForMethods);
    }

    private Set<MethodInfo> resolve(MethodInfo methodInfo, Set<MethodInfo> concreteImplementations) {
        Set<MethodInfo> waitFor = new HashSet<>();
        for (ParameterInfo pi : methodInfo.parameters()) {
            waitFor.addAll(unmodified(concreteImplementations, pi));
            waitFor.addAll(independent(concreteImplementations, pi));
            waitFor.addAll(collectDowncast(concreteImplementations, pi));
        }
        waitFor.addAll(methodNonModifying(concreteImplementations, methodInfo));
        waitFor.addAll(methodIndependent(concreteImplementations, methodInfo));
        return waitFor;
    }

    private static Set<MethodInfo> collectDowncast(Set<MethodInfo> concreteImplementations, ParameterInfo pi) {
        Value.SetOfTypeInfo downcastsValue = pi.analysis().getOrNull(PropertyImpl.DOWNCAST_PARAMETER, Value.SetOfTypeInfo.class);
        if (downcastsValue == null) {
            Set<MethodInfo> waitFor = new HashSet<>();
            Set<TypeInfo> downcasts = new HashSet<>();
            for (MethodInfo implementation : concreteImplementations) {
                ParameterInfo pii = implementation.parameters().get(pi.index());
                Value.SetOfTypeInfo downcastsImplValue = pii.analysis().getOrNull(PropertyImpl.DOWNCAST_PARAMETER,
                        Value.SetOfTypeInfo.class);
                if (downcastsImplValue == null) {
                    waitFor.add(implementation);
                } else {
                    downcasts.addAll(downcastsImplValue.typeInfoSet());
                }
            }
            if (!waitFor.isEmpty()) {
                return waitFor;
            }
            pi.analysis().set(PropertyImpl.DOWNCAST_PARAMETER, new ValueImpl.SetOfTypeInfoImpl(Set.copyOf(downcasts)));
        }
        return Set.of();
    }

    private static Set<MethodInfo> independent(Set<MethodInfo> concreteImplementations, ParameterInfo pi) {
        Value.Independent independent = pi.analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class);
        if (independent == null) {
            Set<MethodInfo> waitFor = new HashSet<>();
            Value.Independent fromImplementations = ValueImpl.IndependentImpl.INDEPENDENT;
            for (MethodInfo implementation : concreteImplementations) {
                ParameterInfo pii = implementation.parameters().get(pi.index());
                Value.Independent independentImpl = pii.analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER,
                        ValueImpl.IndependentImpl.class);
                if (independentImpl == null) {
                    waitFor.add(implementation);
                    fromImplementations = null;
                } else if (fromImplementations != null) {
                    fromImplementations = fromImplementations.min(independentImpl);
                    if (fromImplementations.isDependent()) break;
                }
            }
            if (fromImplementations != null) {
                pi.analysis().set(PropertyImpl.INDEPENDENT_PARAMETER, fromImplementations);
                DECIDE.debug("Decide independent of param {} = {}", pi, fromImplementations);
            } else {
                UNDECIDED.debug("Independent of param {} undecided, wait for {}", pi, waitFor);
            }
            return waitFor;
        }
        return Set.of();
    }


    private Set<MethodInfo> methodNonModifying(Set<MethodInfo> concreteImplementations, MethodInfo methodInfo) {
        Value.Bool nonModifying = methodInfo.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                ValueImpl.BoolImpl.class);
        if (nonModifying == null) {
            Set<MethodInfo> waitFor = new HashSet<>();
            Value.Bool fromImplementations = ValueImpl.BoolImpl.TRUE;
            for (MethodInfo implementation : concreteImplementations) {
                Value.Bool nonModImpl = implementation.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                        ValueImpl.BoolImpl.class);
                if (nonModImpl == null) {
                    waitFor.add(implementation);
                    fromImplementations = null;
                } else if (nonModImpl.isFalse() && fromImplementations != null) {
                    fromImplementations = ValueImpl.BoolImpl.FALSE;
                    break;
                }
            }
            if (fromImplementations != null) {
                methodInfo.analysis().set(PropertyImpl.NON_MODIFYING_METHOD, fromImplementations);
                DECIDE.debug("Decide non-modifying of method {} = {}", methodInfo, fromImplementations);
            } else {
                UNDECIDED.debug("Non-modifying method {} undecided, wait for {}", methodInfo, waitFor);
            }
            return waitFor;
        }
        return Set.of();
    }


    private Set<MethodInfo> methodIndependent(Set<MethodInfo> concreteImplementations, MethodInfo methodInfo) {
        Value.Independent independent = methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD,
                ValueImpl.IndependentImpl.class);
        if (independent == null) {
            Set<MethodInfo> waitFor = new HashSet<>();
            Value.Independent fromImplementations = ValueImpl.IndependentImpl.INDEPENDENT;
            for (MethodInfo implementation : concreteImplementations) {
                Value.Independent independentImpl = implementation.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD,
                        ValueImpl.IndependentImpl.class);
                if (independentImpl == null) {
                    waitFor.add(implementation);
                    fromImplementations = null;
                } else if (fromImplementations != null) {
                    fromImplementations = fromImplementations.min(independentImpl);
                    if (fromImplementations.isDependent()) break;
                }
            }
            if (fromImplementations != null) {
                methodInfo.analysis().set(PropertyImpl.INDEPENDENT_METHOD, fromImplementations);
                DECIDE.debug("Decide independent of method {} = {}", methodInfo, fromImplementations);
            } else {
                UNDECIDED.debug("Independent of method {} undecided, wait for {}", methodInfo, waitFor);
            }
            return waitFor;
        }
        return Set.of();
    }

    private static Set<MethodInfo> unmodified(Set<MethodInfo> concreteImplementations, ParameterInfo pi) {
        Value.Bool unmodified = pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class);
        if (unmodified == null) {
            Set<MethodInfo> waitFor = new HashSet<>();
            Value.Bool fromImplementations = ValueImpl.BoolImpl.TRUE;
            for (MethodInfo implementation : concreteImplementations) {
                ParameterInfo pii = implementation.parameters().get(pi.index());
                Value.Bool unmodifiedImpl = pii.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER,
                        ValueImpl.BoolImpl.class);
                if (unmodifiedImpl == null) {
                    waitFor.add(implementation);
                    fromImplementations = null;
                } else if (unmodifiedImpl.isFalse() && fromImplementations != null) {
                    fromImplementations = ValueImpl.BoolImpl.FALSE;
                    break;
                }
            }
            if (fromImplementations != null) {
                pi.analysis().set(PropertyImpl.UNMODIFIED_PARAMETER, fromImplementations);
                DECIDE.debug("Decide unmodified of param {} = {}", pi, fromImplementations);
            } else {
                UNDECIDED.debug("Non-modifying of param {} undecided, wait for {}", pi, waitFor);
            }
            return waitFor;
        }
        return Set.of();
    }
}
