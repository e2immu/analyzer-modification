package org.e2immu.analyzer.modification.linkedvariables.lv;


import org.e2immu.analyzer.modification.linkedvariables.hcs.IndexImpl;
import org.e2immu.analyzer.modification.linkedvariables.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Link;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.e2immu.analyzer.modification.linkedvariables.hcs.IndexImpl.ALL;
import static org.e2immu.analyzer.modification.linkedvariables.hcs.IndicesImpl.ALL_INDICES;


public record LinksImpl(Map<Indices, Link> map) implements Links {
    public static final Links NO_LINKS = new LinksImpl(Map.of());

    public LinksImpl(int from, int to) {
        this(Map.of(from == ALL ? ALL_INDICES : new IndicesImpl(Set.of(new IndexImpl(List.of(from)))),
                new LinkImpl(new IndicesImpl(Set.of(new IndexImpl(List.of(to)))), false)));
    }

    /*
    this method, together with allowModified(), is key to the whole linking + modification process of
    ComputeLinkedVariables.
     */
    @Override
    public Links next(DijkstraShortestPath.Connection current, boolean keepNoLinks) {
        if (current == NO_LINKS) {
            return this;
        }
        if (this == NO_LINKS && keepNoLinks) {
            // good for -2- and -D-, but not for -0-, -1-
            // sadly enough, we do not have that info here
            return this;
        }
        Map<Indices, Link> res = new HashMap<>();
        for (Map.Entry<Indices, Link> entry : ((LinksImpl) current).map.entrySet()) {
            Indices middle = entry.getValue().to();
            Link link = this.map.get(middle);
            if (link != null) {
                boolean fromAllToAll = entry.getKey().equals(ALL_INDICES) && link.to().equals(ALL_INDICES);
                if (!fromAllToAll) {
                    boolean middleIsAll = middle.equals(ALL_INDICES);
                    boolean mutable = !middleIsAll && entry.getValue().mutable() && link.mutable();
                    Link newLink = mutable == link.mutable() ? link : new LinkImpl(link.to(), false);
                    res.merge(entry.getKey(), newLink, Link::merge);
                }
            } else {
                Link allLink = this.map.get(ALL_INDICES);
                if (allLink != null) {
                    // start again from *
                    boolean mutable = entry.getValue().mutable() || allLink.mutable();
                    Link newLink = mutable == allLink.mutable() ? allLink : new LinkImpl(allLink.to(), true);
                    res.merge(entry.getKey(), newLink, Link::merge);
                }
            }
        }
        if (res.isEmpty()) return null;
        return new LinksImpl(res);
    }

    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        Links other = (Links) connection;
        Map<Indices, Link> res = new HashMap<>(map);
        other.map().forEach((k, v) -> res.merge(k, v, Link::merge));
        return new LinksImpl(res);
    }

    public Links theirsToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map().get(thisTo.to());
            assert link != null;
            res.put(thisTo.to(), link);
        });
        return new LinksImpl(res);
    }

    // use thisTo.to as the intermediary
    public Links mineToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map().get(thisTo.to());
            assert link != null;
            res.put(thisFrom, link);
        });
        return new LinksImpl(res);
    }

    public Links reverse() {
        if (map.isEmpty()) return this;
        Map<Indices, Link> map = new HashMap<>();
        for (Map.Entry<Indices, Link> e : this.map.entrySet()) {
            map.put(e.getValue().to(), new LinkImpl(e.getKey(), e.getValue().mutable()));
        }
        return new LinksImpl(Map.copyOf(map));
    }
}