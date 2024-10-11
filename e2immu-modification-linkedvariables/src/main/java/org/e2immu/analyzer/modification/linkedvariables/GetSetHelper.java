package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

import java.util.Map;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_METHOD;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.GET_SET_FIELD;

public class GetSetHelper {
    private final Runtime runtime;

    public GetSetHelper(Runtime runtime) {
        this.runtime = runtime;
    }

    public void copyStaticValuesForGetSet(MethodInfo methodInfo) {
        Value.FieldValue getSet = methodInfo.analysis().getOrDefault(GET_SET_FIELD, ValueImpl.GetSetValueImpl.EMPTY);
        if (getSet.field() != null && !methodInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_METHOD)) {
            assert getSet.field().isSynthetic();
            StaticValues sv;
            if (getSet.setter()) {
                // setter
                sv = setter(methodInfo, getSet);
            } else {
                // getter
                sv = getter(methodInfo, getSet);
            }
            methodInfo.analysis().set(STATIC_VALUES_METHOD, sv);
        }
    }

    private StaticValues getter(MethodInfo methodInfo, Value.FieldValue getSet) {
        Expression indexOrNull;
        if (methodInfo.parameters().isEmpty()) {
            // straight
            indexOrNull = null;
        } else {
            // indexing
            ParameterInfo indexParameter = methodInfo.parameters().get(getSet.parameterIndexOfIndex());
            indexOrNull = runtime.newVariableExpression(indexParameter);
        }
        VariableExpression thisVe = runtime.newVariableExpression(runtime.newThis(methodInfo.typeInfo()));
        Variable variable = getSet.createVariable(runtime, thisVe, indexOrNull);
        return new StaticValuesImpl(null, runtime.newVariableExpression(variable), Map.of());
    }

    private StaticValues setter(MethodInfo methodInfo, Value.FieldValue getSet) {
        Variable valueParameter;
        Expression indexOrNull;
        if (getSet.hasIndex()) {
            // indexing
            // DECISION: if the object type is 'int' as well, we follow the convention of List.set(index. element)
            Variable indexParameter = methodInfo.parameters().get(getSet.parameterIndexOfIndex());
            valueParameter = methodInfo.parameters().get(1 - getSet.parameterIndexOfIndex());

            // see org.e2immu.language.inspection.api.util.GetSetUtil.extractFieldType
            if (getSet.field().type().arrays() > 0) {
                // indexing in array: this.objects[i]=o
                indexOrNull = runtime.newVariableExpression(indexParameter);
            } else {
                throw new UnsupportedOperationException("indexing is in a virtual array");
            }
        } else {
            assert !methodInfo.parameters().isEmpty();
            // normal setter
            valueParameter = methodInfo.parameters().get(0);
            indexOrNull = null;
        }
        VariableExpression thisVe = runtime.newVariableExpression(runtime.newThis(methodInfo.typeInfo()));
        Variable target = getSet.createVariable(runtime, thisVe, indexOrNull);
        Expression expression;
        if (methodInfo.isFluent()) {
            expression = runtime.newVariableExpression(runtime.newThis(methodInfo.typeInfo()));
        } else {
            expression = null;
        }
        return new StaticValuesImpl(null, expression, Map.of(target, runtime.newVariableExpression(valueParameter)));
    }

}
