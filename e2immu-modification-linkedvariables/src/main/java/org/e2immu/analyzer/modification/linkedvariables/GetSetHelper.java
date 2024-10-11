package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.FieldReference;
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
        Value.FieldValue getSet = methodInfo.analysis().getOrDefault(GET_SET_FIELD, ValueImpl.FieldValueImpl.EMPTY);
        if (getSet.field() != null && !methodInfo.analysis().haveAnalyzedValueFor(STATIC_VALUES_METHOD)) {
            assert getSet.field().isSynthetic();
            StaticValues sv;
            if (!methodInfo.hasReturnValue() || methodInfo.isFluent()) {
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
        Variable target;
        if (methodInfo.parameters().isEmpty()) {
            // straight
            target = runtime.newFieldReference(getSet.field());
        } else {
            // indexing
            ParameterInfo indexParameter = methodInfo.parameters().get(0);
            FieldReference getSetFieldRef = runtime.newFieldReference(getSet.field());
            target = runtime.newDependentVariable(runtime.newVariableExpression(getSetFieldRef),
                    runtime.newVariableExpression(indexParameter));
        }
        return new StaticValuesImpl(null, runtime.newVariableExpression(target), Map.of());
    }

    private StaticValues setter(MethodInfo methodInfo, Value.FieldValue getSet) {
        Variable target;
        Variable valueParameter;
        if (methodInfo.parameters().size() == 2) {
            // indexing
            // DECISION: if the object type is 'int' as well, we follow the convention of List.set(index. element)
            Variable indexParameter = methodInfo.parameters().get(0);
            valueParameter = methodInfo.parameters().get(1);
            if (valueParameter.parameterizedType().isInt() && !indexParameter.parameterizedType().isInt()) {
                Variable tmp = valueParameter;
                valueParameter = indexParameter;
                indexParameter = tmp;
            }
            // see org.e2immu.language.inspection.api.util.GetSetUtil.extractFieldType
            if (getSet.field().type().arrays() > 0) {
                // indexing in array: this.objects[i]=o

                // objects == the GetSet field
                FieldReference getSetFieldRef = runtime.newFieldReference(getSet.field());
                target = runtime.newDependentVariable(runtime.newVariableExpression(getSetFieldRef),
                        runtime.newVariableExpression(indexParameter));
            } else {
                throw new UnsupportedOperationException("indexing is in a virtual array");
            }
        } else if (methodInfo.parameters().size() == 1) {
            // normal setter
            target = runtime.newFieldReference(getSet.field());
            valueParameter = methodInfo.parameters().get(0);
        } else throw new UnsupportedOperationException();
        Expression expression;
        if (methodInfo.isFluent()) {
            expression = runtime.newVariableExpression(runtime.newThis(methodInfo.typeInfo()));
        } else {
            expression = null;
        }
        return new StaticValuesImpl(null, expression, Map.of(target, runtime.newVariableExpression(valueParameter)));
    }

}
