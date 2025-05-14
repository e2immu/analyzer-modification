package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;

import java.util.Set;

/*
Phase 4.1

Given the modification and independence state of the fields of a type, compute its @Immutable properties.
This analyzer computes @Immutable across all subtypes, because the fields are visible across the subtypes,
hence their modification and independence status is shared.

It is still possible to have to wait for other type's @Immutable status, because of extensions.
 */
public interface PrimaryTypeImmutableAnalyzer extends Analyzer {

    interface Output extends Analyzer.Output {

        Set<FieldInfo> internalWaitingFor();
        Set<TypeInfo> externalWaitingForCannotCauseCycles();
    }
}
