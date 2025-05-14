package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;

import java.util.Set;

public interface AbstractInfoAnalyzer extends Analyzer {

    interface Output extends Analyzer.Output {
        Set<TypeInfo> waitForTypes();

        Set<MethodInfo> waitForMethods();
    }

    Output go();
}
