package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;

import java.util.List;

public interface ModAnalyzerForTesting {
    List<Throwable> go(List<Info> analysisOrder);
}
