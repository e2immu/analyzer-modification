package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.linkedvariables.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.delay.CausesOfDelay;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.analyzer.modification.prepwork.variable.Link;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.language.cst.api.analysis.Value;

import java.util.*;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl.NO_LINKS;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.IndependentImpl.*;

/*
initial delay: value can still become statically_assigned
 */
public class LVImpl implements LV {
    private static final int I_INITIAL_DELAY = -2;
    private static final int I_DELAY = -1;
    private static final int I_HC = 4;
    private static final int I_DEPENDENT = 2;

    public static final LV LINK_INITIAL_DELAY = new LVImpl(-2, NO_LINKS, "<initial delay>", null);
    public static final LV LINK_DELAYED = new LVImpl(-1, NO_LINKS, "<delayed>", null);
    public static final LV LINK_STATICALLY_ASSIGNED = new LVImpl(0, NO_LINKS, "-0-", DEPENDENT);
    public static final LV LINK_ASSIGNED = new LVImpl(1, NO_LINKS, "-1-", DEPENDENT);
    public static final LV LINK_DEPENDENT = new LVImpl(I_DEPENDENT, NO_LINKS, "-2-", DEPENDENT);

    // do not use for equality! Use LV.isCommonHC()
    public static final LV LINK_COMMON_HC = new LVImpl(I_HC, NO_LINKS, "-4-", INDEPENDENT_HC);
    public static final LV LINK_INDEPENDENT = new LVImpl(5, NO_LINKS, "-5-", INDEPENDENT);

    private final int value;
    private final Links links;
    private final String label;
    private final Value.Independent correspondingIndependent;

    private LVImpl(int value, Links links, String label, Value.Independent correspondingIndependent) {
        this.value = value;
        this.links = links;
        this.label = Objects.requireNonNull(label);
        assert !label.isBlank();
        this.correspondingIndependent = correspondingIndependent;
    }

    public static LV delay(CausesOfDelay someDelay) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean haveLinks() {
        return !links.map().isEmpty();
    }

    @Override
    public boolean isDependent() {
        return I_DEPENDENT == value;
    }

    @Override
    public boolean isCommonHC() {
        return I_HC == value;
    }

    @Override
    public int value() {
        return value;
    }

    public Links links() {
        return links;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean isInitialDelay() {
        return value == I_INITIAL_DELAY;
    }

    @Override
    public boolean isDelayed() {
        return value == I_DELAY || value == I_INITIAL_DELAY;
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
        return new LVImpl(I_HC, links, createLabel(links, I_HC), INDEPENDENT);
    }

    public static LV createDependent(Links links) {
        return new LVImpl(I_DEPENDENT, links, createLabel(links, I_DEPENDENT), INDEPENDENT);
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
                res.put(indicesInFrom, new LinkImpl(indicesInTo, false));
            }
        }
        assert !res.isEmpty();
        return new LinksImpl(Map.copyOf(res));
    }

    @Override
    public LV reverse() {
        if (isDependent()) {
            return createDependent(links.reverse());
        }
        if (isCommonHC()) {
            return createHC(links.reverse());
        }
        return this;
    }

    @Override
    public boolean le(LV other) {
        return value <= other.value();
    }

    @Override
    public boolean lt(LV other) {
        return value < other.value();
    }

    @Override
    public boolean ge(LV other) {
        return value >= other.value();
    }

    @Override
    public LV min(LV other) {
        if (isDelayed()) return this;
        if (other.isDelayed()) return other;
        if (value > other.value()) return other;
        if (isCommonHC() && other.isCommonHC()) {
            // IMPORTANT: the union only "compacts" on the "to" side for now, see Linking_1A.f9m()
            Links union = union(other.links());
            return createHC(union);
        }
        return this;
    }

    private Links union(Links other) {
        Map<Indices, Link> res = new HashMap<>(links.map());
        for (Map.Entry<Indices, Link> e : other.map().entrySet()) {
            res.merge(e.getKey(), e.getValue(), Link::merge);
        }
        return new LinksImpl(Map.copyOf(res));
    }

    private boolean sameLinks(LV other) {
        return links.equals(other.links());
    }

    @Override
    public LV max(LV other) {
        if (isDelayed()) return this;
        if (other.isDelayed()) return other;
        if (value < other.value()) return other;
        assert value != I_HC || other.value() != I_HC || sameLinks(other);
        return this;
    }

    @Override
    public boolean isDone() {
        return value != I_DELAY;
    }

    @Override
    public int compareTo(LV o) {
        return value - o.value();
    }

    public Value.Independent toIndependent() {
        // no correction for the "M" links in independent_hc down to dependent!
        return correspondingIndependent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LV lv = (LV) o;
        if (value != lv.value()) return false;
        return Objects.equals(links, lv.links());
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, links);
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public String minimal() {
        if (!links.map().isEmpty()) {
            return label;
        }
        return Integer.toString(value);
    }

    @Override
    public boolean isStaticallyAssignedOrAssigned() {
        return value == 0 || value == 1;
    }

    @Override
    public boolean mineIsAll() {
        return links.map().size() == 1 && links.map().containsKey(ALL_INDICES);
    }

    @Override
    public boolean theirsIsAll() {
        return links.map().size() == 1 && links.map().values().stream().findFirst().orElseThrow().to().equals(ALL_INDICES);
    }

    /*
    modifications travel the -4- links ONLY when the link is *M--4--xx
     */
    @Override
    public boolean allowModified() {
        if (value != I_HC) return true;
        if (links.map().size() == 1) {
            Map.Entry<Indices, Link> entry = links.map().entrySet().stream().findFirst().orElseThrow();
            return entry.getValue().mutable() && ALL_INDICES.equals(entry.getKey());
        }
        return false;
    }

    @Override
    public LV correctTo(Map<Indices, Indices> correctionMap) {
        if (links.map().isEmpty()) return this;
        boolean isHc = isCommonHC();
        Map<Indices, Link> updatedMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().correctTo(correctionMap)));
        Links updatedLinks = new LinksImpl(updatedMap);
        return isHc ? createHC(updatedLinks) : createDependent(updatedLinks);
    }

    @Override
    public LV prefixMine(int index) {
        if (isDelayed() || isStaticallyAssignedOrAssigned()) return this;
        if (links.map().isEmpty()) return this;
        Map<Indices, Link> newMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(e -> e.getKey().prefix(index), Map.Entry::getValue));
        Links newLinks = new LinksImpl(newMap);
        return isCommonHC() ? createHC(newLinks) : createDependent(newLinks);
    }

    @Override
    public LV prefixTheirs(int index) {
        if (isDelayed() || isStaticallyAssignedOrAssigned()) return this;
        if (links.map().isEmpty()) return this;
        Map<Indices, Link> newMap = links.map().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().prefixTheirs(index)));
        Links newLinks = new LinksImpl(newMap);
        return isCommonHC() ? createHC(newLinks) : createDependent(newLinks);
    }

    @Override
    public LV changeToHc() {
        if (value == I_DEPENDENT) {
            return createHC(links);
        }
        return this;
    }
}