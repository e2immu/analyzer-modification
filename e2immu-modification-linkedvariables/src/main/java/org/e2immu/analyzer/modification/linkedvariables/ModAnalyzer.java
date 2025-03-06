package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Block;
import org.e2immu.language.cst.api.statement.Statement;

import java.util.List;
import java.util.Map;

public interface ModAnalyzer {
    void doMethod(MethodInfo methodInfo);

    VariableData doBlock(MethodInfo methodInfo,
                         Block block,
                         VariableData vdOfParent);

    VariableData doStatement(MethodInfo methodInfo,
                             Statement statement,
                             VariableData previous,
                             boolean first);

    /*
                the information flow is fixed, because it is a single pass system:

                - analyze the method's statements, and copy what is possible to the parameters and the method
                - analyze the fields and their initializers
                - copy from fields to parameters where relevant

                 */
    void doPrimaryType(TypeInfo primaryType, List<Info> analysisOrder);

    Map<String, Integer> getHistogram();

    List<Throwable> getProblemsRaised();

    void go(List<Info> analysisOrder);
}
