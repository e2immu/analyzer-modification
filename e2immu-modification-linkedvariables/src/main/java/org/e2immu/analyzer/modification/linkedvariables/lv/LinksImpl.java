package org.e2immu.analyzer.modification.linkedvariables.lv;


import org.e2immu.analyzer.modification.prepwork.hcs.IndexImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Link;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.util.internal.graph.op.DijkstraShortestPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.e2immu.analyzer.modification.prepwork.hcs.IndexImpl.ALL;
import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.ALL_INDICES;
import static org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl.NO_MODIFICATION_INDICES;

/*
Modification area of a modified variable is copied into MODIFIED_COMPONENTS_VARIABLE/_PARAMETER, in terms of actual field references.
We use ALL_INDICES as the marker "everything" (on a -2- link) and "nothing" (on a -4- link).

The modification area will be used here to block certain edges in the linked variables graph from being added.
When R has components A, B, C, with links
    A *-2-0 R   mod area *, 0
    B *-2-1 R   mod area *, 1
    C *-2-0 R   mod area *, 2
    D  -2-  R   mod area *, 3

the only link that can be generated is 0-4-0 between A and C: A and C (may) share hidden content of type 0.
The important consequence is that a modification in A does imply a modification in R, but not in B, C or D.
 */

public record LinksImpl(Map<Indices, Link> map, Indices modificationAreaSource,
                        Indices modificationAreaTarget) implements Links {
    // link info on -0-, -1-, -2- without extra link info. -4- must have hidden content links,
    // and its modification areas must be NO_MODIFICATION_INDICES
    public static final Links NO_LINKS = new LinksImpl(Map.of(), ALL_INDICES, ALL_INDICES);

    public LinksImpl(int from, int to, boolean isHc) {
        this(Map.of(from == ALL ? ALL_INDICES : new IndicesImpl(Set.of(new IndexImpl(List.of(from)))),
                new LinkImpl(new IndicesImpl(Set.of(new IndexImpl(List.of(to)))), false)),
                isHc ? NO_MODIFICATION_INDICES: ALL_INDICES, isHc ? NO_MODIFICATION_INDICES: ALL_INDICES);
    }

    public LinksImpl(Map<Indices, Link> map) {
        this(map, ALL_INDICES, ALL_INDICES);
    }

    @Override
    public Links ensureNoModification() {
        return new LinksImpl(map, NO_MODIFICATION_INDICES, NO_MODIFICATION_INDICES);
    }

    /*
            @Override
            public DijkstraShortestPath.Accept next(DijkstraShortestPath.Connection current, boolean allowNoConnection, boolean keepNoLinks) {
                if (current == NO_LINKS || this == NO_LINKS && keepNoLinks) {
                    // good for -2- and -D-, but not for -0-, -1-
                    // sadly enough, we do not have that info here
                    return new DijkstraShortestPath.Accept(true, this);
                }
                boolean returnNull = true;
                int conflicted = 0;
                Map<Indices, Link> res = new HashMap<>();

                LinksImpl currentLink = (LinksImpl) current;
                for (Map.Entry<Indices, Link> entry : currentLink.map.entrySet()) {
                    Indices middle = entry.getValue().to();
                    Link link = this.map.get(middle);
                    if (link != null) {
                        boolean fromAllToAll = entry.getKey().isAll() && link.to().isAll();
                        if (fromAllToAll) {
                            boolean intersect = modificationAreaTarget.intersectionNonEmpty(currentLink.modificationAreaSource());
                            if (intersect) {
                                // accept the link
                                res.merge(entry.getKey(), link, Link::merge);
                            } else {
                                // block the link
                                conflicted++;
                            }
                        } else {
                            boolean middleIsAll = middle.isAll();
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
                        } else if (!this.map.isEmpty()) { // FIXME this condition may have to be more strict
                            ++conflicted;
                        }
                    }
                }
                assert conflicted <= 1;
                if (res.isEmpty()) {
                    if (allowNoConnection && conflicted == 0) return new DijkstraShortestPath.Accept(returnNull, null);
                    return new DijkstraShortestPath.Accept(false, null);
                }
                LinksImpl next = new LinksImpl(res, modificationAreaSource, currentLink.modificationAreaTarget);
                return new DijkstraShortestPath.Accept(true, next);
            }*/
     /*
      this method, together with allowModified(), is key to the whole linking + modification process
      */
    @Override
    public DijkstraShortestPath.Accept next(DijkstraShortestPath.Connection current, boolean keepNoLinks) {
        if (current == NO_LINKS || this == NO_LINKS && keepNoLinks) {
            // good for -2- and -D-, but not for -0-, -1-
            // sadly enough, we do not have that info here
            return new DijkstraShortestPath.Accept(true, this);
        }
        boolean conflicted = false;
        LinksImpl currentLink = (LinksImpl) current;

        Map<Indices, Link> res = new HashMap<>();
        for (Map.Entry<Indices, Link> entry : currentLink.map.entrySet()) {
            Indices middle = entry.getValue().to();
            Link link = this.map.get(middle);
            if (link != null) {
                boolean fromAllToAll = entry.getKey().equals(ALL_INDICES) && link.to().equals(ALL_INDICES);
                if (fromAllToAll) {
                    boolean intersect = modificationAreaSource.intersectionNonEmpty(currentLink.modificationAreaTarget());
                    conflicted |= !intersect;
                } else {
                    boolean middleIsAll = middle.isAll();
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
                } else{
                    boolean intersect = modificationAreaSource.intersectionNonEmpty(currentLink.modificationAreaTarget());
                    if (!intersect) {
                        // block the link
                        conflicted = true;
                    }
                }
            }
        }
        if (res.isEmpty()) {
            boolean allowNoConnection = !modificationAreaSource.isNoModification()
                                        && !currentLink.modificationAreaTarget.isNoModification();
            boolean accept = allowNoConnection && !conflicted;
            return new DijkstraShortestPath.Accept(accept, null);
        }
        LinksImpl next = new LinksImpl(res, modificationAreaSource, currentLink.modificationAreaTarget);
        return new DijkstraShortestPath.Accept(true, next);
    }


    @Override
    public DijkstraShortestPath.Connection merge(DijkstraShortestPath.Connection connection) {
        Links other = (Links) connection;
        Map<Indices, Link> res = new HashMap<>(map);
        other.map().forEach((k, v) -> res.merge(k, v, Link::merge));
        return new LinksImpl(res, modificationAreaSource.merge(other.modificationAreaSource()),
                modificationAreaTarget.merge(other.modificationAreaTarget()));
    }

    public Links theirsToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map().get(thisTo.to());
            assert link != null;
            res.put(thisTo.to(), link);
        });
        return new LinksImpl(res, modificationAreaTarget, links.modificationAreaTarget());
    }

    // use thisTo.to as the intermediary
    public Links mineToTheirs(Links links) {
        Map<Indices, Link> res = new HashMap<>();
        map.forEach((thisFrom, thisTo) -> {
            Link link = links.map().get(thisTo.to());
            assert link != null;
            res.put(thisFrom, link);
        });
        return new LinksImpl(res, modificationAreaSource, links.modificationAreaTarget());
    }

    public Links reverse() {
        if (map.isEmpty()) return this;
        Map<Indices, Link> map = new HashMap<>();
        for (Map.Entry<Indices, Link> e : this.map.entrySet()) {
            map.put(e.getValue().to(), new LinkImpl(e.getKey(), e.getValue().mutable()));
        }
        return new LinksImpl(Map.copyOf(map), modificationAreaTarget, modificationAreaSource);
    }
}