package org.e2immu.analyzer.modification.linkedvariables.lv;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Links;

import java.util.Map;

public class DirectAccessLinks extends SingleLinks {

    public DirectAccessLinks(Map<Integer, FromToMutable> hcMethodToFromToMutableMap,
                             HiddenContentTypes hctFrom,
                             HiddenContentTypes hctTo,
                             Indices modificationAreaSource,
                             Indices modificationAreaTarget) {
        super(hcMethodToFromToMutableMap, hctFrom, hctTo, modificationAreaSource, modificationAreaTarget);
        // TODO the "all" type of hctFrom must be one of the hidden content types in hctTo.
    }

    @Override
    public Links ensureNoModification() {
        return null;
    }

    @Override
    public HiddenContentSelector hcsMethod() {
        return null;
    }

    @Override
    public String toString(int hc) {
        return "";
    }
}
