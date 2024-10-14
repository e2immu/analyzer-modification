package org.e2immu.analyzer.modification.linkedvariables.graph.impl;

import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.language.cst.api.variable.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.FIELD_INDICES;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestWeightedGraph19F extends CommonWG {

    /*
      Exit ee = new Exit(e)
      LoopDataImpl ldi = new LoopDataImpl(ee);

      ldi FM-2-*M ee FM-2-*M e

      and we expect a link  ldi FM-2-*M e
     */
    Variable ldi, ee, e;
    WeightedGraph wg;
    ShortestPath shortestPath;

    @BeforeEach
    public void beforeEach() {
        ldi = makeVariable("ldi");
        ee = makeVariable("ee");
        e = makeVariable("e");

        LV link = LVImpl.createDependent(new LinksImpl(Map.of(FIELD_INDICES, new LinkImpl(ALL_INDICES, true))));

        assertEquals("FM-2-*M", link.toString());

        wg = new WeightedGraphImpl();
        wg.addNode(ldi, Map.of(ee, link));
        wg.addNode(ee, Map.of(e, link));
        shortestPath = wg.shortestPath();
        assertEquals("0(1:*M-2-FM)1(0:FM-2-*M;2:*M-2-FM)2(1:FM-2-*M)",
                ((ShortestPathImpl) shortestPath).getCacheKey());
    }

    @Test
    @DisplayName("start in ldi")
    public void testM() {
        Map<Variable, LV> startAt = shortestPath.links(ldi, null);
        assertEquals(3, startAt.size());
        assertEquals(v0, startAt.get(ldi));
        assertEquals("FM-2-*M", startAt.get(ee).toString());
        assertEquals("FM-2-*M", startAt.get(e).toString());
    }

    @Test
    @DisplayName("start in ee")
    public void testEE() {
        Map<Variable, LV> startAt = shortestPath.links(ee, null);
        assertEquals(3, startAt.size(), "Have " + startAt);
        assertEquals(v0, startAt.get(ee));
        assertEquals("FM-2-*M", startAt.get(e).toString());
        assertEquals("*M-2-FM", startAt.get(ldi).toString());
    }

    @Test
    @DisplayName("start in e")
    public void testE() {
        Map<Variable, LV> startAt = shortestPath.links(e, null);
        assertEquals(3, startAt.size(), "Have " + startAt);
        assertEquals(v0, startAt.get(e));
        assertEquals("*M-2-FM", startAt.get(ee).toString());
        assertEquals("*M-2-FM", startAt.get(ldi).toString());
    }

}
