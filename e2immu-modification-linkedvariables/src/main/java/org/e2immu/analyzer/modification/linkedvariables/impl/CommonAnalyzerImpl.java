package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CommonAnalyzerImpl implements Analyzer {
    protected static final Logger DECIDE = LoggerFactory.getLogger("e2immu.modanalyzer.decide");
    protected static final Logger UNDECIDED = LoggerFactory.getLogger("e2immu.modanalyzer.delay");

}
