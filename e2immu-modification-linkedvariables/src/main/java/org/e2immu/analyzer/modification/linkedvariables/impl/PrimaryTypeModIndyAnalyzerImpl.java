package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.PrimaryTypeModIndyAnalyzer;
import org.e2immu.language.cst.api.info.TypeInfo;

/*
Phase 3.

Does modification and independence of parameters, and independence of methods, and breaks internal cycles.

Modification of methods is done in Phase 1
Modification and independence of fields is done in Phase 2
Immutable, independence of types is done in Phase 4.1.

 */
public class PrimaryTypeModIndyAnalyzerImpl extends CommonAnalyzerImpl implements PrimaryTypeModIndyAnalyzer {
    @Override
    public Output go(TypeInfo primaryType) {
        return null;
    }
}
