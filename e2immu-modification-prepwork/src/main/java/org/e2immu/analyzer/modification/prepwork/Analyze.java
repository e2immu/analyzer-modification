package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.InstanceOf;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.support.Either;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer.NOT_YET_READ;

/*
do all the analysis of this phase

 */
public class Analyze {
    public static final String FIRST = "0";
    private final Runtime runtime;

    public Analyze(Runtime runtime) {
        this.runtime = runtime;
    }

    private record ReadWriteData(VariableData previous,
                                 String index,
                                 Set<Variable> seenFirstTime,
                                 Set<Variable> read,
                                 Set<Variable> assigned) {
        public Assignments assignmentIds(Variable v, Stage stage) {
            boolean isAssigned = assigned.contains(v);
            if (previous == null || !previous.isKnown(v.fullyQualifiedName())) {
                String indexOfDefinition = v instanceof LocalVariable ? index : "-";
                return new Assignments(indexOfDefinition, isAssigned);
            } else {
                Assignments prev = previous.variableInfo(v, stage).assignments();
                if (isAssigned) {
                    return Assignments.newAssignment(index, prev);
                }
                return prev;
            }
        }

        public String isRead(Variable v, Stage stage) {
            return read.contains(v) ? index : previous == null || !previous.isKnown(v.fullyQualifiedName())
                    ? NOT_YET_READ
                    : previous.variableInfo(v, stage).readId();
        }

        public Set<Variable> variablesSeenFirstTime() {
            return seenFirstTime;
        }
    }

    public void doMethod(MethodInfo methodInfo) {
        // build up the initial variableInfo in the first statement
        VariableDataImpl vdi = new VariableDataImpl();
        Statement statement = methodInfo.methodBody().statements().get(0);
        boolean hasMerge = statement.hasSubBlocks();
        ReturnVariable rv = methodInfo.hasReturnValue() ? new ReturnVariableImpl(methodInfo) : null;
        ReadWriteData readWriteData = analyzeEval(null, FIRST, statement, rv);
        if (methodInfo.hasReturnValue()) {
            assert rv != null;
            vdi.put(rv, initial(FIRST, rv, readWriteData, hasMerge));
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            vdi.put(pi, initial(FIRST, pi, readWriteData, hasMerge));
        }
        for (Variable v : readWriteData.variablesSeenFirstTime()) {
            vdi.putIfAbsent(v, initial(FIRST, v, readWriteData, hasMerge));
        }
        This thisVar = methodInfo.isStatic() ? null : runtime.newThis(methodInfo.typeInfo());
        if (thisVar != null) {
            vdi.putIfAbsent(thisVar, initial(FIRST, thisVar, readWriteData, hasMerge));
        }
        statement.analysis().set(VariableDataImpl.VARIABLE_DATA, vdi);
        if (statement.hasSubBlocks()) {
            List<VariableData> lastOfEachSubBlock = doBlocks(statement, vdi, rv);
            addMerge(FIRST, vdi, lastOfEachSubBlock);
        }
        // start analysis, and copy results of last statement into method
        VariableData lastOfMainBlock = doBlock(methodInfo.methodBody(), vdi, null, rv);
        methodInfo.analysis().set(VariableDataImpl.VARIABLE_DATA, lastOfMainBlock);
    }

    private List<VariableData> doBlocks(Statement parentStatement, VariableData vdOfParent, ReturnVariable rv) {
        return parentStatement.subBlockStream()
                .filter(b -> !b.isEmpty())
                .map(block -> doBlock(block, null, vdOfParent, rv))
                .toList();
    }

