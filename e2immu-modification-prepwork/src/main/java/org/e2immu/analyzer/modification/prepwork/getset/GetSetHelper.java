package org.e2immu.analyzer.modification.prepwork.getset;

import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.statement.Block;
import org.e2immu.language.cst.api.statement.ExpressionAsStatement;
import org.e2immu.language.cst.api.statement.ReturnStatement;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.DependentVariable;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;

public class GetSetHelper {
    private static final String LIST_GET = "java.util.List.get(int)";
    private static final String LIST_SET = "java.util.List.set(int,E)";

    /*
    a getter or accessor is a method that does nothing but return a field.
    a setter is a method that does nothing but set the value of a field. It may return "this".

    there are some complications:
    - the field only has to have a scope which is recursively 'this', it does not have to be 'this' directly.
    - overriding a method which has a @GetSet marker (e.g. interface method)
    - array access or direct list indexing. we will always determine from the context whether we're dealing with
      indexing or not, and store the whole field in one go

    TODO overrides! compatibility, direct override, etc.

    Obvious limitations
    - we're using a field to store the information, we should use field reference (see TestGetSet)
    - alternative packing systems: a map with string constants, getting values at fixed positions (see TestGetSet)
     */
    public static void doGetSetAnalysis(MethodInfo methodInfo, Block methodBody) {
        if (!methodInfo.analysis().haveAnalyzedValueFor(PropertyImpl.GET_SET_FIELD)) {
            Value.FieldValue getSet;
            if (!methodBody.isEmpty()) {
                Statement s0 = methodBody.statements().get(0);
                if (s0 instanceof ReturnStatement rs) {
                    if (rs.expression() instanceof VariableExpression ve
                        && ve.variable() instanceof FieldReference fr
                        && fr.scopeIsRecursivelyThis()) {
                        // return this.field;
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), false, -1);
                    } else if (rs.expression() instanceof VariableExpression ve
                               && ve.variable() instanceof DependentVariable dv
                               && dv.arrayVariable() instanceof FieldReference fr
                               && dv.indexVariable() instanceof ParameterInfo
                               && fr.scopeIsRecursivelyThis()) {
                        // return this.objects[param]
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), false, 0);
                    } else if (rs.expression() instanceof MethodCall mc
                               && overrideOf(mc.methodInfo(), LIST_GET)
                               && mc.parameterExpressions().get(0) instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo
                               && mc.object() instanceof VariableExpression ve2
                               && ve2.variable() instanceof FieldReference fr
                               && fr.scopeIsRecursivelyThis()) {
                        // return this.list.get(param);
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), false, 0);
                    } else {
                        getSet = null;
                    }
                } else if (checkSetMethod(methodBody) && s0 instanceof ExpressionAsStatement eas) {
                    if (eas.expression() instanceof Assignment a
                        && a.variableTarget() instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                        && a.value() instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo) {
                        // this.field = param
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), true, -1);
                    } else if (eas.expression() instanceof Assignment a
                               && a.variableTarget() instanceof DependentVariable dv
                               && dv.arrayVariable() instanceof FieldReference fr && fr.scopeIsRecursivelyThis()
                               && dv.indexVariable() instanceof ParameterInfo
                               && a.value() instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo) {
                        // this.objects[i] = param
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), true, 0);
                    } else if (eas.expression() instanceof MethodCall mc
                               && overrideOf(mc.methodInfo(), LIST_SET)
                               && mc.parameterExpressions().get(0) instanceof VariableExpression ve && ve.variable() instanceof ParameterInfo
                               && mc.parameterExpressions().get(1) instanceof VariableExpression ve2 && ve2.variable() instanceof ParameterInfo
                               && mc.object() instanceof VariableExpression ve3 && ve3.variable() instanceof FieldReference fr
                               && fr.scopeIsRecursivelyThis()) {
                        // this.list.set(i, object)
                        getSet = new ValueImpl.GetSetValueImpl(fr.fieldInfo(), true, 0);
                    } else {
                        getSet = null;
                    }
                } else {
                    getSet = null;
                }
                if (getSet != null) {
                    methodInfo.analysis().set(PropertyImpl.GET_SET_FIELD, getSet);
                }
            }
        }
    }


    private static boolean overrideOf(MethodInfo methodInfo, String fqn) {
        if (fqn.equals(methodInfo.fullyQualifiedName())) return true;
        return methodInfo.overrides().stream().anyMatch(mi -> fqn.equals(mi.fullyQualifiedName()));
    }

    private static boolean checkSetMethod(Block methodBody) {
        return methodBody.size() == 1 ||
               methodBody.size() == 2
               && methodBody.statements().get(1) instanceof ReturnStatement rs
               && rs.expression() instanceof VariableExpression veThis
               && veThis.variable() instanceof This;
    }

}
