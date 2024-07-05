package org.e2immu.analyzer.modification.linkedvariables.lv;


import org.e2immu.analyzer.modification.linkedvariables.hcs.Indices;


import java.util.Map;



public record Link(Indices to, boolean mutable) {
    public Link correctTo(Map<Indices, Indices> correctionMap) {
        return new Link(correctionMap.getOrDefault(to, to), mutable);
    }

    public Link merge(Link l2) {
        return new Link(to.merge(l2.to), mutable || l2.mutable);
    }

    public Link prefixTheirs(int index) {
        return new Link(to.prefix(index), mutable);
    }
}
