package org.e2immu.analyzer.modification.linkedvariables.lv;


import org.e2immu.analyzer.modification.linkedvariables.hcs.Index;
import org.e2immu.analyzer.modification.linkedvariables.hcs.Indices;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.Index.ALL;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.Indices.ALL_INDICES;

public record Links(Map<Indices, Link> map) implements DijkstraShortestPath.Connection {
    public static final Links NO_LINKS = new Links(Map.of());

    public Links(int from, int to) {
        this(Map.of(from == ALL ? ALL_INDICES : new Indices(Set.of(new Index(List.of(from)))),
                new Link(new Indices(Set.of(new Index(List.of(to)))), false)));
    }

    /*
    this method, together with allowModified(), is key to the whole linking + modification process of
    ComputeLinkedVariables.
     */
    @Override
    public Links next(DijkstraShortestPath.Connection current) {
        if (current == NO_LINKS || this == NO_LINKS) {
            return this;
        }
        Map<Indices, Link> res = new HashMap<>();
        for (Map.Entry<Indices, Link> entry : ((Links) current).map.entrySet()) {
            Indices middle = entry.getValue().to();
            Link link = this.map.get(middle);
            if (link != null) {
                boolean fromAllToAll = entry.getKey().equals(ALL_INDICES) && link.to().equals(ALL_INDICES);
                if (!fromAllToAll) {
                    boolean middleIsAll = middle.equals(ALL_INDICES);
                    boolean mutable = !middleIsAll && entry.getValue().mutable() && link.mutable();
                    Link newLink = mutable == link.mutable() ? link : new Link(link.to(), false);
                    res.merge(entry.getKey(), newLink, Link::merge);
                }
            } else {
                Link allLink = this.map.get(ALL_INDICES);
                if (allLink != null) {
                    // start again from *
                    boolean mutable = entry.getValue().mutable() || allLink.mutable();
                    Link newLink = mutable == allLink.mutable() ? allLink : new Link(allLink.to(), true);
                    res.merge(entry.getKey(), newLink, Link::merge);
                }
            }
        }
        if (res.isEmpty()) return null;
        return new Links(res);
    }

    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        Links other = (Links) connection;
        Map<Indices, Link> res = new HashMap<>(map);
        other.map.forEach((k, v) -> res.merge(k, v, Link::merge));
        return new Links(res);
    }

    public Links theirsToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map.get(thisTo.to());
            assert link != null;
            res.put(thisTo.to(), link);
        });
        return new Links(res);
    }

    // use thisTo.to as the intermediary
    public Links mineToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map.get(thisTo.to());
            assert link != null;
            res.put(thisFrom, link);
        });
        return new Links(res);
    }

    public Links reverse() {
        if (map.isEmpty()) return this;
        Map<Indices, Link> map = new HashMap<>();
        for (Map.Entry<Indices, Link> e : this.map.entrySet()) {
            map.put(e.getValue().to(), new Link(e.getKey(), e.getValue().mutable()));
        }
        return new Links(Map.copyOf(map));
    }
}