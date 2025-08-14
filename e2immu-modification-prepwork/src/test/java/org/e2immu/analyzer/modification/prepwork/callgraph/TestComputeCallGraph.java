package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.junit.jupiter.api.Test;

import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputeCallGraph.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestComputeCallGraph {
    @Test
    public void test() {
        long twoR = 2 * REFERENCES;
        assertEquals(2, weightedSumInteractions(twoR, 1, 1, 1,
                1, 1));
        long twoRtwoH = twoR + 2 * TYPE_HIERARCHY;
        assertEquals(4, weightedSumInteractions(twoRtwoH, 1, 1, 1,
                1, 1));
        assertEquals("HR", edgeValuePrinter(twoRtwoH));
        long twoRtwoHoneC = twoRtwoH + CODE_STRUCTURE;
        assertEquals(5, weightedSumInteractions(twoRtwoHoneC, 1, 1, 1,
                1, 1));
        assertEquals("SHR", edgeValuePrinter(twoRtwoHoneC));
    }
}
