package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.language.cst.impl.variable.ThisImpl;
import org.e2immu.support.Either;
import org.e2immu.util.internal.graph.V;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer.NOT_YET_READ;

/*
do all the analysis of this phase

 */
public class Analyze {
    private final Runtime runtime;

    public Analyze(Runtime runtime) {
        this.runtime = runtime;
    }

    private record ReadWriteData(VariableData previous, String index,
                                 Set<Variable> seenFirstTime,
                                 Set<Variable> read,
                                 Set<Variable> assigned) {
        public AssignmentIds assignmentIds(Variable v) {
            String fqn = v.fullyQualifiedName();
            AssignmentIds prev = previous == null || !previous.isKnown(fqn)
                    ? AssignmentIds.NOT_YET_ASSIGNED
                    : previous.variableInfo(fqn).assignmentIds();
            if (assigned.contains(v)) {
                return new AssignmentIds(index, prev);
            }
            return prev;
        }

        public String isRead(Variable v) {
            String fqn = v.fullyQualifiedName();
            return read.contains(v) ? index : previous == null || !previous.isKnown(fqn)
                    ? NOT_YET_READ
                    : previous.variableInfo(fqn).readId();
        }

        public Set<Integer> modificationIds(Variable v) {
            return Set.of();
        }

        public Set<Variable> variablesSeenFirstTime() {
            return seenFirstTime;
        }
    }

    public void doMethod(MethodInfo methodInfo) {
        VariableDataImpl vdi = new VariableDataImpl();
        Statement statement = methodInfo.methodBody().statements().get(0);
        boolean hasMerge = statement.block() != null;
        ReturnVariable rv = methodInfo.hasReturnValue() ? new ReturnVariableImpl(methodInfo) : null;
        ReadWriteData readWriteData = analyzeEval(null, "0", statement, rv);
        if (methodInfo.hasReturnValue()) {
            assert rv != null;
            vdi.put(rv, initial(rv, readWriteData, hasMerge));
        }
        for (ParameterInfo pi : methodInfo.parameters()) {
            vdi.put(pi, initial(pi, readWriteData, hasMerge));
        }
        for (Variable v : readWriteData.variablesSeenFirstTime()) {
            vdi.putIfAbsent(v, initial(v, readWriteData, hasMerge));
        }
        This thisVar = methodInfo.isStatic() ? null : runtime.newThis(methodInfo.typeInfo());
        if (thisVar != null) {
            vdi.putIfAbsent(thisVar, initial(thisVar, readWriteData, hasMerge));
        }
        statement.analysis().set(VariableDataImpl.VARIABLE_DATA, vdi);

        if (statement.block() != null) {
            List<VariableData> lastOfEachSubBlock = doBlocks(statement, vdi, rv);
            // make "merge" VIs for each variable
        }
        VariableData lastOfMainBlock = doBlock(methodInfo.methodBody(), vdi, null, rv);
        methodInfo.analysis().set(VariableDataImpl.VARIABLE_DATA, lastOfMainBlock);
    }

    private List<VariableData> doBlocks(Statement parentStatement, VariableData vdOfParent, ReturnVariable rv) {
        return Stream.concat(Stream.of(parentStatement.block()), parentStatement.otherBlocks().stream())
                .map(block -> doBlock(block, null, vdOfParent, rv))
                .toList();
    }

    private VariableData doBlock(Block block, VariableData vdOfFirstStatement, VariableData vdOfParent,
                                 ReturnVariable rv) {
        VariableData previous = vdOfFirstStatement != null ? vdOfFirstStatement : vdOfParent;
        VariableData last = previous;
        int start = vdOfFirstStatement != null ? 1 : 0;
        for (int i = start; i < block.statements().size(); i++) {
            Statement statement = block.statements().get(i);
            String index = statement.source().index();
            ReadWriteData readWriteData = analyzeEval(previous, index, statement, rv);
            VariableDataImpl vdi = new VariableDataImpl();
            boolean hasMerge = statement.block() != null;

            previous.variableInfoContainerStream().forEach(vic -> {
                VariableInfo vi = vic.best();
                Variable variable = vi.variable();
                VariableInfoImpl eval = new VariableInfoImpl(variable, readWriteData.assignmentIds(variable),
                        readWriteData.isRead(variable), readWriteData.modificationIds(variable));
                VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vic.variableNature(),
                        Either.left(vic), eval, hasMerge);
                vdi.put(variable, newVic);
            });

            for (Variable v : readWriteData.variablesSeenFirstTime()) {
                vdi.put(v, initial(v, readWriteData, hasMerge));
            }
            statement.analysis().set(VariableDataImpl.VARIABLE_DATA, vdi);
            // FIXME add
            last = vdi;
        }
        return last;
    }

    private VariableInfoContainer initial(Variable rv, ReadWriteData readWriteData, boolean hasMerge) {
        VariableInfoImpl initial = new VariableInfoImpl(rv, AssignmentIds.NOT_YET_ASSIGNED, NOT_YET_READ, Set.of());
        VariableInfoImpl eval = new VariableInfoImpl(rv, readWriteData.assignmentIds(rv), readWriteData.isRead(rv),
                readWriteData.modificationIds(rv));
        return new VariableInfoContainerImpl(rv, NormalVariableNature.INSTANCE,
                Either.right(initial), eval, hasMerge);
    }

    private ReadWriteData analyzeEval(VariableData previous, String index, Statement statement, ReturnVariable rv) {
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
        if (lvc.localVariable().assignmentExpression() != null) {
            v.assigned.add(lvc.localVariable());
        }
        for (LocalVariable lv : lvc.otherLocalVariables()) {
            v.seenFirstTime.add(lv);
            if (lv.assignmentExpression() != null) {
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
                Variable variable = ve.variable();
                variable.variableStreamDescend().forEach(read::add);
                if (!knownVariableNames.contains(variable.fullyQualifiedName())) {
                    seenFirstTime.add(variable);
                }
                return false;
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
