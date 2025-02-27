package org.e2immu.analyzer.modification.prepwork.getset;

import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.translate.TranslationMap;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import static org.e2immu.language.cst.impl.analysis.PropertyImpl.GET_SET_FIELD;

public record ApplyGetSetTranslation(Runtime runtime) implements TranslationMap {
    @Override
    public Variable translateVariableRecursively(Variable variable) {
        return runtime.translateVariableRecursively(this, variable);
    }

    @Override
    public boolean isClearAnalysis() {
        return false;
    }

    @Override
    public Expression translateExpression(Expression expression) {
        if (expression instanceof Lambda || expression instanceof ConstructorCall cc && cc.anonymousClass() != null) {
            return null;
        }
        if (expression instanceof MethodCall mc) {
            ensureGetSet(mc.methodInfo());

            Value.FieldValue getSet = mc.methodInfo().analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
            if (getSet.field() != null) {
                Expression replacedObject = unwrap(mc.object().translate(this));
                ParameterizedType type = mc.concreteReturnType();
                if (getSet.setter()) {
                    VariableExpression target;
                    if (getSet.hasIndex()) {
                        Expression replacedIndex = mc.parameterExpressions().get(getSet.parameterIndexOfIndex())
                                .translate(this);
                        ParameterizedType arrayType = type.copyWithArrays(type.arrays() + 1);
                        FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, arrayType);
                        target = runtime.newVariableExpression(runtime.newDependentVariable(fr, type, replacedIndex));
                    } else {
                        target = runtime.newVariableExpression(runtime.newFieldReference(getSet.field(), replacedObject,
                                mc.object().parameterizedType()));
                    }
                    Expression replacedValue = mc.parameterExpressions().get(getSet.parameterIndexOfValue());
                    //return runtime.newAssignmentBuilder().setSource(mc.source()).setTarget(target).setValue(replacedValue).build();
                } else {
                    if (mc.parameterExpressions().size() == 1) {
                        ParameterizedType arrayType = type.copyWithArrays(type.arrays() + 1);
                        FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, arrayType);
                        Expression replacedIndex = mc.parameterExpressions().get(0).translate(this);
                        DependentVariable dv = runtime.newDependentVariable(fr, type, replacedIndex);
                        return runtime.newVariableExpression(dv);
                    }
                    FieldReference fr = runtime.newFieldReference(getSet.field(), replacedObject, type);
                    return runtime.newVariableExpression(fr);
                }
            }
        }
        return expression;
    }

    private static Expression unwrap(Expression object) {
        if (object instanceof EnclosedExpression ee) return unwrap(ee.inner());
        if (object instanceof Cast cast && cast.expression() instanceof VariableExpression ve) {
            return unwrap(ve);
        }
        return object;
    }

    /*
    Depending on the order of the primary types in "PrepAnalyzer.doPrimaryTypes", the getSet analysis may have
    happened already, or not. TestCallGraph2 contains an example where this can go wrong, but note that the execution
    order for the modification analyzer does not catch this problem.

     */
    private static void ensureGetSet(MethodInfo methodInfo) {
        if (!methodInfo.isAbstract()) {
            GetSetHelper.doGetSetAnalysis(methodInfo, methodInfo.methodBody());
        }
    }
}
