package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.AbstractInfoAnalyzer;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;

public class AbstractInfoAnalyzerImpl extends CommonAnalyzerImpl implements AbstractInfoAnalyzer {

    // given an abstract type, which are its concrete extensions?
    final Map<TypeInfo, Set<TypeInfo>> concreteExtensionsOfAbstractTypes = new HashMap<>();

    // given an abstract method, which are its concrete implementations?
    final Map<MethodInfo, Set<MethodInfo>> concreteImplementationsOfAbstractMethods = new HashMap<>();

    public AbstractInfoAnalyzerImpl(Set<TypeInfo> primaryTypes) {
        primaryTypes.stream().flatMap(TypeInfo::recursiveSubTypeStream).forEach(typeInfo -> {
            if (!typeInfo.isAbstract()) {
                typeInfo.typeHierarchyExcludingJLOStream()
                        // stay within our source code
                        .filter(st -> primaryTypes.contains(st.primaryType()))
                        .forEach(st -> {
                            if (st.isAbstract()) {
                                concreteExtensionsOfAbstractTypes.computeIfAbsent(st, t -> new HashSet<>())
                                        .add(typeInfo);
                            }
                        });
            }
            typeInfo.methodStream().filter(mi -> !mi.isAbstract()).forEach(mi -> {
                mi.overrides().stream()
                        // override of the non-abstract method must be in our source code, and must be abstract itself
                        .filter(MethodInfo::isAbstract)
                        .filter(ov -> primaryTypes.contains(ov.typeInfo().primaryType()))
                        .forEach(ov -> {
                            concreteImplementationsOfAbstractMethods.computeIfAbsent(ov, m -> new HashSet<>())
                                    .add(mi);
                        });
            });
        });
    }

    private record OutputImpl(List<Throwable> problemsRaised,
                              Set<TypeInfo> waitForTypes,
                              Set<MethodInfo> waitForMethods) implements Output {

    }

    @Override
    public Output go() {
        Set<MethodInfo> waitForMethods = new HashSet<>();
        Iterator<Map.Entry<MethodInfo, Set<MethodInfo>>> iterator = concreteImplementationsOfAbstractMethods.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MethodInfo, Set<MethodInfo>> entry = iterator.next();
            Set<MethodInfo> waitForOfMethod = resolve(entry.getKey(), entry.getValue());
            if (waitForOfMethod.isEmpty()) {
                iterator.remove();
            } else {
                waitForMethods.addAll(waitForOfMethod);
            }
        }
        Set<TypeInfo> waitForTypes = new HashSet<>();
        Iterator<Map.Entry<TypeInfo, Set<TypeInfo>>> iterator2 = concreteExtensionsOfAbstractTypes.entrySet().iterator();
        while (iterator2.hasNext()) {
            Map.Entry<TypeInfo, Set<TypeInfo>> entry = iterator2.next();
            Set<TypeInfo> waitForOfType = resolve(entry.getKey(), entry.getValue());
            if (waitForOfType.isEmpty()) {
                iterator2.remove();
            } else {
                waitForTypes.addAll(waitForOfType);
            }
        }
        return new OutputImpl(List.of(), waitForTypes, waitForMethods);
    }

    private Set<MethodInfo> resolve(MethodInfo methodInfo, Set<MethodInfo> concreteImplementations) {
        Set<MethodInfo> waitFor = new HashSet<>();
        for (ParameterInfo pi : methodInfo.parameters()) {
            waitFor.addAll(unmodified(concreteImplementations, pi));
            waitFor.addAll(independent(concreteImplementations, pi));
            waitFor.addAll(collectDowncast(concreteImplementations, pi));
        }
        waitFor.addAll(methodNonModifying(concreteImplementations, methodInfo));
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
        Value.Bool independent = pi.analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.BoolImpl.class);
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
                DECIDE.debug("Independent of param {} = {}", pi, fromImplementations);
            } else {
                UNDECIDED.debug("Cannot decide on independent param {}, wait for {}", pi, waitFor);
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
                DECIDE.debug("NonModifying of method {} = {}", methodInfo, fromImplementations);
            } else {
                UNDECIDED.debug("Cannot decide on non-modifying method {}, wait for {}", methodInfo, waitFor);
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
                DECIDE.debug("Unmodified of param {} = {}", pi, fromImplementations);
            } else {
                UNDECIDED.debug("Cannot decide on nonmodifying param {}, wait for {}", pi, waitFor);
            }
            return waitFor;
        }
        return Set.of();
    }

    private Set<TypeInfo> resolve(TypeInfo methodInfo, Set<TypeInfo> concreteExtensions) {
        throw new UnsupportedOperationException();
    }
}
