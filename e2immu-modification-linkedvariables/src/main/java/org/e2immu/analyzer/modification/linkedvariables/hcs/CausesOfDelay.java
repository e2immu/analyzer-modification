package org.e2immu.analyzer.modification.linkedvariables.hcs;

public record CausesOfDelay(boolean isDelayed, String label) {

    public static final CausesOfDelay EMPTY = new CausesOfDelay(false, null);
    public static final CausesOfDelay INITIAL_DELAY = new CausesOfDelay(true, "initial");

    public boolean isDone() {
        return !isDelayed;
    }

    public boolean isInitialDelay() {
        return this == INITIAL_DELAY;
    }

    public CausesOfDelay merge(CausesOfDelay other) {
        if (this.isDelayed) return this;
        return other;
    }
}
