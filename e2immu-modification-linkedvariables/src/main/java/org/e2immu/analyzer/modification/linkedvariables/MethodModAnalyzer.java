package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.info.MethodInfo;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/*
Phase 1.
Single method analyzer.

Analyzes statements in a method, and tries to determine if the method is @Modified.

It is NOT concerned with solving the @Independent property of the method, because to do that, the linking info
of the fields must be known.
 */
public interface MethodModAnalyzer extends Analyzer {

    interface Output extends Analyzer.Output {

        Collection<MethodInfo> waitingFor();

        Map<String, Integer> infoHistogram();
    }

    Output go(MethodInfo methodInfo, boolean activateCycleBreaking);
}
