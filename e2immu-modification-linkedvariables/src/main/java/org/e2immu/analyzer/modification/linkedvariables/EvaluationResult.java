package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LinkedVariables;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.annotation.Fluent;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.translate.TranslationMap;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.Variable;

import java.util.*;

class EvaluationResult {

    public static final EvaluationResult EMPTY = new Builder().build();

    private final LinkedVariables linkedVariables;
    // values accumulated as we evaluate the expression
    private final StaticValues staticValues;

    private final Map<Variable, LinkedVariables> links;
    // other assignments accumulated along the way, as "side effects"
    private final Map<Variable, StaticValues> assignments;

    private final Set<Variable> modified;
    private final Map<FieldReference, Boolean> modifiedFunctionalComponents;

    private EvaluationResult(LinkedVariables linkedVariables,
                             Map<Variable, LinkedVariables> links,
                             Set<Variable> modified,
                             StaticValues staticValues,
                             Map<Variable, StaticValues> assignments,
                             Map<FieldReference, Boolean> modifiedFunctionalComponents) {
        this.linkedVariables = linkedVariables;
        this.links = links;
        assert ensureNoIdentityLinks(this.links);
        this.modified = modified;
        this.staticValues = staticValues;
        this.assignments = assignments;
        this.modifiedFunctionalComponents = modifiedFunctionalComponents;
    }

    private static boolean ensureNoIdentityLinks(Map<Variable, LinkedVariables> links) {
        return links.entrySet().stream()
                .noneMatch(e -> e.getValue().variableStream()
                        .anyMatch(v -> v.equals(e.getKey())));
    }

    /*
    SV E=expression
    Assignment expression.field=value

    -> SV E=expression this.field=value
     */
    public StaticValues gatherAllStaticValues(Runtime runtime) {
        Map<Variable, Expression> updatedValueMap = new HashMap<>();
        if (staticValues.values() != null) updatedValueMap.putAll(staticValues.values());
        // now copy from assignments, using translation
        if (staticValues.expression() != null) {
            TypeInfo bestType = staticValues.expression().parameterizedType().bestTypeInfo();
            if(bestType != null && !assignments.isEmpty()) {
                Variable thisVar = runtime.newThis(staticValues.expression().parameterizedType());
                VariableExpression thisVarVe = runtime.newVariableExpressionBuilder().setVariable(thisVar)
                        .setSource(staticValues.expression().source()).build();
                TranslationMap tm = runtime.newTranslationMapBuilder().put(staticValues.expression(), thisVarVe).build();
                for (Map.Entry<Variable, StaticValues> entry : assignments.entrySet()) {
                    VariableExpression variableExpression = runtime.newVariableExpression(entry.getKey());
                    Expression translatedKey = variableExpression.translate(tm);
                    if (translatedKey != variableExpression && entry.getValue().expression() != null) {
                        updatedValueMap.put(((VariableExpression) translatedKey).variable(), entry.getValue().expression());
                    }
                }
            }
        }
        return new StaticValuesImpl(staticValues.type(), staticValues.expression(), false, updatedValueMap);
    }

    public static class Builder {
        private LinkedVariables linkedVariables = LinkedVariablesImpl.EMPTY;
        private StaticValues staticValues = StaticValuesImpl.NONE;
        private final Map<Variable, LinkedVariables> links = new HashMap<>();
        private final Map<Variable, StaticValues> assignments = new HashMap<>();
        private final Set<Variable> modified = new HashSet<>();
        private final Map<FieldReference, Boolean> modifiedFunctionalComponents = new HashMap<>();

        @Fluent
        public Builder merge(EvaluationResult evaluationResult) {
            linkedVariables = evaluationResult.linkedVariables;
            staticValues = evaluationResult.staticValues;

            evaluationResult.links.forEach((v, lv) -> links.merge(v, lv, LinkedVariables::merge));
            assert ensureNoIdentityLinks(this.links);
            evaluationResult.assignments.forEach((v, sv) -> assignments.merge(v, sv, StaticValues::merge));
            modified.addAll(evaluationResult.modified);
            modifiedFunctionalComponents.putAll(evaluationResult.modifiedFunctionalComponents);
            return this;
        }

        @Fluent
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
            LinkedVariables lvWithout = lv.remove(v -> v.equals(variable));
            if (!lvWithout.isEmpty()) {
                this.links.merge(variable, lvWithout, LinkedVariables::merge);
                assert ensureNoIdentityLinks(this.links);
            }
            return this;
        }

        @Fluent
        Builder merge(Variable variable, StaticValues sv) {
            assert variable != null;
            assert sv != null;
            if (!sv.isEmpty()) {
                this.assignments.merge(variable, sv, StaticValues::merge);
            }
            return this;
        }

        @Fluent
        Builder addModified(Variable variable) {
            modified.add(variable);
            return this;
        }

        @Fluent
        Builder addModifiedFunctionalInterfaceComponent(FieldReference fieldReference, boolean propagateOrFailOnModified) {
            modifiedFunctionalComponents.put(fieldReference, propagateOrFailOnModified);
            return this;
        }

        EvaluationResult build() {
            return new EvaluationResult(linkedVariables, Map.copyOf(links), Set.copyOf(modified),
                    staticValues, Map.copyOf(assignments), Map.copyOf(modifiedFunctionalComponents));
        }

        @Override
        public String toString() {
            return "Builder{" +
                   "linkedVariables=" + linkedVariables +
                   ", staticValues=" + staticValues +
                   ", links=" + links +
                   ", assignments=" + assignments +
                   ", modified=" + modified +
                   ", modifiedFunctionalComponents=" + modifiedFunctionalComponents +
                   '}';
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

    public Map<FieldReference, Boolean> modifiedFunctionalComponents() {
        return modifiedFunctionalComponents;
    }
}
