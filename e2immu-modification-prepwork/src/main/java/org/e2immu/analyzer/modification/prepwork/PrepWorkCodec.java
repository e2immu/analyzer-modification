package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.impl.analysis.PropertyProviderImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.language.cst.io.CodecImpl;

import java.util.Map;
import java.util.function.BiFunction;

import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_METHOD;
import static org.e2immu.analyzer.modification.prepwork.hcs.HiddenContentSelector.HCS_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

public class PrepWorkCodec {

    private final Codec.TypeProvider typeProvider;
    private final Codec.DecoderProvider decoderProvider;
    private final Codec.PropertyProvider propertyProvider;
    private final Runtime runtime;

    public PrepWorkCodec(Runtime runtime) {
        this.typeProvider = fqn -> runtime.getFullyQualified(fqn, true);
        decoderProvider = new D();
        this.propertyProvider = new P();
        this.runtime = runtime;
    }

    public Codec codec() {
        return new C(runtime);
    }

    class C extends CodecImpl {
        public C(Runtime runtime) {
            super(runtime, propertyProvider, decoderProvider, typeProvider);
        }
    }

    private static final Map<String, Property> PROPERTY_MAP = Map.of(
            HIDDEN_CONTENT_TYPES.key(), HIDDEN_CONTENT_TYPES,
            HCS_METHOD.key(), HCS_METHOD,
            HCS_PARAMETER.key(), HCS_PARAMETER,
            PART_OF_CONSTRUCTION.key(), PART_OF_CONSTRUCTION);

    class P implements Codec.PropertyProvider {
        @Override
        public Property get(String propertyName) {
            Property inMap = PROPERTY_MAP.get(propertyName);
            if (inMap != null) return inMap;
            return PropertyProviderImpl.get(propertyName);
        }
    }

    class D implements Codec.DecoderProvider {

        @Override
        public BiFunction<Codec.DI, Codec.EncodedValue, Value> decoder(Class<? extends Value> clazz) {
            if (HiddenContentTypes.class.equals(clazz)) {
                return (di, ev) -> HiddenContentTypes.decode(di.codec(), di.context(), ev);
            }
            if (HiddenContentSelector.class.equals(clazz)) {
                return (di, ev) -> HiddenContentSelector.decode(di.codec(), di.context(), ev);
            }
            // part of construction uses "set of info", which is in ValueImpl.
            return ValueImpl.decoder(clazz);
        }
    }

}

