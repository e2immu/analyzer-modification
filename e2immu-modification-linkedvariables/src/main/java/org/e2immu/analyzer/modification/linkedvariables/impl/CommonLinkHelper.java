package org.e2immu.analyzer.modification.linkedvariables.impl;

import org.e2immu.analyzer.modification.common.AnalysisHelper;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkImpl;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinksImpl;
import org.e2immu.analyzer.modification.prepwork.hcs.IndicesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.Indices;
import org.e2immu.analyzer.modification.prepwork.variable.Link;
import org.e2immu.analyzer.modification.prepwork.variable.Links;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.inspection.api.parser.GenericsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;

public abstract class CommonLinkHelper {

    protected final TypeInfo currentPrimaryType;
    protected final Runtime runtime;
    protected final GenericsHelper genericsHelper;
    protected final AnalysisHelper analysisHelper = new AnalysisHelper();

    protected CommonLinkHelper(TypeInfo currentPrimaryType, Runtime runtime, AnalysisHelper analysisHelper, GenericsHelper genericsHelper) {
        this.currentPrimaryType = currentPrimaryType;
        this.runtime = runtime;
        this.genericsHelper = genericsHelper;
    }

    ParameterizedType ensureTypeParameters(ParameterizedType pt) {
        if (!pt.parameters().isEmpty() || pt.typeInfo() == null) return pt;
        ParameterizedType formal = pt.typeInfo().asParameterizedType();
        List<ParameterizedType> parameters = new ArrayList<>();
        for (int i = 0; i < formal.parameters().size(); ++i) parameters.add(runtime.objectParameterizedType());
        return pt.withParameters(List.copyOf(parameters));
    }

}
