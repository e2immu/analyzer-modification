package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.PropertyValueMapImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.support.EventuallyFinal;

public class VariableInfoImpl implements VariableInfo {
    public static final Property MODIFIED_VARIABLE = new PropertyImpl("modifiedVariable");

    private final EventuallyFinal<LinkedVariables> linkedVariables = new EventuallyFinal<>();
    private final PropertyValueMap analysis = new PropertyValueMapImpl();

    private final Variable variable;
    private final Assignments assignments;
    private final String readId;

    public VariableInfoImpl(Variable variable, Assignments assignments, String readId) {
        this.variable = variable;
        this.assignments = assignments;
        this.readId = readId;
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

    @Override
    public Variable variable() {
        return variable;
    }

    @Override
    public LinkedVariables linkedVariables() {
        return linkedVariables.get();
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
    public String readId() {
        return readId;
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
}
