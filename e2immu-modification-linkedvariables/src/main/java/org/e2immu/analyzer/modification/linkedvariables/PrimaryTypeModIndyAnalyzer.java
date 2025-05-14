package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.language.cst.api.info.TypeInfo;

/*
Phase 3:

Given the modification and linking of methods and fields,
compute independence of methods, fields, parameters, and primary type,
and forward the modification of fields to the parameters linked to it.
 */
public interface PrimaryTypeModIndyAnalyzer extends Analyzer {

    Output go(TypeInfo primaryType);
}
