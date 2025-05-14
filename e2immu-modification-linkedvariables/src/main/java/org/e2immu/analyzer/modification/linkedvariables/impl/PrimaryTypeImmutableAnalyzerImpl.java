package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.PrimaryTypeImmutableAnalyzer;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.*;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.UNMODIFIED_FIELD;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.*;

public class PrimaryTypeImmutableAnalyzerImpl extends CommonAnalyzerImpl implements PrimaryTypeImmutableAnalyzer {
    private final AnalysisHelper analysisHelper = new AnalysisHelper();

    private record OutputImpl(List<Throwable> problemsRaised,
                              Set<FieldInfo> internalWaitFor,
                              Set<TypeInfo> externalWaitFor) implements Output {
    }

    @Override
    public Output go(TypeInfo primaryType) {
        ComputeImmutable ci = new ComputeImmutable();
        primaryType.recursiveSubTypeStream().forEach(this::go);
        return new OutputImpl(ci.problemsRaised, ci.internalWaitFor, ci.externalWaitFor);
    }

    private class ComputeImmutable {
        List<Throwable> problemsRaised = new LinkedList<>();
        Set<FieldInfo> internalWaitFor = new HashSet<>();
        Set<TypeInfo> externalWaitFor = new HashSet<>();

        void go(TypeInfo typeInfo) {
            if (typeInfo.analysis().haveAnalyzedValueFor(IMMUTABLE_TYPE)) return;
            Value.Immutable immutable = computeImmutableType(typeInfo);
            if (immutable != null) {
                DECIDE.debug("Decide immutable = {} for type {}", immutable, typeInfo);
                typeInfo.analysis().set(IMMUTABLE_TYPE, immutable);
            } else {
                UNDECIDED.debug("Immutable of type {} undecided, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }


        private Value.Immutable computeImmutableType(TypeInfo typeInfo) {
            boolean fieldsAssignableFromOutside = typeInfo.fields().stream()
                    .anyMatch(fi -> !fi.isPropertyFinal() && !fi.access().isPrivate());
            if (fieldsAssignableFromOutside) {
                return MUTABLE;
            }

            TypeInfo parent = typeInfo.parentClass().typeInfo();
            Value.Immutable immutableParent = parent.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
            boolean stopExternal = false;
            if (immutableParent == null) {
                externalWaitFor.add(parent);
                stopExternal = true;
            } else if (MUTABLE.equals(immutableParent)) {
                return MUTABLE;
            }

            Value.Immutable worst = Objects.requireNonNullElse(immutableParent, MUTABLE);
            for (ParameterizedType superType : typeInfo.interfacesImplemented()) {
                Value.Immutable immutableSuper = superType.typeInfo().analysis().getOrNull(IMMUTABLE_TYPE,
                        ValueImpl.ImmutableImpl.class);
                if (immutableSuper == null) {
                    externalWaitFor.add(superType.typeInfo());
                    stopExternal = true;
                } else if (MUTABLE.equals(immutableSuper)) {
                    return MUTABLE;
                }
                worst = worst.min(immutableSuper);
            }
            if (stopExternal) {
                return null;
            }

            // are any of the fields modified outside of construction?
            // are any of the fields exposed?
            Boolean isImmutable = isImmutable(typeInfo);
            if (isImmutable == null) return null;

            assert worst != null;
            if (isImmutable && worst.isAtLeastImmutableHC()) {
                HiddenContentTypes hct = typeInfo.analysis().getOrNull(HIDDEN_CONTENT_TYPES, HiddenContentTypes.class);
                assert hct != null;
                return hct.hasHiddenContent() ? IMMUTABLE_HC : IMMUTABLE;
            }
            boolean allFieldsFinal = typeInfo.fields().stream().allMatch(FieldInfo::isPropertyFinal);
            Value.Immutable mine = allFieldsFinal ? FINAL_FIELDS : MUTABLE;
            return mine.min(worst);
        }


        private Boolean isImmutable(TypeInfo typeInfo) {
            // fields should be private, or immutable for the type to be immutable
            // fields should not be @Modified
            boolean undecided = false;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
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
            }
            return undecided ? null : true;
        }

    }
}
