/*
 * e2immu: a static code analyser for effective and eventual immutability
 * Copyright 2020-2021, Bart Naudts, https://www.e2immu.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.e2immu.analyzer.modification.linkedvariables.graph.impl;


import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.language.cst.api.variable.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestWeightedGraph5 extends CommonWG {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestWeightedGraph5.class);

    Variable x1, x2, x3, f, thisVar;
    WeightedGraph wg1, wg2;
    List<WeightedGraph> wgs;

    @BeforeEach
    public void beforeEach() {
        x1 = makeVariable("x1");
        x2 = makeVariable("x2");
        x3 = makeVariable("x3");
        f = makeVariable("f");
        thisVar = makeVariable("this");

        wg1 = new WeightedGraphImpl();
        wg1.addNode(x1, Map.of(f, v0, thisVar, v4, x3, delay));
        wg1.addNode(x2, Map.of(f, v0));
        wg1.addNode(x3, Map.of(x1, delay, f, delay, thisVar, delay));
        wg1.addNode(f, Map.of(x1, v0, x2, v0, x3, delay, thisVar, delay));
        wg1.addNode(thisVar, Map.of());

        wg2 = new WeightedGraphImpl();
        wg2.addNode(f, Map.of(x1, v0, x2, v0, x3, delay, thisVar, delay));
        wg2.addNode(x1, Map.of(f, v0, thisVar, v4, x3, delay));
        wg2.addNode(x2, Map.of(f, v0));
        wg2.addNode(x3, Map.of(x1, delay, f, delay, thisVar, delay));
        wg2.addNode(thisVar, Map.of());

        wgs = List.of(wg1, wg2);
    }

    @Test
    public void test1() {
        int cnt = 0;
        for (WeightedGraph wg : wgs) {
            LOGGER.info("WeightedGraph {}", cnt);
            Map<Variable, LV> startAtX1 = wg.shortestPath().links(x1, null);
            assertEquals(5, startAtX1.size());
            assertEquals(v0, startAtX1.get(f));
            assertEquals(v0, startAtX1.get(x1));
            assertEquals(v0, startAtX1.get(x2));
            assertEquals(delay, startAtX1.get(x3));
            assertEquals(delay, startAtX1.get(thisVar));
            cnt++;
        }
    }
}
