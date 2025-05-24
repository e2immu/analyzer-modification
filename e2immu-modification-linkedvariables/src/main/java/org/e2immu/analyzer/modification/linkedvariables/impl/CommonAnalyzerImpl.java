package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.linkedvariables.IteratingAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CommonAnalyzerImpl {
    public static final Logger DECIDE = LoggerFactory.getLogger("e2immu.modanalyzer.decide");
    public static final Logger UNDECIDED = LoggerFactory.getLogger("e2immu.modanalyzer.delay");

    protected final IteratingAnalyzer.Configuration configuration;

    protected CommonAnalyzerImpl(IteratingAnalyzer.Configuration configuration) {
        this.configuration = configuration;
    }
}
