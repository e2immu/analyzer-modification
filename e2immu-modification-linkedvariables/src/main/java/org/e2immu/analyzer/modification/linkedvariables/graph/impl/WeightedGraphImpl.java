package org.e2immu.analyzer.modification.linkedvariables.graph.impl;

import org.e2immu.analyzer.modification.linkedvariables.graph.Cache;
import org.e2immu.analyzer.modification.linkedvariables.graph.ShortestPath;
import org.e2immu.analyzer.modification.linkedvariables.graph.WeightedGraph;
import org.e2immu.analyzer.modification.prepwork.delay.CausesOfDelay;
import org.e2immu.analyzer.modification.prepwork.variable.LV;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.support.Freezable;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;
import org.jgrapht.alg.util.UnionFind;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.LINK_INDEPENDENT;
import static org.e2immu.analyzer.modification.linkedvariables.lv.LVImpl.LINK_STATICALLY_ASSIGNED;

public class WeightedGraphImpl extends Freezable implements WeightedGraph {

    private final Map<Variable, Node> nodeMap;
    private final Cache cache;

    // for testing only!
    public WeightedGraphImpl() {
        this(new GraphCacheImpl(10));
    }

    public WeightedGraphImpl(Cache cache) {
        nodeMap = new LinkedHashMap<>();
        this.cache = cache;
    }

    private static class Node {
        Map<Variable, LV> dependsOn;
        final Variable variable;

        private Node(Variable v) {
            variable = v;
        }
    }

    /*
    https://en.wikipedia.org/wiki/Disjoint-set_data_structure
    loop over all vertices and edges, and use a disjoint-set data structure to efficiently group or cluster all
    nodes linked by STATICALLY_ASSIGNED edges.

    The return-value cluster is a special case, it overlaps with the other ones.
     */
    @Override
    public WeightedGraph.ClusterResult staticClusters() {
        super.freeze();
        UnionFind<Variable> unionFind = new UnionFind<>(nodeMap.keySet());
        Variable rv = null;
        Set<Variable> dependsOnRv = null;
        for (Map.Entry<Variable, Node> entry : nodeMap.entrySet()) {
            Variable variable = entry.getKey();
            boolean isRv = variable instanceof ReturnVariable;
            if (isRv) {
                rv = variable;
                dependsOnRv = new HashSet<>();
            }
            Map<Variable, LV> dependsOn = entry.getValue().dependsOn;
            if (dependsOn != null) {
                for (Map.Entry<Variable, LV> e2 : dependsOn.entrySet()) {
                    if (LINK_STATICALLY_ASSIGNED.equals(e2.getValue())) {
                        if (isRv) {
                            dependsOnRv.add(e2.getKey());
                        } else {
                            unionFind.union(variable, e2.getKey());
                        }
                    }
                }
            }
        }
        Map<Variable, Cluster> representativeToCluster = new LinkedHashMap<>();
        for (Variable variable : nodeMap.keySet()) {
            if (!(variable instanceof ReturnVariable)) {
                Variable representative = unionFind.find(variable);
                Cluster cluster = representativeToCluster.computeIfAbsent(representative,
                        v -> new Cluster(new LinkedHashSet<>()));
                cluster.variables().add(variable);
            }
        }
        List<Cluster> clusters = representativeToCluster.values().stream().toList();
        Cluster rvCluster;
        if (rv != null) {
            rvCluster = new Cluster(new LinkedHashSet<>());
            rvCluster.variables().add(rv);
            for (Variable v : dependsOnRv) {
                Variable r = unionFind.find(v);
                Cluster c = representativeToCluster.get(r);
                rvCluster.variables().addAll(c.variables());
            }
        } else {
            rvCluster = null;
        }
        return new WeightedGraph.ClusterResult(rvCluster, rv, clusters);
    }

    public int size() {
        return nodeMap.size();
    }

    public boolean isEmpty() {
        return nodeMap.isEmpty();
    }

    public void visit(BiConsumer<Variable, Map<Variable, LV>> consumer) {
        nodeMap.values().forEach(n -> consumer.accept(n.variable, n.dependsOn));
    }

    private Node getOrCreate(Variable v) {
        ensureNotFrozen();
        Objects.requireNonNull(v);
        return nodeMap.computeIfAbsent(v, Node::new);
    }

