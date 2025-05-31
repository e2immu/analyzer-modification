package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.InfoMap;
import org.e2immu.language.cst.api.translate.TranslationMap;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.util.internal.util.MapUtil;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.*;


public class LinkedVariablesImpl implements LinkedVariables, Comparable<Value>,
        Iterable<Map.Entry<Variable, LV>> {
    // never use .equals() here, marker
    public static final LinkedVariables NOT_YET_SET = new LinkedVariablesImpl(Map.of());
    // use .equals, not a marker
    public static final LinkedVariables EMPTY = new LinkedVariablesImpl(Map.of());

    public static final Property LINKED_VARIABLES_METHOD = new PropertyImpl("linkedVariablesOfMethod", EMPTY);
    public static final Property LINKED_VARIABLES_PARAMETER = new PropertyImpl("linkedVariablesOfParameter", EMPTY);
    public static final Property LINKED_VARIABLES_FIELD = new PropertyImpl("linkedVariablesOfField", EMPTY);

    public record ListOfLinkedVariablesImpl(List<LinkedVariables> list) implements ListOfLinkedVariables {
        public static final ListOfLinkedVariablesImpl EMPTY = new ListOfLinkedVariablesImpl(List.of());

        @Override
        public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
            // not yet streamed
            return null;
        }

        @Override
        public boolean isDefault() {
            return list.isEmpty();
        }

        @Override
        public Value rewire(InfoMap infoMap) {
            List<LinkedVariables> newList = list.stream()
                    .map(lv -> (LinkedVariables) lv.rewire(infoMap)).toList();
            return new ListOfLinkedVariablesImpl(newList);
        }

        @Override
        public boolean overwriteAllowed(Value newValue) {
            List<LinkedVariables> other = ((ListOfLinkedVariablesImpl) newValue).list;
            int i = 0;
            for (LinkedVariables lv : list) {
                LinkedVariables lv2 = other.get(i++);
                if (!lv.overwriteAllowed(lv2)) return false;
            }
            return true;
        }
    }

    // added to MethodCall objects
    public static final Property LINKED_VARIABLES_ARGUMENTS = new PropertyImpl("linkedVariablesOfArguments",
            ListOfLinkedVariablesImpl.EMPTY);

    // for methods
    public static final Property LINKS_TO_OBJECT = new PropertyImpl("linksToObject", EMPTY);

    public static final String NOT_YET_SET_STR = "NOT_YET_SET";

    private final Map<Variable, LV> variables;


    private LinkedVariablesImpl(Map<Variable, LV> variables) {
        assert variables != null;
        this.variables = variables;
        assert variables.values().stream().noneMatch(lv -> lv == LINK_INDEPENDENT || lv == LINK_COMMON_HC);
    }

    public static LV fromIndependentToLinkedVariableLevel(Value.Independent independent) {
        if (independent.isIndependent()) return LINK_INDEPENDENT;
        if (!independent.isAtLeastIndependentHc()) return LINK_DEPENDENT;
        return LINK_COMMON_HC;
    }

    @Override
    public boolean isDefault() {
        return this == NOT_YET_SET;
    }

    // in the next iteration, a type may become independent rather than dependent
    @Override
    public boolean overwriteAllowed(Value newValue) {
        LinkedVariables other = (LinkedVariables) newValue;
        if (other.isDefault()) return true;
        return variables.entrySet().stream().allMatch(e -> {
            LV lv = other.value(e.getKey());
            return lv == null || e.getValue().overwriteAllowed(lv);
        });
    }

    @Override
    public Codec.EncodedValue encode(Codec codec, Codec.Context context) {
        return null;// not yet streamed
    }

    @Override
    public boolean isNotYetSet() {
        return this == NOT_YET_SET;
    }

    public boolean isDelayed() {
        if (this == NOT_YET_SET) return true;
        return variables.values().stream().anyMatch(LV::isDelayed);
    }

    public static LinkedVariables of(Variable variable, LV value) {
        return new LinkedVariablesImpl(Map.of(variable, value));
    }

    public static LinkedVariables of(Map<Variable, LV> map) {
        return new LinkedVariablesImpl(Map.copyOf(map));
    }

    public static LinkedVariables of(Variable var1, LV v1, Variable var2, LV v2) {
        return new LinkedVariablesImpl(Map.of(var1, v1, var2, v2));
    }

    public static boolean isAssigned(LV level) {
        return level == LINK_STATICALLY_ASSIGNED || level == LINK_ASSIGNED;
    }

    @Override
    public Iterator<Map.Entry<Variable, LV>> iterator() {
        return variables.entrySet().iterator();
    }

    /*
    goal of the 'minimum' parameter:

    x = new X(b); expression b has linked variables b,0 when b is a variable expression
    the variable independent gives the independence of the first parameter of the constructor X(b)

    if DEPENDENT, then x is linked in an accessible way to b (maybe it stores b); max(0,0)=0
    if INDEPENDENT_1, then changes to b may imply changes to the hidden content of x; max(0,1)=1

    if the expression is e.g. c,1, as in new B(c)

    if DEPENDENT, then x is linked in an accessible way to an object b which is content linked to c
    changes to c have an impact on the hidden content of b, which is linked in an accessible way
    (leaving the hidden content hidden?) to x  max(1,0)=1
    if INDEPENDENT_1, then x is hidden content linked to b is hidden content linked to c, max(1,1)=1
     */
    @Override
    public LinkedVariables merge(LinkedVariables other, LV minimum) {
        if (this == NOT_YET_SET || other == NOT_YET_SET) return NOT_YET_SET;

        HashMap<Variable, LV> map = new HashMap<>(variables);
        other.variables().forEach((v, i) -> {
            LV newValue = minimum.isInitialDelay() ? i : i.max(minimum);
            LV inMap = map.get(v);
            if (inMap == null) {
                map.put(v, newValue);
            } else {
                // once 0, always 0 (we do not accept delays on 0!)
                LV merged = newValue.equals(LINK_STATICALLY_ASSIGNED) || inMap.equals(LINK_STATICALLY_ASSIGNED)
                        ? LINK_STATICALLY_ASSIGNED : newValue.min(inMap);
                map.put(v, merged);
            }
        });
        if (map.isEmpty()) return EMPTY;
        return of(map);
    }

    @Override
    public LinkedVariables merge(LinkedVariables other) {
        return merge(other, LINK_INITIAL_DELAY); // no effect
    }

    @Override
    public boolean isEmpty() {
        return variables.isEmpty();
    }

    private static final Comparator<Map.Entry<Variable, LV>> COMPARATOR = (e1, e2) -> {
        Variable v1 = e1.getKey();
        Variable v2 = e2.getKey();
        int c = v1.simpleName().compareTo(v2.simpleName());
        if (c == 0) {
            return v1.fullyQualifiedName().compareTo(v2.fullyQualifiedName());
        }
        return c;
    };

    @Override
    public String toString() {
        if (this == NOT_YET_SET) return NOT_YET_SET_STR;
        if (this == EMPTY || variables.isEmpty()) return "";
        return variables.entrySet().stream()
                .sorted(COMPARATOR)
                .map(e -> e.getValue().label() + ":" + e.getKey().simpleName())
                .collect(Collectors.joining(", "));
    }

    public LV select(int index) {
        if (this == NOT_YET_SET || this == EMPTY || variables.isEmpty()) throw new UnsupportedOperationException();
        return variables.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().simpleName() + ":" + e.getValue().value()))
                .skip(index)
                .map(Map.Entry::getValue)
                .findFirst().orElseThrow();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinkedVariables that = (LinkedVariables) o;
        return variables.equals(that.variables());
    }

    @Override
    public int hashCode() {
        return variables.hashCode();
    }

    @Override
    public boolean contains(Variable variable) {
        return variables.containsKey(variable);
    }

    @Override
    public Stream<Map.Entry<Variable, LV>> stream() {
        return variables.entrySet().stream();
    }

    @Override
    public LinkedVariables maximum(LV other) {
        if (this == NOT_YET_SET) return NOT_YET_SET;
        return of(variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> other.max(e.getValue()))));
    }

    @Override
    public Stream<Variable> variableStream() {
        return variables.keySet().stream();
    }

    @Override
    public Stream<Variable> variablesAssigned() {
        return variables.entrySet().stream()
                .filter(e -> isAssigned(e.getValue()))
                .map(Map.Entry::getKey);
    }

    @Override
    public LinkedVariables translate(TranslationMap translationMap) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        var translatedVariables = variables.entrySet().stream()
                .collect(Collectors.toMap(e -> translationMap.translateVariable(e.getKey()), Map.Entry::getValue, LV::min));
        if (translatedVariables.equals(variables)) return this;
        return of(translatedVariables);
    }

    @Override
    public LinkedVariables changeToDelay() {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> LINK_DELAYED));
        return of(map);
    }

    @Override
    public LinkedVariables remove(Set<Variable> reassigned) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .filter(e -> !reassigned.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return of(map);
    }

    @Override
    public LinkedVariables remove(Predicate<Variable> remove) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .filter(e -> !remove.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return of(map);
    }

    @Override
    public LinkedVariables changeNonStaticallyAssignedToDelay() {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    LV lv = e.getValue();
                    return LINK_STATICALLY_ASSIGNED.equals(lv) ? LINK_STATICALLY_ASSIGNED : LINK_DELAYED;
                }));
        return of(map);
    }

    @Override
    public LinkedVariables changeAllTo(LV value) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> value));
        return of(map);
    }

    @Override
    public LinkedVariables changeAllToUnlessDelayed(LV value) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().isDelayed() ? e.getValue() : value));
        return of(map);
    }

    @Override
    public LV value(Variable variable) {
        if (this == NOT_YET_SET) return LINK_DELAYED;
        return variables.get(variable);
    }

    public static boolean isAssignedOrLinked(LV dependent) {
        return dependent.ge(LINK_STATICALLY_ASSIGNED) && dependent.le(LINK_DEPENDENT);
    }

    @Override
    public Map<Variable, LV> variables() {
        return variables;
    }

    public boolean isDone() {
        if (this == NOT_YET_SET) return false;
        return !isDelayed();
    }

    @Override
    public int compareTo(Value o) {
        if (o instanceof LinkedVariables lv) {
            return MapUtil.compareMaps(variables, lv.variables());
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Variable> staticallyAssigned() {
        return staticallyAssignedStream().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean identicalStaticallyAssigned(LinkedVariables linkedVariables) {
        return staticallyAssigned().equals(linkedVariables.staticallyAssigned());
    }

    @Override
    public Set<Variable> directAssignmentVariables() {
        if (isEmpty() || this == NOT_YET_SET) return Set.of();
        return variables.entrySet().stream().filter(e -> e.getValue().equals(LINK_STATICALLY_ASSIGNED))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Stream<Variable> staticallyAssignedStream() {
        return variables.entrySet().stream().filter(e -> LINK_STATICALLY_ASSIGNED.equals(e.getValue())).map(Map.Entry::getKey);
    }

    @Override
    public Stream<Variable> dependentVariables() {
        return variables.entrySet().stream().filter(e -> e.getValue().isDependent()).map(Map.Entry::getKey);
    }

    @Override
    public Stream<Variable> assignedOrDependentVariables() {
        return variables.entrySet().stream().filter(e -> isAssignedOrLinked(e.getValue())).map(Map.Entry::getKey);
    }

    @Override
    public LinkedVariables removeStaticallyAssigned() {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .filter(e -> !e.getValue().equals(LINK_STATICALLY_ASSIGNED))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return of(map);
    }

    @Override
    public LinkedVariables map(Function<LV, LV> function) {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> function.apply(e.getValue())));
        return of(map);
    }

    @Override
    public boolean compatibleWith(HiddenContentSelector hcs) {
        for (Map.Entry<Variable, LV> entry : this) {
            for (Map.Entry<Indices, Link> link : entry.getValue().links().map().entrySet()) {
                for (Index index : link.getKey().set()) {
                    for (int i : index.list()) {
                        if (i >= 0) {
                            Indices indices = hcs.getMap().get(i);
                            assert indices != null;
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public LinkedVariables reverseAll() {
        if (isEmpty() || this == NOT_YET_SET) return this;
        Map<Variable, LV> map = variables.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().reverse()));
        return of(map);
    }

}
