package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.variable.impl.Assignments;
import org.e2immu.language.cst.api.analysis.PropertyValueMap;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.Set;

public interface VariableInfo {

    // FIRST PASS DATA, assigned in the prep-work stage during construction

    // for later
    //Set<Integer> readAtStatementTimes();

    /*
    Information about assignments to this variable.
     */
    Assignments assignments();

    /*
    Semantics: index of most recent statement where this variable is read, after a full re-assignment.
    Use Assignments to find out which re-assignment this was, but, because the code compiles, it will have
    an assignmentCount>0.
     */
    String readId();

    Variable variable();

    // SECOND PASS DATA, assigned in the modification stage; eventually immutable

    LinkedVariables linkedVariables();

    PropertyValueMap analysis();

    // for later
    //int modificationTime();
}
