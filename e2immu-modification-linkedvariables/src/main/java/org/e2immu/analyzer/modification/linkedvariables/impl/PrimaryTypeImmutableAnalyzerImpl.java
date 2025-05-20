package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.PrimaryTypeImmutableAnalyzer;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;

/*
Phase 4.1 Primary type immutable, independent

 */
public class PrimaryTypeImmutableAnalyzerImpl extends CommonAnalyzerImpl implements PrimaryTypeImmutableAnalyzer {
    private final AnalysisHelper analysisHelper = new AnalysisHelper();
    private final IteratingAnalyzer.Configuration configuration;

    public PrimaryTypeImmutableAnalyzerImpl(IteratingAnalyzer.Configuration configuration) {
        this.configuration = configuration;
    }

    private record OutputImpl(Set<Info> internalWaitFor,
                              Set<TypeInfo> externalWaitFor) implements Output {
        @Override
        public List<Throwable> problemsRaised() {
            return List.of();
        }
    }

    @Override
    public Output go(TypeInfo primaryType, boolean activateCycleBreaking) {
        ComputeImmutable ci = new ComputeImmutable();
        primaryType.recursiveSubTypeStream().forEach(ti -> ci.go(ti, activateCycleBreaking));
        return new OutputImpl(ci.internalWaitFor, ci.externalWaitFor);
    }

    private record ImmIndy(Value.Immutable immutable, Value.Independent independent) {
    }

    private class ComputeImmutable {
        Set<Info> internalWaitFor = new HashSet<>();
        Set<TypeInfo> externalWaitFor = new HashSet<>();

