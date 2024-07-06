package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.variable.impl.AssignmentIds;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.Set;

public interface VariableInfo {
    Set<Integer> readAtStatementTimes();

    AssignmentIds assignmentIds();

    String readId();

    Variable variable();

    LinkedVariables linkedVariables();
    
    PropertyValueMap analysis();
}