    private VariableData doBlock(Block block,
                                 VariableData vdOfFirstStatement,
                                 VariableData vdOfParent,
                                 ReturnVariable rv) {

        VariableData previous = vdOfFirstStatement != null ? vdOfFirstStatement : vdOfParent;
        int start = vdOfFirstStatement != null ? 1 : 0;
        for (int i = start; i < block.statements().size(); i++) {
            Statement statement = block.statements().get(i);
            String index = statement.source().index();
            ReadWriteData readWriteData = analyzeEval(previous, index, statement, rv);
            VariableDataImpl vdi = new VariableDataImpl();
            boolean hasMerge = statement.hasSubBlocks();
            Stage stage = i == 0 ? Stage.EVALUATION : Stage.MERGE;
            previous.variableInfoContainerStream().forEach(vic -> {
                VariableInfo vi = vic.best(stage);
                Variable variable = vi.variable();
                VariableInfoImpl eval = new VariableInfoImpl(variable, readWriteData.assignmentIds(variable, stage),
                        readWriteData.isRead(variable, stage));
                VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vic.variableNature(),
                        Either.left(vic), eval, hasMerge);
                vdi.put(variable, newVic);
            });

            for (Variable v : readWriteData.variablesSeenFirstTime()) {
                vdi.put(v, initial(index, v, readWriteData, hasMerge));
            }

            // sub-blocks
            if (statement.hasSubBlocks()) {
                List<VariableData> lastOfEachSubBlock = doBlocks(statement, vdi, rv);
                addMerge(index, vdi, lastOfEachSubBlock);
            }

            statement.analysis().set(VariableDataImpl.VARIABLE_DATA, vdi);
            previous = vdi;
        }
        return previous;
    }

    /*
    Variables can be local to a block, in which case they are NOT transferred to the merge.
    Fields occurring in one of the blocks are kept in the merge.
    Scopes of fields that and array variables may survive in the following conditions: TODO
    Pattern variables may survive, if they occurred in a "negative" expression: "if(!(e instanceof X x)) { throw ... }"

    Some variables already exist, but are not referenced in any of the blocks at all.
     */
    private void addMerge(String index, VariableDataImpl vdStatement, List<VariableData> lastOfEachSubBlock) {
        Map<Variable, List<VariableInfo>> map = new HashMap<>();
        for (VariableData vd : lastOfEachSubBlock) {
            vd.variableInfoStream().forEach(vi -> {
                if (copyToMerge(index, vi)) {
                    map.computeIfAbsent(vi.variable(), v -> new ArrayList<>()).add(vi);
                }
            });
        }
        map.forEach((v, vis) -> {
            List<Assignments> assignmentsPerBlock = vis.stream().map(VariableInfo::assignments).toList();
            Assignments assignments = Assignments.mergeBlocks(index, assignmentsPerBlock);
            String readId = vis.stream().map(VariableInfo::readId).reduce(NOT_YET_READ,
                    (s1, s2) -> s1.compareTo(s2) <= 0 ? s2 : s1);
            VariableInfoImpl merge = new VariableInfoImpl(v, assignments, readId);
            VariableInfoContainer inMap = vdStatement.variableInfoContainerOrNull(v.fullyQualifiedName());
            VariableInfoContainerImpl vici;
            if (inMap == null) {
                String indexOfDefinition = v instanceof LocalVariable ? index : "-";
                Assignments notYetAssigned = new Assignments(indexOfDefinition, false);
                VariableInfoImpl initial = new VariableInfoImpl(v, notYetAssigned, NOT_YET_READ);
                vici = new VariableInfoContainerImpl(v, NormalVariableNature.INSTANCE, Either.right(initial), initial,
                        true);
                vdStatement.put(v, vici);
            } else {
                vici = (VariableInfoContainerImpl) inMap;
            }
            vici.setMerge(merge);
        });
    }

    private boolean copyToMerge(String index, VariableInfo vi) {
        return !(vi.variable() instanceof LocalVariable) || index.compareTo(vi.assignments().indexOfDefinition()) >= 0;
    }

    private VariableInfoContainer initial(String index, Variable v, ReadWriteData readWriteData, boolean hasMerge) {
        String indexOfDefinition = v instanceof LocalVariable ? index : "-";
        Assignments notYetAssigned = new Assignments(indexOfDefinition, false);
        VariableInfoImpl initial = new VariableInfoImpl(v, notYetAssigned, NOT_YET_READ);
        VariableInfoImpl eval = new VariableInfoImpl(v, readWriteData.assignmentIds(v, Stage.MERGE),
                readWriteData.isRead(v, Stage.MERGE));
        return new VariableInfoContainerImpl(v, NormalVariableNature.INSTANCE,
                Either.right(initial), eval, hasMerge);
    }

    private ReadWriteData analyzeEval(VariableData previous, String indexIn, Statement statement, ReturnVariable rv) {
        String index = statement.hasSubBlocks() ? indexIn + "-E" : indexIn;
        Visitor v = new Visitor(previous == null ? Set.of() : previous.knownVariableNames());
        if (statement instanceof ReturnStatement && rv != null) {
            v.assigned.add(rv);
        } else if (statement instanceof LocalVariableCreation lvc) {
            handleLvc(lvc, v);
        } else if (statement instanceof ForStatement fs) {
            for (Element initializer : fs.initializers()) {
                if (initializer instanceof LocalVariableCreation lvc) {
                    handleLvc(lvc, v);
                } else if (initializer instanceof Expression e) {
                    e.visit(v);
                } else throw new UnsupportedOperationException();
            }
            for (Expression updater : fs.updaters()) {
                updater.visit(v);
            }
        } else if (statement instanceof ForEachStatement fe) {
            handleLvc(fe.initializer(), v);
        } else if (statement instanceof AssertStatement as) {
            if (as.message() != null) as.message().visit(v);
        } else if (statement instanceof ExplicitConstructorInvocation eci) {
            eci.parameterExpressions().forEach(e -> e.visit(v));
        } else if (statement instanceof TryStatement ts) {
            ts.resources().forEach(r -> handleLvc(r, v));
        }
        Expression expression = statement.expression();
        if (expression != null) expression.visit(v);
        return new ReadWriteData(previous, index, v.seenFirstTime, v.read, v.assigned);
    }

    private static void handleLvc(LocalVariableCreation lvc, Visitor v) {
        v.seenFirstTime.add(lvc.localVariable());
        Expression assignmentExpression = lvc.localVariable().assignmentExpression();
        if (!assignmentExpression.isEmpty()) {
            v.assigned.add(lvc.localVariable());
            assignmentExpression.visit(v);
        }
        for (LocalVariable lv : lvc.otherLocalVariables()) {
            v.seenFirstTime.add(lv);
            if (!lv.assignmentExpression().isEmpty()) {
                v.assigned.add(lv);
                lv.assignmentExpression().visit(v);
            }
        }
    }

    private static class Visitor implements Predicate<Element> {
        final Set<Variable> read = new HashSet<>();
        final Set<Variable> assigned = new HashSet<>();
        final Set<Variable> seenFirstTime = new HashSet<>();
        final Set<String> knownVariableNames;

        Visitor(Set<String> knownVariableNames) {
            this.knownVariableNames = knownVariableNames;
        }

        @Override
        public boolean test(Element e) {
            if (e instanceof VariableExpression ve) {
                ve.variable().variableStreamDescend().forEach(v -> {
                    read.add(v);
                    if (!knownVariableNames.contains(v.fullyQualifiedName())) {
                        seenFirstTime.add(v);
                    }
                });
                return false;
            }
            if (e instanceof InstanceOf instanceOf && instanceOf.patternVariable() != null) {
                seenFirstTime.add(instanceOf.patternVariable());
                assigned.add(instanceOf.patternVariable());
            }
            if (e instanceof Assignment a) {
                assigned.add(a.variableTarget());
                a.value().visit(this);
                return false;
            }
            return true;
        }
    }
}
