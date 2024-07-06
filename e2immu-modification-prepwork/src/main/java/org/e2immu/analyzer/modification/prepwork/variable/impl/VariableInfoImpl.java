package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyValueMapImpl;
import org.e2immu.support.EventuallyFinal;
import org.e2immu.support.SetOnce;

import java.util.Set;

public class VariableInfoImpl implements VariableInfo {

    private final EventuallyFinal<LinkedVariables> linkedVariables = new EventuallyFinal<>();
    private final SetOnce<Integer> modificationTime = new SetOnce<>();
    private final PropertyValueMap analysis = new PropertyValueMapImpl();

    private final Variable variable;
    private final AssignmentIds assignmentIds;
    private final String readId;
    private final Set<Integer> readAtStatementTimes;

    public VariableInfoImpl(Variable variable, AssignmentIds assignmentIds, String readId, Set<Integer> readAtStatementTimes) {
        this.variable = variable;
        this.assignmentIds = assignmentIds;
        this.readId = readId;
        this.readAtStatementTimes = readAtStatementTimes;
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
        if (!this.linkedVariables.get() .isNotYetSet()) {
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
    public Set<Integer> readAtStatementTimes() {
        return readAtStatementTimes;
    }

    @Override
    public AssignmentIds assignmentIds() {
        return assignmentIds;
    }

    @Override
    public String readId() {
        return readId;
    }
}
