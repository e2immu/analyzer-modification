package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.annotation.Fluent;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.HashMap;
import java.util.Map;

public class LinkEvaluation {

    private final LinkedVariables linkedVariables;
    private final Map<Variable, LinkedVariables> links;


    private LinkEvaluation(LinkedVariables linkedVariables, Map<Variable, LinkedVariables> links) {
        this.linkedVariables = linkedVariables;
        this.links = links;
    }

    public static class Builder {
        private LinkedVariables linkedVariables = LinkedVariablesImpl.EMPTY;
        private final Map<Variable, LinkedVariables> links = new HashMap<>();

        public void mergeLinkedVariablesOfExpression(LinkedVariables lv) {
            linkedVariables = linkedVariables.merge(lv);
        }

        @Fluent
        Builder setLinkedVariables(LinkedVariables linkedVariables) {
            assert linkedVariables != null;
            this.linkedVariables = linkedVariables;
            return this;
        }

        @Fluent
        Builder merge(Variable variable, LinkedVariables lv) {
            assert variable != null;
            assert lv != null;
            this.links.merge(variable, lv, LinkedVariables::merge);
            return this;
        }

        LinkEvaluation build() {
            return new LinkEvaluation(linkedVariables, Map.copyOf(links));
        }
    }

    public LinkedVariables linkedVariables() {
        return linkedVariables;
    }

    public Map<Variable, LinkedVariables> links() {
        return links;
    }
}
