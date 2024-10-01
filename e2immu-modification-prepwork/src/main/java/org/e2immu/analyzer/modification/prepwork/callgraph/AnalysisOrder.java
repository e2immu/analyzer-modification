package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;

import java.util.List;

/*
Order in which methods, fields, types are to be analyzed.
Computation based on call graph.

List, with extra information, stored in InfoAndDetails.

ignoreMePartOfCallCycle = the method that was used to break the cycle.
 */
public class AnalysisOrder implements Value {
    public static final AnalysisOrder EMPTY_ORDER = new AnalysisOrder();
    public static final Property ANALYSIS_ORDER = new PropertyImpl("analysisOrderPrimaryType", EMPTY_ORDER);

    public record InfoAndDetails(Info info,
                                 boolean ignoreMePartOfCallCycle,
                                 boolean partOfConstruction,
                                 boolean recursive) {
        @Override
        public String toString() {
            return info.fullyQualifiedName() + ":" + tf(ignoreMePartOfCallCycle)
                   + tf(partOfConstruction) + tf(recursive);
        }

        private static String tf(boolean b) {
            return b ? "T" : "F";
        }
    }

    private final List<InfoAndDetails> list;

    private AnalysisOrder() {
        this(List.of());
    }

    AnalysisOrder(List<InfoAndDetails> list) {
        this.list = list;
    }

    public List<InfoAndDetails> infoOrder() {
        return list;
    }

    @Override
    public String toString() {
        return list.toString();
    }

    @Override
    public Codec.EncodedValue encode(Codec codec) {
        List<Codec.EncodedValue> encodedList = list.stream().map(iad -> {
            Codec.EncodedValue encodedInfo = codec.encodeInfo(iad.info);
            Codec.EncodedValue ignoreMe = codec.encodeBoolean(iad.ignoreMePartOfCallCycle);
            Codec.EncodedValue partOfC = codec.encodeBoolean(iad.partOfConstruction);
            Codec.EncodedValue recursive = codec.encodeBoolean(iad.recursive);
            return codec.encodeList(List.of(encodedInfo, ignoreMe, partOfC, recursive));
        }).toList();
        return codec.encodeList(encodedList);
    }

}