        void go(TypeInfo typeInfo, boolean activateCycleBreaking) {
            if (typeInfo.analysis().haveAnalyzedValueFor(IMMUTABLE_TYPE) && typeInfo.analysis().haveAnalyzedValueFor(INDEPENDENT_TYPE)) {
                return;
            }
            PrimaryTypeImmutableAnalyzerImpl.ImmIndy immIndy = computeImmutableType(typeInfo, activateCycleBreaking);
            if (immIndy != null) {
                if (immIndy.immutable != null) {
                    DECIDE.debug("Decide immutable = {} for type {}", immIndy.immutable, typeInfo);
                    typeInfo.analysis().setAllowControlledOverwrite(IMMUTABLE_TYPE, immIndy.immutable);
                } else {
                    UNDECIDED.debug("Immutable of type {} undecided, wait for internal {}, external {}", typeInfo,
                            internalWaitFor, externalWaitFor);
                }
                if (immIndy.independent != null) {
                    DECIDE.debug("Decide independent = {} for type {}", immIndy.independent, typeInfo);
                    typeInfo.analysis().setAllowControlledOverwrite(INDEPENDENT_TYPE, immIndy.independent);
                } else {
                    UNDECIDED.debug("Independent of type {} undecided, wait for internal {}, external {}", typeInfo,
                            internalWaitFor, externalWaitFor);
                }
            } else {
                UNDECIDED.debug("Immutable+independent of type {} undecided because of hierarchy, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }


        private PrimaryTypeImmutableAnalyzerImpl.ImmIndy computeImmutableType(TypeInfo typeInfo, boolean activateCycleBreaking) {
            boolean fieldsAssignable = typeInfo.fields().stream().anyMatch(fi -> !fi.isPropertyFinal());
            Value.Immutable immFromHierarchy = IMMUTABLE;
            if (fieldsAssignable) immFromHierarchy = MUTABLE;
            Value.Independent indyFromHierarchy = INDEPENDENT;

            // hierarchy

            TypeInfo parent = typeInfo.parentClass().typeInfo();

            Value.Immutable immutableParent = parent.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
            Value.Immutable immutableParentBroken;
            Value.Independent independentParent = parent.analysis().getOrNull(INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
            Value.Independent independentParentBroken;

            assert (immutableParent == null) == (independentParent == null);
            boolean stopExternal = false;
            if (immutableParent == null) {
                if (activateCycleBreaking) {
                    if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                        immutableParentBroken = HiddenContentTypes.hasHc(parent) ? IMMUTABLE_HC : IMMUTABLE;
                        independentParentBroken = INDEPENDENT;
                    } else {
                        immutableParentBroken = MUTABLE;
                        independentParentBroken = DEPENDENT;
                    }
                } else {
                    externalWaitFor.add(parent);
                    stopExternal = true;
                    immutableParentBroken = MUTABLE; // not relevant
                    independentParentBroken = DEPENDENT; // not relevant
                }
            } else {
                immutableParentBroken = immutableParent;
                independentParentBroken = independentParent;
            }
            immFromHierarchy = immutableParentBroken.min(immFromHierarchy);
            indyFromHierarchy = independentParentBroken.min(indyFromHierarchy);

            for (ParameterizedType superType : typeInfo.interfacesImplemented()) {
                Value.Immutable immutableSuper = superType.typeInfo().analysis().getOrNull(IMMUTABLE_TYPE,
                        ValueImpl.ImmutableImpl.class);
                Value.Independent independentSuper = superType.typeInfo().analysis().getOrNull(INDEPENDENT_TYPE,
                        ValueImpl.IndependentImpl.class);
                Value.Immutable immutableSuperBroken;
                Value.Independent independentSuperBroken;
                if (immutableSuper == null || independentSuper == null) {
                    if (activateCycleBreaking) {
                        if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                            immutableSuperBroken = HiddenContentTypes.hasHc(superType.typeInfo()) ? IMMUTABLE_HC : IMMUTABLE;
                            independentSuperBroken = INDEPENDENT;
                        } else {
                            immutableSuperBroken = MUTABLE;
                            independentSuperBroken = DEPENDENT;
                        }
                    } else {
                        externalWaitFor.add(superType.typeInfo());
                        immutableSuperBroken = Objects.requireNonNullElse(immutableSuper, MUTABLE); // not relevant
                        independentSuperBroken = Objects.requireNonNullElse(independentSuper, DEPENDENT); // not relevant
                    }
                    stopExternal = true;
                } else {
                    immutableSuperBroken = immutableSuper;
                    independentSuperBroken = independentSuper;
                }
                immFromHierarchy = immutableSuperBroken.min(immFromHierarchy);
                indyFromHierarchy = independentSuperBroken.min(indyFromHierarchy);
            }
            if (stopExternal) {
                return null;
            }

            // fields 1

            boolean finalFields = typeInfo.fields().stream().allMatch(FieldInfo::isPropertyFinal);

            // fields and abstract methods

            ImmIndy immIndy = loopOverFieldsAndAbstractMethods(typeInfo);
            boolean immFromField;
            boolean indyFromField;
            if (immIndy.isImmutable == null) {
                if (finalFields && immFromHierarchy.isAtLeastImmutableHC()) {
                    // we'll have to wait
                    return new PrimaryTypeImmutableAnalyzerImpl.ImmIndy(null, indyFromHierarchy);
                }
                immFromField = false;
            } else {
                immFromField = immIndy.isImmutable;
            }
            if (immIndy.isIndependent == null) {
                if (indyFromHierarchy.isAtLeastIndependentHc()) {
                    // we'll have to wait
                    return new PrimaryTypeImmutableAnalyzerImpl.ImmIndy(null, null);
                }
                indyFromField = false;
            } else {
                indyFromField = immIndy.isIndependent;
            }

            assert immFromHierarchy != null;
            assert indyFromHierarchy != null;
            if (immFromField && immFromHierarchy.isAtLeastImmutableHC()) {
                immFromHierarchy = HiddenContentTypes.hasHc(typeInfo) ? IMMUTABLE_HC : IMMUTABLE;
            }
            if (!indyFromField) {
                indyFromHierarchy = DEPENDENT;
            }
            if (!immFromField) {
                immFromHierarchy = FINAL_FIELDS.min(immFromHierarchy);
            }
            return new PrimaryTypeImmutableAnalyzerImpl.ImmIndy(immFromHierarchy, indyFromHierarchy);
        }

        private record ImmIndy(Boolean isImmutable, Boolean isIndependent) {
        }

        private ImmIndy loopOverFieldsAndAbstractMethods(TypeInfo typeInfo) {
            // fields should be private, or immutable for the type to be immutable
            // fields should not be @Modified nor assigned to
            // fields should not be @Dependent
            Boolean isImmutable = true;
            Boolean isIndependent = true;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                if (!fieldInfo.access().isPrivate()) {
                    Immutable immutable = analysisHelper.typeImmutableNullIfUndecided(fieldInfo.type());
                    if (immutable == null) {
                        externalWaitFor.add(fieldInfo.type().bestTypeInfo());
                        isImmutable = null;
                    } else if (!immutable.isAtLeastImmutableHC()) {
                        isImmutable = false;
                    }
                }
                Value.Bool fieldUnmodified = fieldInfo.analysis().getOrNull(UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
                if (fieldUnmodified == null) {
                    internalWaitFor.add(fieldInfo);
                    isImmutable = null;
                } else if (fieldUnmodified.isFalse()) {
                    isImmutable = false;
                }
                Value.Independent fieldIndependent = fieldInfo.analysis().getOrNull(INDEPENDENT_FIELD,
                        ValueImpl.IndependentImpl.class);
                if (fieldIndependent == null) {
                    internalWaitFor.add(fieldInfo);
                    isIndependent = null;
                } else if (fieldIndependent.isDependent()) {
                    isIndependent = false;
                    isImmutable = false;
                }
                if (isImmutable != null && !isImmutable && isIndependent != null && !isIndependent) {
                    return new ImmIndy(false, false);
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.isAbstract()) {
                    Value.Bool nonModifying = methodInfo.analysis().getOrNull(NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
                    if (nonModifying == null) {
                        internalWaitFor.add(methodInfo);
                        isImmutable = null;
                    } else if (nonModifying.isFalse()) {
                        isImmutable = false;
                    }
                    Value.Independent methodIndependent = methodInfo.analysis().getOrNull(INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.class);
                    if (methodIndependent == null) {
                        internalWaitFor.add(methodInfo);
                        isIndependent = null;
                    } else if (methodIndependent.isDependent()) {
                        isIndependent = false;
                        isImmutable = false;
                    }
                    for (ParameterInfo pi : methodInfo.parameters()) {
                        Value.Independent paramIndependent = pi.analysis().getOrNull(INDEPENDENT_PARAMETER,
                                ValueImpl.IndependentImpl.class);
                        if (paramIndependent == null) {
                            internalWaitFor.add(methodInfo);
                            isIndependent = null;
                        } else if (paramIndependent.isDependent()) {
                            isIndependent = false;
                            isImmutable = false;
                        }
                    }
                    if (isImmutable != null && !isImmutable && isIndependent != null && !isIndependent) {
                        return new ImmIndy(false, false);
                    }
                }
            }
            return new ImmIndy(isImmutable, isIndependent);
        }

    }
}
