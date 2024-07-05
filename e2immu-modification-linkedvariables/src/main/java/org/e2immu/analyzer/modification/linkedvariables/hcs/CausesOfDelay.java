package org.e2immu.analyzer.modification.linkedvariables.hcs;

public record CausesOfDelay(boolean isDelayed) {

    public static final CausesOfDelay EMPTY = new CausesOfDelay(false);

    public boolean isDone() {
        return !isDelayed;
    }
}
