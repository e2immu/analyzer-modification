package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.PrimaryTypeImmutableAnalyzer;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.*;

/*
Phase 4.2 Primary type immutable

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

    private class ComputeImmutable {
        Set<Info> internalWaitFor = new HashSet<>();
        Set<TypeInfo> externalWaitFor = new HashSet<>();

        void go(TypeInfo typeInfo, boolean activateCycleBreaking) {
            if (typeInfo.analysis().haveAnalyzedValueFor(IMMUTABLE_TYPE)
                || !typeInfo.analysis().haveAnalyzedValueFor(INDEPENDENT_TYPE)) {
                return;
            }
            Immutable immutable = computeImmutableType(typeInfo, activateCycleBreaking);
            if (immutable != null) {
                DECIDE.debug("Decide immutable = {} for type {}", immutable, typeInfo);
                typeInfo.analysis().setAllowControlledOverwrite(IMMUTABLE_TYPE, immutable);
            } else {
                UNDECIDED.debug("Immutable of type {} undecided, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }

        private Immutable computeImmutableType(TypeInfo typeInfo, boolean activateCycleBreaking) {
            boolean fieldsAssignable = typeInfo.fields().stream().anyMatch(fi -> !fi.isPropertyFinal());
            if (fieldsAssignable) return MUTABLE;

            // hierarchy

            Value.Immutable immFromHierarchy = IMMUTABLE;
            boolean stopExternal = false;

            for (ParameterizedType superType : typeInfo.parentAndInterfacesImplemented()) {
                Value.Immutable immutableSuper = superType.typeInfo().analysis().getOrNull(IMMUTABLE_TYPE,
                        ValueImpl.ImmutableImpl.class);
                Value.Immutable immutableSuperBroken;
                if (immutableSuper == null) {
                    if (activateCycleBreaking) {
                        if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                            immutableSuperBroken = HiddenContentTypes.hasHc(superType.typeInfo()) ? IMMUTABLE_HC : IMMUTABLE;
                        } else {
                            return FINAL_FIELDS;
                        }
                    } else {
                        externalWaitFor.add(superType.typeInfo());
                        immutableSuperBroken = MUTABLE; // not relevant
                        stopExternal = true;
                    }
                } else {
                    if (immutableSuper.isMutable()) return MUTABLE;
                    immutableSuperBroken = immutableSuper;
                }
                immFromHierarchy = immutableSuperBroken.min(immFromHierarchy);
            }
            if (immFromHierarchy.isFinalFields()) return FINAL_FIELDS;

            if (stopExternal) {
                return null;
            }

            // fields and abstract methods

            Boolean immFromField = loopOverFieldsAndAbstractMethods(typeInfo);
            if (immFromField == null) return null;
            if (!immFromField) return FINAL_FIELDS;
            return HiddenContentTypes.hasHc(typeInfo) ? IMMUTABLE_HC : IMMUTABLE;
        }


        private Boolean loopOverFieldsAndAbstractMethods(TypeInfo typeInfo) {
            // fields should be private, or immutable for the type to be immutable
            // fields should not be @Modified nor assigned to
            // fields should not be @Dependent
            Set<TypeInfo> externalWaitFor = new HashSet<>();
            Set<Info> internalWaitFor = new HashSet<>();

            Boolean isImmutable = true;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                if (!fieldInfo.access().isPrivate()) {
                    Immutable immutable = analysisHelper.typeImmutableNullIfUndecided(fieldInfo.type());
                    if (immutable == null) {
                        externalWaitFor.add(fieldInfo.type().bestTypeInfo());
                        isImmutable = null;
                    } else if (!immutable.isAtLeastImmutableHC()) {
                        return false;
                    }
                }
                Value.Bool fieldUnmodified = fieldInfo.analysis().getOrNull(UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
                if (fieldUnmodified == null) {
                    internalWaitFor.add(fieldInfo);
                    isImmutable = null;
                } else if (fieldUnmodified.isFalse()) {
                    return false;
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.isAbstract()) {
                    Value.Bool nonModifying = methodInfo.analysis().getOrNull(NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
                    if (nonModifying == null) {
                        internalWaitFor.add(methodInfo);
                        isImmutable = null;
                    } else if (nonModifying.isFalse()) {
                        return false;
                    }
                }
            }
            if (isImmutable == null) {
                this.externalWaitFor.addAll(externalWaitFor);
                this.internalWaitFor.addAll(internalWaitFor);
                return null;
            }
            return true;
        }
    }
}