    public void addNode(Variable v, Map<Variable, LV> dependsOn) {
        ensureNotFrozen();
        Node node = getOrCreate(v);
        for (Map.Entry<Variable, LV> e : dependsOn.entrySet()) {
            if (node.dependsOn == null) {
                node.dependsOn = new LinkedHashMap<>();
            }
            Variable target = e.getKey();
            LV linkLevel = e.getValue();
            assert !LINK_INDEPENDENT.equals(linkLevel);
            assert !(target instanceof ReturnVariable);
            /*
             Links from the return variable are asymmetrical
             */
            LV min = node.dependsOn.merge(target, linkLevel, LV::min);
            if (!(v instanceof ReturnVariable)) {
                Node n = getOrCreate(target);
                if (n.dependsOn == null) {
                    n.dependsOn = new LinkedHashMap<>();
                }
                n.dependsOn.merge(v, min.reverse(), LV::min);
            }
        }
    }

    static Comparator<String> REVERSE_STRING_COMPARATOR = (s1, s2) -> {
        int i1 = s1.length() - 1;
        int i2 = s2.length() - 1;
        while (i1 >= 0 && i2 >= 0) {
            int c = Character.compare(s1.charAt(i1), s2.charAt(i2));
            if (c != 0) return c;
            --i1;
            --i2;
        }
        if (i1 >= 0) return 1;
        if (i2 >= 0) return -1;
        return 0;
    };

    static Comparator<Variable> REVERSE_FQN_COMPARATOR = (v1, v2) ->
            REVERSE_STRING_COMPARATOR.compare(v1.fullyQualifiedName(), v2.fullyQualifiedName());

    @Override
    public ShortestPath shortestPath() {
        int n = nodeMap.size();
        Variable[] variables = new Variable[n];
        // -- CACHE --
        int j = 0;
        for (Variable v : nodeMap.keySet()) {
            variables[j++] = v;
        }
        // we need a stable order across the variables; given the huge prefixes of parameters and fields,
        // it seems a lot faster to sort starting from the back.
        Arrays.sort(variables, REVERSE_FQN_COMPARATOR); // default: by name
        // -- CACHE --
        Map<Variable, Integer> variableIndex = new LinkedHashMap<>();
        int i = 0;
        for (Variable v : variables) {
            variableIndex.put(v, i);
            ++i;
        }
        StringBuilder sb = new StringBuilder(n * n * 5);
        Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edges = new LinkedHashMap<>();
        Map<Integer, Map<Integer, DijkstraShortestPath.DCP>> edgesHigh = new LinkedHashMap<>();
        //CausesOfDelay delay = null;
        for (int d1 = 0; d1 < n; d1++) {
            Node node = nodeMap.get(variables[d1]);
            Map<Variable, LV> dependsOn = node.dependsOn;
            sb.append(d1);
            if (dependsOn != null && !dependsOn.isEmpty()) {

                Map<Integer, DijkstraShortestPath.DCP> edgesOfD1 = new LinkedHashMap<>();
                edges.put(d1, edgesOfD1);
                Map<Integer, DijkstraShortestPath.DCP> edgesOfD1High = new LinkedHashMap<>();
                edgesHigh.put(d1, edgesOfD1High);
                List<String> unsorted = new ArrayList<>(dependsOn.size());
                for (Map.Entry<Variable, LV> e2 : dependsOn.entrySet()) {
                    Integer d2 = variableIndex.get(e2.getKey());
                    assert d2 != null : "Variable " + e2.getKey() + " is not in " + Arrays.toString(variables);
                    LV lv = e2.getValue();
                    //if (lv.isDelayed() && delay == null) {
                    //    delay = lv.causesOfDelay();
                    //
                    long d = ShortestPathImpl.toDistanceComponent(lv);
                    edgesOfD1.put(d2, new DijkstraShortestPath.DCP(d, lv.links()));
                    long dHigh = ShortestPathImpl.toDistanceComponentHigh(lv);
                    edgesOfD1High.put(d2, new DijkstraShortestPath.DCP(dHigh, lv.links()));

                    String cacheCode = lv.isDelayed() ? "D" : lv.minimal();
                    unsorted.add(d2 + ":" + cacheCode);
                }
                sb.append("(");
                sb.append(unsorted.stream().sorted().collect(Collectors.joining(";")));
                sb.append(")");
            } else {
                sb.append("/");
            }
        }
        String cacheKey = sb.toString();
        Cache.Hash hash = cache.createHash(cacheKey);
        ShortestPathImpl.LinkMap linkMap = (ShortestPathImpl.LinkMap)
                cache.computeIfAbsent(hash, h -> new ShortestPathImpl.LinkMap(new LinkedHashMap<>(), new AtomicInteger(), cacheKey));
        return new ShortestPathImpl(variableIndex, variables, edges, edgesHigh, CausesOfDelay.DELAY, linkMap);
    }
}