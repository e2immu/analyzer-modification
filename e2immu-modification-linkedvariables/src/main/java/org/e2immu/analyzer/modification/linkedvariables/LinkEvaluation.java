package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.annotation.Fluent;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.*;

public class LinkEvaluation {

    public static final LinkEvaluation EMPTY = new Builder().build();

    private final LinkedVariables linkedVariables;
    private final Map<Variable, LinkedVariables> links;
    private final Set<Variable> modified;

    private LinkEvaluation(LinkedVariables linkedVariables, Map<Variable, LinkedVariables> links, Set<Variable> modified) {
        this.linkedVariables = linkedVariables;
        this.links = links;
        this.modified = modified;
    }

    public static class Builder {
        private LinkedVariables linkedVariables = LinkedVariablesImpl.EMPTY;
        private final Map<Variable, LinkedVariables> links = new HashMap<>();
        private final Set<Variable> modified = new HashSet<>();

        public LinkedVariables getLinkedVariablesOf(Variable variable) {
            return links.get(variable);
        }

        public Builder merge(LinkEvaluation linkEvaluation) {
            linkedVariables = linkEvaluation.linkedVariables;
            linkEvaluation.links.forEach((v, lv) -> links.merge(v, lv, LinkedVariables::merge));
            modified.addAll(linkEvaluation.modified);
            return this;
        }

        public Builder merge(Builder builder) {
            linkedVariables = builder.linkedVariables;
            builder.links.forEach((v, lv) -> links.merge(v, lv, LinkedVariables::merge));
            modified.addAll(builder.modified);
            return this;
        }

        public Builder mergeLinkedVariablesOfExpression(LinkedVariables lv) {
            linkedVariables = linkedVariables.merge(lv);
            return this;
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

        @Fluent
        Builder addModified(LinkEvaluation linkEvaluation) {
            modified.addAll(linkEvaluation.modified);
            return this;
        }

        @Fluent
        Builder addModified(Collection<Variable> variables) {
            modified.addAll(variables);
            return this;
        }

        public LinkedVariables linkedVariablesOfExpression() {
            return linkedVariables;
        }

        LinkEvaluation build() {
            return new LinkEvaluation(linkedVariables, Map.copyOf(links), Set.copyOf(modified));
        }
    }

    public LinkedVariables linkedVariables() {
        return linkedVariables;
    }

    public Map<Variable, LinkedVariables> links() {
        return links;
    }

    public Set<Variable> modified() {
        return modified;
    }
}
