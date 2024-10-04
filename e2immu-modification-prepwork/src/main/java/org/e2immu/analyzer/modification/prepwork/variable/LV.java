package org.e2immu.analyzer.modification.prepwork.variable;

import java.util.Map;

public interface LV extends Comparable<LV> {
    LV max(LV other);

    boolean isDone();

    String minimal();

    boolean isStaticallyAssignedOrAssigned();

    boolean mineIsAll();

    boolean theirsIsAll();

    /*
                    modifications travel the -4- links ONLY when the link is *M--4--xx
                     */
    boolean allowModified();

    LV correctTo(Map<Indices, Indices> correctionMap);

    LV prefixMine(int index);

    LV prefixTheirs(int index);

    LV changeToHc();

    boolean haveLinks();

    boolean isDependent();

    boolean isCommonHC();

    String label();

    boolean isInitialDelay();

    boolean isDelayed();

    Links links();

    LV reverse();

    boolean le(LV other);

    boolean lt(LV other);

    boolean ge(LV other);

    LV min(LV other);

    int value();
}
