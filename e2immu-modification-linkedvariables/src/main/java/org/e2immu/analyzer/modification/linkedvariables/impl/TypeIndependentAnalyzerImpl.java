package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalyzerException;
import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.TypeIndependentAnalyzer;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.e2immu.language.cst.api.analysis.Value.Independent;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.*;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;

/*
Phase 4.1 Primary type independent

 */
public class TypeIndependentAnalyzerImpl extends CommonAnalyzerImpl implements TypeIndependentAnalyzer {

    public TypeIndependentAnalyzerImpl(IteratingAnalyzer.Configuration configuration) {
        super(configuration);
    }

    private record OutputImpl(List<AnalyzerException> analyzerExceptions,
                              Set<Info> internalWaitFor,
                              Set<TypeInfo> externalWaitFor) implements Output {
    }

    @Override
    public Output go(TypeInfo typeInfo, boolean activateCycleBreaking) {
        ComputeIndependent ci = new ComputeIndependent();
        List<AnalyzerException> analyzerExceptions = new LinkedList<>();
        try {
            ci.go(typeInfo, activateCycleBreaking);
        } catch (RuntimeException re) {
            if (configuration.storeErrors()) {
                if (!(re instanceof AnalyzerException)) {
                    analyzerExceptions.add(new AnalyzerException(typeInfo, re));
                }
            } else throw re;
        }
        return new OutputImpl(analyzerExceptions, ci.internalWaitFor, ci.externalWaitFor);
    }

    private class ComputeIndependent {
        Set<Info> internalWaitFor = new HashSet<>();
        Set<TypeInfo> externalWaitFor = new HashSet<>();

        void go(TypeInfo typeInfo, boolean activateCycleBreaking) {
            if (typeInfo.analysis().haveAnalyzedValueFor(INDEPENDENT_TYPE)) {
                return;
            }
            Independent independent = computeIndependentType(typeInfo, activateCycleBreaking);
            if (independent != null) {
                DECIDE.debug("Ti: Decide independent of type {} = {}", typeInfo, independent);
                typeInfo.analysis().setAllowControlledOverwrite(INDEPENDENT_TYPE, independent);
            } else {
                UNDECIDED.debug("Ti: Independent of type {} undecided, wait for internal {}, external {}", typeInfo,
                        internalWaitFor, externalWaitFor);
            }
        }

        private Independent computeIndependentType(TypeInfo typeInfo, boolean activateCycleBreaking) {

            Independent indyFromHierarchy = INDEPENDENT;

            // hierarchy

            boolean stopExternal = false;
            for (ParameterizedType superType : typeInfo.parentAndInterfacesImplemented()) {
                TypeInfo superTypeInfo = superType.typeInfo();
                Independent independentSuper = independentSuper(superTypeInfo);
                Independent independentSuperBroken;
                if (independentSuper == null) {
                    if (activateCycleBreaking) {
                        if (configuration.cycleBreakingStrategy() == CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING) {
                            independentSuperBroken = INDEPENDENT;
                        } else {
                            return DEPENDENT;
                        }
                    } else {
                        externalWaitFor.add(superTypeInfo);
                        independentSuperBroken = INDEPENDENT; // not relevant
                    }
                    stopExternal = true;
                } else {
                    independentSuperBroken = independentSuper;
                }
                indyFromHierarchy = independentSuperBroken.min(indyFromHierarchy);
                if (indyFromHierarchy.isDependent()) return DEPENDENT;
            }
            if (stopExternal) {
                return null;
            }
            assert indyFromHierarchy.isAtLeastIndependentHc();

            Independent fromFields = loopOverFieldsAndAbstractMethods(typeInfo);
            if (fromFields == null) return null;
            if (fromFields.isDependent()) return DEPENDENT;
            return fromFields.min(indyFromHierarchy);
        }

        private Independent independentSuper(TypeInfo superTypeInfo) {
            Independent ofType = superTypeInfo.analysis().getOrNull(INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
            if (ofType != null || !superTypeInfo.isAbstract()) return ofType;
            Independent ofMethods = INDEPENDENT;
            for (MethodInfo methodInfo : superTypeInfo.constructorsAndMethods()) {
                if (!methodInfo.isAbstract()) {
                    Independent ofMethod = methodInfo.analysis().getOrNull(INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.class);
                    if (ofMethod == null) return null;
                    if (ofMethod.isDependent()) return DEPENDENT;
                    ofMethods = ofMethods.min(ofMethod);
                }
            }
            return ofMethods;
        }

        private Independent loopOverFieldsAndAbstractMethods(TypeInfo typeInfo) {
            Set<Info> internalWaitFor = new HashSet<>();
            Independent independent = INDEPENDENT;
            for (FieldInfo fieldInfo : typeInfo.fields()) {
                Independent fieldIndependent = fieldInfo.analysis().getOrNull(INDEPENDENT_FIELD,
                        ValueImpl.IndependentImpl.class);
                if (fieldIndependent == null) {
                    internalWaitFor.add(fieldInfo);
                    independent = null;
                } else if (fieldIndependent.isDependent()) {
                    return DEPENDENT;
                } else if (independent != null) {
                    independent = independent.min(fieldIndependent);
                }
            }
            for (MethodInfo methodInfo : typeInfo.methods()) {
                if (methodInfo.isAbstract()) {
                    Independent methodIndependent = methodInfo.analysis().getOrNull(INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.class);
                    if (methodIndependent == null) {
                        internalWaitFor.add(methodInfo);
                        independent = null;
                    } else if (methodIndependent.isDependent()) {
                        return DEPENDENT;
                    } else if (independent != null) {
                        independent = independent.min(methodIndependent);
                    }
                    for (ParameterInfo pi : methodInfo.parameters()) {
                        Independent paramIndependent = pi.analysis().getOrNull(INDEPENDENT_PARAMETER,
                                ValueImpl.IndependentImpl.class);
                        if (paramIndependent == null) {
                            internalWaitFor.add(methodInfo);
                            independent = null;
                        } else if (paramIndependent.isDependent()) {
                            return DEPENDENT;
                        } else if (independent != null) {
                            independent = independent.min(paramIndependent);
                        }
                    }
                }
            }
            if (independent == null) {
                this.internalWaitFor.addAll(internalWaitFor);
            }
            return independent;
        }

    }
}
