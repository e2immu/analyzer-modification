package org.e2immu.analyzer.modification.linkedvariables;

import java.util.List;

public interface Analyzer {

    enum CycleBreakingStrategy {
        NONE, NO_INFORMATION_IS_NON_MODIFYING
    }

    interface Output {
        List<Throwable> problemsRaised();
    }
}
