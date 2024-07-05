package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.linkedvariables.hcs.CausesOfDelay;
import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.linkedvariables.hcs.Indices;
import org.e2immu.language.cst.api.analysis.Value;

import java.util.*;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.CausesOfDelay.EMPTY;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.Indices.ALL_INDICES;
import static org.e2immu.analyzer.modification.linkedvariables.lv.Links.NO_LINKS;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

public class LV implements Comparable<LV> {

    private static final int I_DELAY = -1;
    private static final int I_HC = 4;
    private static final int I_DEPENDENT = 2;

    public static final LV LINK_STATICALLY_ASSIGNED = new LV(0, NO_LINKS, "-0-", EMPTY, DEPENDENT);
    public static final LV LINK_ASSIGNED = new LV(1, NO_LINKS, "-1-", EMPTY, DEPENDENT);
    public static final LV LINK_DEPENDENT = new LV(I_DEPENDENT, NO_LINKS, "-2-", EMPTY, DEPENDENT);

    // do not use for equality! Use LV.isCommonHC()
    public static final LV LINK_COMMON_HC = new LV(I_HC, NO_LINKS, "-4-", EMPTY, INDEPENDENT_HC);
    public static final LV LINK_INDEPENDENT = new LV(5, NO_LINKS, "-5-", EMPTY, INDEPENDENT);

    private final int value;

    public boolean haveLinks() {
        return !links.map().isEmpty();
    }


    private final Links links;
    private final String label;
    private final CausesOfDelay causesOfDelay;
    private final Value.Independent correspondingIndependent;

    public boolean isDependent() {
        return I_DEPENDENT == value;
    }

    public boolean isCommonHC() {
        return I_HC == value;
    }

    private LV(int value, Links links, String label, CausesOfDelay causesOfDelay, Value.Independent correspondingIndependent) {
        this.value = value;
        this.links = links;
        this.label = Objects.requireNonNull(label);
        assert !label.isBlank();
        this.causesOfDelay = Objects.requireNonNull(causesOfDelay);
        this.correspondingIndependent = correspondingIndependent;
    }

    public static LV initialDelay() {
        return delay(CausesOfDelay.INITIAL_DELAY);
    }

    public int value() {
        return value;
    }

    public Links links() {
        return links;
    }

    public String label() {
        return label;
    }

    public CausesOfDelay causesOfDelay() {
        return causesOfDelay;
    }

    public static LV delay(CausesOfDelay causes) {
        assert causes.isDelayed();
        return new LV(I_DELAY, NO_LINKS, causes.label(), causes, null);
    }

    private static String createLabel(Links links, int hc) {
        List<String> from = new ArrayList<>();
        List<String> to = new ArrayList<>();
        int countAll = 0;
        for (Map.Entry<Indices, Link> e : links.map().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            boolean mutable = e.getValue().mutable();
            boolean fromIsAll = e.getKey().equals(ALL_INDICES);
            String f = (fromIsAll ? "*" : "" + e.getKey()) + (mutable ? "M" : "");
            Indices i = e.getValue().to();
            assert i != null;
            boolean toIsAll = i.equals(ALL_INDICES);
            String t = (toIsAll ? "*" : "" + i) + (mutable ? "M" : "");
            assert !(fromIsAll && toIsAll);
            from.add(f);
            to.add(t);
            countAll += (fromIsAll || toIsAll) ? 1 : 0;
        }
        assert countAll <= 1;
        assert from.size() == to.size();
        assert hc != I_HC || !from.isEmpty();
        return String.join(",", from) + "-" + hc + "-" + String.join(",", to);
    }


    public static LV createHC(Links links) {
        return new LV(I_HC, links, createLabel(links, I_HC), EMPTY, INDEPENDENT);
    }

    public static LV createDependent(Links links) {
        return new LV(I_DEPENDENT, links, createLabel(links, I_DEPENDENT), EMPTY, INDEPENDENT);
    }

    /*
    go from hidden content selectors to an actual Links object, in the method context.
    Assume, for now, that mutable == false.
     */
    public static Links matchingLinks(HiddenContentSelector from, HiddenContentSelector to) {
        Map<Indices, Link> res = new HashMap<>();
        for (Map.Entry<Integer, Indices> entry : from.getMap().entrySet()) {
            int hctIndex = entry.getKey();
            Indices indicesInTo = to.getMap().get(hctIndex);
            if (indicesInTo != null) {
                Indices indicesInFrom = entry.getValue();
                res.put(indicesInFrom, new Link(indicesInTo, false));
            }
        }
        assert !res.isEmpty();
        return new Links(Map.copyOf(res));
    }


