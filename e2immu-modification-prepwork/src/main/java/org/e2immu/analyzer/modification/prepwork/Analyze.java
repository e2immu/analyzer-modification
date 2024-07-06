package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.support.Either;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer.NOT_YET_READ;

/*
do all the analysis of this phase

 */
public class Analyze {

    private record ReadWriteData(VariableData previous, String index,
                                 Set<Variable> read,
                                 Set<Variable> assigned) {
        public AssignmentIds assignmentIds(Variable v) {
            AssignmentIds prev = previous == null ? AssignmentIds.NOT_YET_ASSIGNED
                    : previous.variableInfo(v.fullyQualifiedName()).assignmentIds();
            if (assigned.contains(v)) {
                return new AssignmentIds(index, prev);
            }
            return prev;
        }

        public Collection<FieldReference> fieldsSeenFirstTime() {
            return Set.of();
        }

        public String isRead(Variable v) {
            return read.contains(v) ? index : previous == null ? NOT_YET_READ
                    : previous.variableInfo(v.fullyQualifiedName()).readId();
        }

        public Set<Integer> modificationIds(Variable v) {
            return Set.of();
        }

        public Collection<This> thisSeenFirstTime() {
            return Set.of();
        }
    }

    public void doMethod(MethodInfo methodInfo) {
        VariableDataImpl vdi = new VariableDataImpl();
        Statement statement = methodInfo.methodBody().statements().get(0);
        boolean hasMerge = statement.block() != null;
        ReadWriteData readWriteData = analyzeEval(null, "0", statement);
        if (methodInfo.hasReturnValue()) {
            ReturnVariable rv = new ReturnVariableImpl(methodInfo);
            vdi.put(rv, initial(rv, readWriteData, hasMerge));
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            vdi.put(pi, initial(pi, readWriteData, hasMerge));
        }
        for (FieldReference fr : readWriteData.fieldsSeenFirstTime()) {
            vdi.put(fr, initial(fr, readWriteData, hasMerge));
        }
        for (This thisVar : readWriteData.thisSeenFirstTime()) {
            vdi.put(thisVar, initial(thisVar, readWriteData, hasMerge));
        }
        if (statement.block() != null) {
            List<VariableData> lastOfEachSubBlock = doBlocks(statement, vdi);
            // make "merge" VIs for each variable
        }
        VariableData lastOfMainBlock = doBlock(methodInfo.methodBody(), vdi, null);
        methodInfo.analysis().set(VariableDataImpl.VARIABLE_DATA, lastOfMainBlock);
    }

    private List<VariableData> doBlocks(Statement parentStatement, VariableData vdOfParent) {
        return Stream.concat(Stream.of(parentStatement.block()), parentStatement.otherBlocks().stream())
                .map(block -> doBlock(block, null, vdOfParent))
                .toList();
    }

    private VariableData doBlock(Block block, VariableData vdOfFirstStatement, VariableData vdOfParent) {
        throw new UnsupportedOperationException();
    }

    private VariableInfoContainer initial(Variable rv, ReadWriteData readWriteData, boolean hasMerge) {
        VariableInfoImpl initial = new VariableInfoImpl(rv, AssignmentIds.NOT_YET_ASSIGNED, NOT_YET_READ, Set.of());
        VariableInfoImpl eval = new VariableInfoImpl(rv, readWriteData.assignmentIds(rv), readWriteData.isRead(rv),
                readWriteData.modificationIds(rv));
        return new VariableInfoContainerImpl(rv, NormalVariableNature.INSTANCE,
                Either.right(initial), eval, hasMerge);
    }

    private ReadWriteData analyzeEval(VariableData previous, String index, Statement statement) {
        Expression expression = statement.expression();
        Set<Variable> read = new HashSet<>();
        Set<Variable> assigned = new HashSet<>();

        return new ReadWriteData(previous, index, read, assigned);
    }
}
