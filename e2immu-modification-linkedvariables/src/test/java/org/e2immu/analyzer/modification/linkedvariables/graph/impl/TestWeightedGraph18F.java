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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestWeightedGraph18F extends CommonWG {

    /*
      List<String> l = new ArrayList<>();
      Set<Integer> s = new HashSet<>();
      R r = new R(s, 3, l);

      r  FM----2----0* l
      FM
      |
      2
      |
      *M
      s

     */
    Variable r, s, l;
    WeightedGraph wg;
    ShortestPath shortestPath;

    @BeforeEach
    public void beforeEach() {
        r = makeVariable("r");
        s = makeVariable("s");
        l = makeVariable("l");

        LV link = LVImpl.createDependent(new LinksImpl(Map.of(FIELD_INDICES, new LinkImpl(ALL_INDICES, true))));

        assertEquals("FM-2-*M", link.toString());

        wg = new WeightedGraphImpl();
        wg.addNode(r, Map.of(s, link, l, link));

        shortestPath = wg.shortestPath();
        assertEquals("0(1:*M-2-FM)1(0:FM-2-*M;2:FM-2-*M)2(1:*M-2-FM)",
                ((ShortestPathImpl) shortestPath).getCacheKey());
    }

    @Test
    @DisplayName("start in r")
    public void testM() {
        Map<Variable, LV> startAt = shortestPath.links(r, null);
        assertEquals(3, startAt.size());
        assertEquals(v0, startAt.get(r));
        assertEquals("FM-2-*M", startAt.get(s).toString());
        assertEquals("FM-2-*M", startAt.get(l).toString());
    }


    @Test
    @DisplayName("start in l")
    public void testL() {
        Map<Variable, LV> startAt = shortestPath.links(l, null);
        assertEquals(2, startAt.size(), "Have " + startAt);
        assertEquals(v0, startAt.get(l));
        assertEquals("*M-2-FM", startAt.get(r).toString());
    }

}
