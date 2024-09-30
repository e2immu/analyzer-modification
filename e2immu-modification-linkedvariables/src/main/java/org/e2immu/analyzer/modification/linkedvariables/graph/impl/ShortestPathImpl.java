package org.e2immu.analyzer.modification.linkedvariables.graph.impl;

import org.e2immu.analyzer.modification.linkedvariables.graph.Cache;
import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl;
import org.e2immu.analyzer.modification.prepwork.delay.CausesOfDelay;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.*;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl.NO_LINKS;

public class ShortestPathImpl implements ShortestPath {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShortestPathImpl.class);

    private final Map<Variable, Integer> variableIndex;
    private final Variable[] variables;
    private final Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edges;
    private final Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edgesHigh;
    private final CausesOfDelay someDelay;
    private final DijkstraShortestPath dijkstraShortestPath;
    private final LinkMap linkMap;

    ShortestPathImpl(Map<Variable, Integer> variableIndex,
                     Variable[] variables,
                     Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edges,
                     Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edgesHigh,
                     CausesOfDelay someDelay,
                     LinkMap linkMap) {
        this.variables = variables;
        this.edges = edges;
        this.edgesHigh = edgesHigh;
        this.variableIndex = variableIndex;
        this.someDelay = someDelay;
        dijkstraShortestPath = new DijkstraShortestPath(NO_LINKS, ShortestPathImpl::distancePrinter,
                ShortestPathImpl::testKeepNoLinks);
        this.linkMap = linkMap;
    }

    static final int BITS = 10;
    static final long STATICALLY_ASSIGNED = 1L;

    // *********** DELAY LOWEST (AFTER STATICALLY ASSIGNED) *************

    static final long DELAYED = 1L << BITS;
    static final long ASSIGNED = 1L << (2 * BITS);
    static final long DEPENDENT = 1L << (3 * BITS);
    static final long COMMON_HC = 1L << (4 * BITS);

    public static long toDistanceComponent(LV lv) {
        if (LINK_STATICALLY_ASSIGNED.equals(lv)) return STATICALLY_ASSIGNED;
        if (lv.isDelayed()) return DELAYED;
        if (LINK_ASSIGNED.equals(lv)) return ASSIGNED;
        if (lv.isDependent()) return DEPENDENT;
        return COMMON_HC;
    }

    public static boolean testKeepNoLinks(long l) {
        if (l == Long.MAX_VALUE) return true;
        LV lv = fromDistanceSum(l, CausesOfDelay.DELAY);
        assert lv != null;
        return !LVImpl.LINK_ASSIGNED.equals(lv) && !LINK_STATICALLY_ASSIGNED.equals(lv);
    }

    public static String distancePrinter(long l) {
        if (l == Long.MAX_VALUE) return "<no link>";
        LV lv = fromDistanceSum(l, CausesOfDelay.DELAY);
        assert lv != null;
        return lv.label();
    }

    // used to produce the result.
    public static LV fromDistanceSum(long l, CausesOfDelay someDelay) {
        if (l == Long.MAX_VALUE) return null; // no link
        // 0L ~ no link ~ self STATICALLY_ASSIGNED
        if (l < DELAYED) return LINK_STATICALLY_ASSIGNED;
        if (((l >> BITS) & (DELAYED - 1)) > 0) {
            assert someDelay != null && someDelay.isDelayed();
            return LVImpl.delay(someDelay);
        }
        if (l < DEPENDENT) return LINK_ASSIGNED;
        if (l < COMMON_HC) return LINK_DEPENDENT;
        return LINK_COMMON_HC;
    }

    private static boolean isDelay(long l) {
        return l >= DELAYED && l < ASSIGNED;
    }

    // *********** DELAY HIGHEST *************

    static final long ASSIGNED_H = 1L << BITS;
    static final long DEPENDENT_H = 1L << (2 * BITS);
    static final long HC_MUTABLE_H = 1L << (3 * BITS);
    static final long COMMON_HC_H = 1L << (4 * BITS);
    static final long DELAYED_H = 1L << (5 * BITS);

    public static long toDistanceComponentHigh(LV lv) {
        if (LINK_STATICALLY_ASSIGNED.equals(lv)) return STATICALLY_ASSIGNED;
        if (LINK_ASSIGNED.equals(lv)) return ASSIGNED_H;
        if (lv.isDependent()) return DEPENDENT_H;
        if (lv.isCommonHC()) return COMMON_HC_H;
        assert lv.isDelayed();
        return DELAYED_H;
    }

    static LV fromHighToLow(DijkstraShortestPath.DC lowDc, CausesOfDelay someDelay) {
        long l = lowDc.dist();
        if (l == Long.MAX_VALUE) return null;
        if (l < ASSIGNED_H) return LINK_STATICALLY_ASSIGNED;
        if (l < DEPENDENT_H) return LINK_ASSIGNED;
        if (l < HC_MUTABLE_H) {
            Links links = (Links) lowDc.connection();
            return LVImpl.createDependent(links);
        }
        if (l < DELAYED_H) {
            Links links = (Links) lowDc.connection();
            if (links.map().isEmpty()) {
                return null;
            }
            return LVImpl.createHC(links);
        }
        return LVImpl.delay(someDelay);
    }

    public static LV fromDistanceSumHigh(long l, CausesOfDelay someDelay) {
        if (l == Long.MAX_VALUE) return null;
        if (l < ASSIGNED_H) return LINK_STATICALLY_ASSIGNED;
        if (l < DEPENDENT_H) return LINK_ASSIGNED;
        if (l < COMMON_HC_H) return LINK_DEPENDENT;
        if (l < DELAYED_H) return LINK_COMMON_HC;
        assert someDelay != null && someDelay.isDelayed();
        return LVImpl.delay(someDelay);
    }

    private void debug(String msg, DijkstraShortestPath.DC[] l, BiFunction<Long, CausesOfDelay, LV> transform) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Variables: {}", Arrays.stream(variables).map(Objects::toString)
                    .collect(Collectors.joining(", ")));
            LOGGER.trace("{}: {}", msg, Arrays.stream(l)
                    .map(v -> transform.apply(v.dist(), someDelay))
                    .map(lv -> lv == null ? "-" : lv.toString())
                    .collect(Collectors.joining(", ")));
        }
    }

    public static String code(LV dv) {
        if (dv.isDelayed()) return "D";
        return Integer.toString(dv.value());
    }

    record Key(int start, long maxWeight) {
    }

    record LinkMap(Map<Key, LV[]> map, AtomicInteger savingsCount, String cacheKey) implements Cache.CacheElement {
        @Override
        public int savings() {
            return savingsCount.get();
        }
    }


    @Override
    public Map<Variable, LV> links(Variable v, LV maxWeight) {
        Integer startVertex = variableIndex.get(v);
        if (startVertex == null) {
            return Map.of(v, LINK_STATICALLY_ASSIGNED);
        }
        long maxWeightLong = maxWeight == null ? 0L : toDistanceComponent(maxWeight);
        Key key = new Key(startVertex, maxWeightLong);
        LV[] inMap = linkMap.map.get(key);
        LV[] shortest;
        if (inMap != null) {
            shortest = inMap;
            linkMap.savingsCount.incrementAndGet();
        } else {
            shortest = computeDijkstra(startVertex, maxWeight, maxWeightLong);
            linkMap.map.put(key, shortest);
            linkMap.savingsCount.decrementAndGet();
        }
        Map<Variable, LV> result = new HashMap<>();
        for (int j = 0; j < shortest.length; j++) {
            LV d = shortest[j];
            if (d != null) {
                result.put(variables[j], d);
            }
        }
        return result;
    }

    //      LV dv = fromDistanceSum(d, someDelay);
    private LV[] computeDijkstra(int startVertex, LV maxWeight, long maxWeightLong) {
        DijkstraShortestPath.EdgeProvider edgeProvider = i -> {
            Map<Integer, DijkstraShortestPath.DCP> edgeMap = edges.get(i);
            if (edgeMap == null) return Stream.of();
            return edgeMap.entrySet().stream()
                    .filter(e -> maxWeight == null || e.getValue().dist() <= maxWeightLong);
        };
        DijkstraShortestPath.DC[] shortestL = dijkstraShortestPath.shortestPathDC(variables.length, edgeProvider,
                l -> l == DEPENDENT, startVertex);
        debug("delay low", shortestL, ShortestPathImpl::fromDistanceSum);

        long maxWeightLongHigh = maxWeight == null ? 0L : toDistanceComponentHigh(maxWeight);
        DijkstraShortestPath.EdgeProvider edgeProviderHigh = i -> {
            Map<Integer, DijkstraShortestPath.DCP> edgeMap = edgesHigh.get(i);
            if (edgeMap == null) return Stream.of();
            return edgeMap.entrySet().stream()
                    .filter(e -> maxWeight == null || e.getValue().dist() <= maxWeightLongHigh);
        };
        DijkstraShortestPath.DC[] shortestH = dijkstraShortestPath.shortestPathDC(variables.length, edgeProviderHigh,
                l -> l == DEPENDENT_H, startVertex);
        debug("delay high", shortestH, ShortestPathImpl::fromDistanceSumHigh);

        LV[] shortest = new LV[shortestL.length];
        assert shortestL.length == shortestH.length;
        for (int i = 0; i < shortest.length; i++) {
            LV v;
            long l = shortestL[i].dist();
            if (isDelay(l)) {
                v = LVImpl.delay(someDelay);
            } else if (l == Long.MAX_VALUE) {
                v = null;
            } else {
                v = fromHighToLow(shortestH[i], someDelay);
            }
            shortest[i] = v;
        }
        return shortest;
    }

    @Override
    public Set<Variable> variables() {
        return variableIndex.keySet();
    }

    // for testing

    Variable variablesGet(int i) {
        return variables[i];
    }

    public String getCacheKey() {
        return linkMap.cacheKey;
    }
}

