package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.PropertyValueMapImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.support.EventuallyFinal;
import org.e2immu.support.SetOnce;

public class VariableInfoImpl implements VariableInfo {
    public static final Property MODIFIED_VARIABLE = new PropertyImpl("modifiedVariable");
    public static final Property MODIFIED_FI_COMPONENTS_VARIABLE = new PropertyImpl("modifiedFunctionalInterfaceComponentsVariable",
            ValueImpl.VariableBooleanMapImpl.EMPTY);

    private final EventuallyFinal<LinkedVariables> linkedVariables = new EventuallyFinal<>();
    private final SetOnce<StaticValues> staticValues = new SetOnce<>();

    private final PropertyValueMap analysis = new PropertyValueMapImpl();

    private final Variable variable;
    private final Assignments assignments;
    private final Reads reads;
    private final VariableInfo variableInClosure;

    public VariableInfoImpl(Variable variable, Assignments assignments, Reads reads, VariableInfo variableInClosure) {
        this.variable = variable;
        this.assignments = assignments;
        this.reads = reads;
        this.variableInClosure = variableInClosure;
    }

    public void initializeLinkedVariables(LinkedVariables initialValue) {
        this.linkedVariables.setVariable(initialValue);
    }

    public boolean setLinkedVariables(LinkedVariables linkedVariables) {
        assert linkedVariables != null;
        assert this.linkedVariables.get() != null : "Please initialize LVs";
        assert !linkedVariables.contains(variable) : "Self references are not allowed";
        if (this.linkedVariables.isFinal()) {
            if (!this.linkedVariables.get().equals(linkedVariables)) {
                throw new IllegalStateException("Variable " + variable.fullyQualifiedName()
                                                + ": not allowed to change LVs anymore: old: " + this.linkedVariables.get()
                                                + ", new " + linkedVariables);
            }
            return false;
        }
        if (!this.linkedVariables.get().isNotYetSet()) {
            // the first time, there are no restrictions on statically assigned values
            // as soon as we have a real value, we cannot change SA anymore

            if (!this.linkedVariables.get().identicalStaticallyAssigned(linkedVariables)) {
                throw new IllegalStateException("Cannot change statically assigned for variable "
                                                + variable.fullyQualifiedName() + "\nold: " + this.linkedVariables.get()
                                                + "\nnew: " + linkedVariables + "\n");
            }
        }
        if (linkedVariables.isDelayed()) {
            this.linkedVariables.setVariable(linkedVariables);
            return false;
        }
        this.linkedVariables.setFinal(linkedVariables);
        return true;
    }

    public boolean staticValuesIsSet() {
        return staticValues.isSet();
    }

    public void staticValuesSet(StaticValues staticValues) {
        this.staticValues.set(staticValues);
    }

    @Override
    public Variable variable() {
        return variable;
    }

    @Override
    public LinkedVariables linkedVariables() {
        return linkedVariables.get();
    }

    @Override
    public StaticValues staticValues() {
        return staticValues.getOrDefaultNull();
    }

    @Override
    public PropertyValueMap analysis() {
        return analysis;
    }

    @Override
    public Assignments assignments() {
        return assignments;
    }

    @Override
    public Reads reads() {
        return reads;
    }

    @Override
    public boolean hasBeenDefined(String index) {
        if (variable instanceof LocalVariable || variable instanceof ReturnVariable) {
            return assignments.hasAValueAt(index);
        }
        return true;
    }

    @Override
    public boolean isModified() {
        return analysis.getOrDefault(MODIFIED_VARIABLE, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    @Override
    public VariableInfo variableInfoInClosure() {
        return variableInClosure;
    }
}
