package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.PrimaryTypeImmutableAnalyzer;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.*;

public class PrimaryTypeImmutableAnalyzerImpl extends CommonAnalyzerImpl implements PrimaryTypeImmutableAnalyzer {
    private final AnalysisHelper analysisHelper = new AnalysisHelper();
    private final IteratingAnalyzer.Configuration configuration;

    public PrimaryTypeImmutableAnalyzerImpl(IteratingAnalyzer.Configuration configuration) {
        this.configuration = configuration;
    }

    private record OutputImpl(Set<FieldInfo> internalWaitFor,
                              Set<TypeInfo> externalWaitFor) implements Output {
        @Override
        public List<Throwable> problemsRaised() {
            return List.of();
        }
    }

    @Override
    public Output go(TypeInfo primaryType, boolean activateCycleBreaking) {
        ComputeImmutable ci = new ComputeImmutable();
        primaryType.recursiveSubTypeStream().forEach(ti -> go(ti, activateCycleBreaking));
        return new OutputImpl(ci.internalWaitFor, ci.externalWaitFor);
    }


    private class ComputeImmutable {
        Set<FieldInfo> internalWaitFor = new HashSet<>();
        Set<TypeInfo> externalWaitFor = new HashSet<>();

        void go(TypeInfo typeInfo, boolean activateCycleBreaking) {
            if (typeInfo.analysis().haveAnalyzedValueFor(IMMUTABLE_TYPE)) return;
            Value.Immutable immutable = computeImmutableType(typeInfo, activateCycleBreaking);
            if (immutable != null) {
                DECIDE.debug("Decide immutable = {} for type {}", immutable, typeInfo);
                typeInfo.analysis().set(IMMUTABLE_TYPE, immutable);
            } else {
                UNDECIDED.debug("Immutable of type {} undecided, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }


        private Value.Immutable computeImmutableType(TypeInfo typeInfo, boolean activateCycleBreaking) {
            boolean fieldsAssignable = typeInfo.fields().stream().anyMatch(fi -> !fi.isPropertyFinal());
            if (fieldsAssignable) {
                return MUTABLE;
            }

            // hierarchy

            TypeInfo parent = typeInfo.parentClass().typeInfo();
            Value.Immutable immutableParent = parent.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
            Value.Immutable immutableParentBroken;
            boolean stopExternal = false;
            if (immutableParent == null) {
                if (activateCycleBreaking) {
                    if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                        immutableParentBroken = HiddenContentTypes.hasHc(parent) ? IMMUTABLE_HC : IMMUTABLE;
                    } else {
                        immutableParentBroken = MUTABLE;
                    }
                } else {
                    externalWaitFor.add(parent);
                    stopExternal = true;
                    immutableParentBroken = MUTABLE;
                }
            } else if (MUTABLE.equals(immutableParent)) {
                return MUTABLE;
            } else {
                immutableParentBroken = immutableParent;
            }

            Value.Immutable worst = immutableParentBroken;
            for (ParameterizedType superType : typeInfo.interfacesImplemented()) {
                Value.Immutable immutableSuper = superType.typeInfo().analysis().getOrNull(IMMUTABLE_TYPE,
                        ValueImpl.ImmutableImpl.class);
                Value.Immutable immutableSuperBroken;
                if (immutableSuper == null) {
                    if (activateCycleBreaking) {
                        if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                            immutableSuperBroken = HiddenContentTypes.hasHc(superType.typeInfo()) ? IMMUTABLE_HC : IMMUTABLE;
                        } else {
                            immutableSuperBroken = MUTABLE;
                        }
                    } else {
                        externalWaitFor.add(superType.typeInfo());
                        immutableSuperBroken = MUTABLE;
                    }
                    stopExternal = true;
                } else if (MUTABLE.equals(immutableSuper)) {
                    return MUTABLE;
                } else {
                    immutableSuperBroken = immutableSuper;
                }
                worst = worst.min(immutableSuperBroken);
            }
            if (stopExternal) {
                return null;
            }

            // fields

            Boolean isImmutable = loopOverFields(typeInfo);
            if (isImmutable == null) return null;

            assert worst != null;
            if (isImmutable && worst.isAtLeastImmutableHC()) {
                return HiddenContentTypes.hasHc(typeInfo) ? IMMUTABLE_HC : IMMUTABLE;
            }
            return FINAL_FIELDS.min(worst);
        }

        private Boolean loopOverFields(TypeInfo typeInfo) {
            // fields should be private, or immutable for the type to be immutable
            // fields should not be @Modified nor assigned to
            // fields should not be @Dependent
            boolean undecided = false;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                if (!fieldInfo.isPropertyFinal()) return false;
                if (!fieldInfo.access().isPrivate()) {
                    Immutable immutable = analysisHelper.typeImmutableNullIfUndecided(fieldInfo.type());
                    if (immutable == null) {
                        externalWaitFor.add(fieldInfo.type().bestTypeInfo());
                        undecided = true;
                    } else if (!immutable.isAtLeastImmutableHC()) {
                        return false;
                    }
                }
                Value.Bool fieldUnmodified = fieldInfo.analysis().getOrNull(UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
                if (fieldUnmodified == null) {
                    internalWaitFor.add(fieldInfo);
                    undecided = true;
                } else if (fieldUnmodified.isFalse()) {
                    return false;
                }
                Value.Independent fieldIndependent = fieldInfo.analysis().getOrNull(INDEPENDENT_FIELD,
                        ValueImpl.IndependentImpl.class);
                if (fieldIndependent == null) {
                    internalWaitFor.add(fieldInfo);
                    undecided = true;
                } else if (fieldIndependent.isDependent()) {
                    return false;
                }
            }
            return undecided ? null : true;
        }

    }
}
