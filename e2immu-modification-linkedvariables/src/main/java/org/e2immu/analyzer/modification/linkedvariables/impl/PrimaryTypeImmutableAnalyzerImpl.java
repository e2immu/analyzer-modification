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
            ImmIndy immIndy = computeImmutableType(typeInfo, activateCycleBreaking);
            if (immIndy != null) {
                DECIDE.debug("Decide immutable = {}, independent = {} for type {}", immIndy.immutable,
                        immIndy.independent, typeInfo);
                typeInfo.analysis().setAllowControlledOverwrite(IMMUTABLE_TYPE, immIndy.immutable);
                typeInfo.analysis().setAllowControlledOverwrite(INDEPENDENT_TYPE, immIndy.independent);

            } else {
                UNDECIDED.debug("Immutable+independent of type {} undecided, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }


        private ImmIndy computeImmutableType(TypeInfo typeInfo, boolean activateCycleBreaking) {
            boolean fieldsAssignable = typeInfo.fields().stream().anyMatch(fi -> !fi.isPropertyFinal());
            Value.Immutable immutable = IMMUTABLE;
            if (fieldsAssignable) immutable = MUTABLE;
            Value.Independent independent = INDEPENDENT;

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
            immutable = immutableParentBroken.min(immutable);
            independent = independentParentBroken.min(independent);

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
                immutable = immutableSuperBroken.min(immutable);
                independent = independentSuperBroken.min(independent);
            }
            if (stopExternal) {
                return null;
            }

            // fields

            IsImmIndy isImmIndy = loopOverFieldsAndAbstractMethods(typeInfo);
            if (isImmIndy == null) return null;

            assert immutable != null;
            assert independent != null;
            if (isImmIndy.isImmutable && immutable.isAtLeastImmutableHC()) {
                immutable = HiddenContentTypes.hasHc(typeInfo) ? IMMUTABLE_HC : IMMUTABLE;
            }
            if (!isImmIndy.isIndependent) {
                independent = DEPENDENT;
            }
            if (!isImmIndy.isImmutable) {
                immutable = FINAL_FIELDS.min(immutable);
            }
            return new ImmIndy(immutable, independent);
        }

        private record IsImmIndy(boolean isImmutable, boolean isIndependent) {
        }

        private IsImmIndy loopOverFieldsAndAbstractMethods(TypeInfo typeInfo) {
            // fields should be private, or immutable for the type to be immutable
            // fields should not be @Modified nor assigned to
            // fields should not be @Dependent
            boolean undecided = false;
            boolean isImmutable = true;
            boolean isIndependent = true;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                if (!fieldInfo.isPropertyFinal()) isImmutable = false;
                else if (!fieldInfo.access().isPrivate()) {
                    Immutable immutable = analysisHelper.typeImmutableNullIfUndecided(fieldInfo.type());
                    if (immutable == null) {
                        externalWaitFor.add(fieldInfo.type().bestTypeInfo());
                        undecided = true;
                    } else if (!immutable.isAtLeastImmutableHC()) {
                        isImmutable = false;
                    }
                }
                Value.Bool fieldUnmodified = fieldInfo.analysis().getOrNull(UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
                if (fieldUnmodified == null) {
                    internalWaitFor.add(fieldInfo);
                    undecided = true;
                } else if (fieldUnmodified.isFalse()) {
                    isImmutable = false;
                }
                Value.Independent fieldIndependent = fieldInfo.analysis().getOrNull(INDEPENDENT_FIELD,
                        ValueImpl.IndependentImpl.class);
                if (fieldIndependent == null) {
                    internalWaitFor.add(fieldInfo);
                    undecided = true;
                } else if (fieldIndependent.isDependent()) {
                    isIndependent = false;
                    isImmutable = false;
                }

                if (!undecided && !isImmutable && !isIndependent) {
                    return new IsImmIndy(false, false);
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.isAbstract()) {
                    Value.Bool nonModifying = methodInfo.analysis().getOrNull(NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
                    if (nonModifying == null) {
                        internalWaitFor.add(methodInfo);
                        undecided = true;
                    } else if (nonModifying.isFalse()) {
                        isImmutable = false;
                    }
                    Value.Independent methodIndependent = methodInfo.analysis().getOrNull(INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.class);
                    if (methodIndependent == null) {
                        internalWaitFor.add(methodInfo);
                        undecided = true;
                    } else if (methodIndependent.isDependent()) {
                        isIndependent = false;
                        isImmutable = false;
                    }
                    for (ParameterInfo pi : methodInfo.parameters()) {
                        Value.Independent paramIndependent = pi.analysis().getOrNull(INDEPENDENT_PARAMETER,
                                ValueImpl.IndependentImpl.class);
                        if (paramIndependent == null) {
                            internalWaitFor.add(methodInfo);
                            undecided = true;
                        } else if (paramIndependent.isDependent()) {
                            isIndependent = false;
                            isImmutable = false;
                        }
                    }
                    if (!undecided && !isImmutable && !isIndependent) {
                        return new IsImmIndy(false, false);
                    }
                }
            }
            return undecided ? null : new IsImmIndy(isImmutable, isIndependent);
        }

    }
}
