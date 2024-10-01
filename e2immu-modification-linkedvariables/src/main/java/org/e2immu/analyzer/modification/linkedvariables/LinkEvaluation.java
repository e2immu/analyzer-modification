package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.annotation.Fluent;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.*;

public class LinkEvaluation {

    public static final LinkEvaluation EMPTY = new Builder().build();

    private final LinkedVariables linkedVariables;
    // values accumulated as we evaluate the expression
    private final StaticValues staticValues;

    private final Map<Variable, LinkedVariables> links;
    // other assignments accumulated along the way, as "side effects"
    private final Map<Variable, StaticValues> assignments;

    private final Set<Variable> modified;

    private LinkEvaluation(LinkedVariables linkedVariables, Map<Variable, LinkedVariables> links,
                           Set<Variable> modified,
                           StaticValues staticValues, Map<Variable, StaticValues> assignments) {
        this.linkedVariables = linkedVariables;
        this.links = links;
        this.modified = modified;
        this.staticValues = staticValues;
        this.assignments = assignments;
    }

    public static class Builder {
        private LinkedVariables linkedVariables = LinkedVariablesImpl.EMPTY;
        private StaticValues staticValues = StaticValuesImpl.NONE;
        private final Map<Variable, LinkedVariables> links = new HashMap<>();
        private final Map<Variable, StaticValues> assignments = new HashMap<>();
        private final Set<Variable> modified = new HashSet<>();

        public LinkedVariables getLinkedVariablesOf(Variable variable) {
            return links.get(variable);
        }

        public Builder merge(LinkEvaluation linkEvaluation) {
            linkedVariables = linkEvaluation.linkedVariables;
            staticValues = linkEvaluation.staticValues;

            linkEvaluation.links.forEach((v, lv) -> links.merge(v, lv, LinkedVariables::merge));
            linkEvaluation.assignments.forEach((v, sv) -> assignments.merge(v, sv, StaticValues::merge));
            modified.addAll(linkEvaluation.modified);
            return this;
        }

        public Builder merge(Builder builder) {
            linkedVariables = builder.linkedVariables;
            staticValues = builder.staticValues;
            builder.links.forEach((v, lv) -> links.merge(v, lv, LinkedVariables::merge));
            builder.assignments.forEach((v, sv) -> assignments.merge(v, sv, StaticValues::merge));
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
        public Builder setStaticValues(StaticValues staticValues) {
            assert staticValues != null;
            this.staticValues = staticValues;
            return this;
        }

        @Fluent
        Builder merge(Variable variable, LinkedVariables lv) {
            assert variable != null;
            assert lv != null;
            this.links.merge(variable, lv, LinkedVariables::merge);
            return this;
        }

        Builder merge(Variable variable, StaticValues sv) {
            assert variable != null;
            assert sv != null;
            if(!sv.isEmpty()) {
                this.assignments.merge(variable, sv, StaticValues::merge);
            }
            return this;
        }

        @Fluent
        Builder addModified(Variable variable) {
            modified.add(variable);
            return this;
        }

        public LinkedVariables linkedVariablesOfExpression() {
            return linkedVariables;
        }

        LinkEvaluation build() {
            return new LinkEvaluation(linkedVariables, Map.copyOf(links), Set.copyOf(modified),
                    staticValues, Map.copyOf(assignments));
        }
    }

    public LinkedVariables linkedVariables() {
        return linkedVariables;
    }

    public Map<Variable, LinkedVariables> links() {
        return links;
    }

    public Map<Variable, StaticValues> assignments() {
        return assignments;
    }

    public StaticValues staticValues() {
        return staticValues;
    }

    public Set<Variable> modified() {
        return modified;
    }
}