    public LV reverse() {
        if (isDependent()) {
            return createDependent(links.reverse());
        }
        if (isCommonHC()) {
            return createHC(links.reverse());
        }
        return this;
    }

    public boolean isDelayed() {
        return causesOfDelay.isDelayed();
    }

    public boolean le(LV other) {
        return value <= other.value;
    }

    public boolean lt(LV other) {
        return value < other.value;
    }

    public boolean ge(LV other) {
        return value >= other.value;
    }

    public LV min(LV other) {
        if (isDelayed()) {
            if (other.isDelayed()) {
                return delay(causesOfDelay.merge(other.causesOfDelay));
            }
            return this;
        }
        if (other.isDelayed()) return other;
        if (value > other.value) return other;
        if (isCommonHC() && other.isCommonHC()) {
            // IMPORTANT: the union only "compacts" on the "to" side for now, see Linking_1A.f9m()
            Links union = union(other.links);
            return createHC(union);
        }
        return this;
    }

    private Links union(Links other) {
        Map<Indices, Link> res = new HashMap<>(links.map());
        for (Map.Entry<Indices, Link> e : other.map().entrySet()) {
            res.merge(e.getKey(), e.getValue(), Link::merge);
        }
        return new Links(Map.copyOf(res));
    }

    private boolean sameLinks(LV other) {
        return links.equals(other.links);
    }

    public LV max(LV other) {
        if (isDelayed()) {
            if (other.isDelayed()) {
                return delay(causesOfDelay.merge(other.causesOfDelay));
            }
            return this;
        }
        if (other.isDelayed()) return other;
        if (value < other.value) return other;
        assert value != I_HC || other.value != I_HC || sameLinks(other);
        return this;
    }

    public boolean isDone() {
        return causesOfDelay.isDone();
    }

    @Override
    public int compareTo(LV o) {
        return value - o.value;
    }

    public Value.Independent toIndependent() {
        // no correction for the "M" links in independent_hc down to dependent!
        return correspondingIndependent;
    }

    public boolean isInitialDelay() {
        return causesOfDelay() == CausesOfDelay.INITIAL_DELAY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LV lv = (LV) o;
        if (value != lv.value) return false;
        return Objects.equals(links, lv.links)
               && Objects.equals(causesOfDelay, lv.causesOfDelay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, links, causesOfDelay);
    }

    @Override
    public String toString() {
        return label;
    }

    public String minimal() {
        if (!links.map().isEmpty()) {
            return label;
        }
        return Integer.toString(value);
    }

    public boolean isStaticallyAssignedOrAssigned() {
        return value == 0 || value == 1;
    }

    public boolean mineIsAll() {
        return links.map().size() == 1 && links.map().containsKey(ALL_INDICES);
    }

    public boolean theirsIsAll() {
        return links.map().size() == 1 && links.map().values().stream().findFirst().orElseThrow().to().equals(ALL_INDICES);
    }

    /*
    modifications travel the -4- links ONLY when the link is *M--4--xx
     */
    public boolean allowModified() {
        if (value != I_HC) return true;
        if (links.map().size() == 1) {
            Map.Entry<Indices, Link> entry = links.map().entrySet().stream().findFirst().orElseThrow();
            return entry.getValue().mutable() && ALL_INDICES.equals(entry.getKey());
        }
        return false;
    }

    public LV correctTo(Map<Indices, Indices> correctionMap) {
        if (links.map().isEmpty()) return this;
        boolean isHc = isCommonHC();
        Map<Indices, Link> updatedMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().correctTo(correctionMap)));
        Links updatedLinks = new Links(updatedMap);
        return isHc ? createHC(updatedLinks) : createDependent(updatedLinks);
    }

    public LV prefixMine(int index) {
        if (isDelayed() || isStaticallyAssignedOrAssigned()) return this;
        if (links.map().isEmpty()) return this;
        Map<Indices, Link> newMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().prefix(index), Map.Entry::getValue));
        Links newLinks = new Links(newMap);
        return isCommonHC() ? LV.createHC(newLinks) : LV.createDependent(newLinks);
    }

    public LV prefixTheirs(int index) {
        if (isDelayed() || isStaticallyAssignedOrAssigned()) return this;
        if (links.map().isEmpty()) return this;
        Map<Indices, Link> newMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().prefixTheirs(index)));
        Links newLinks = new Links(newMap);
        return isCommonHC() ? LV.createHC(newLinks) : LV.createDependent(newLinks);
    }

    public LV changeToHc() {
        if (value == I_DEPENDENT) {
            return createHC(links);
        }
        return this;
    }
}