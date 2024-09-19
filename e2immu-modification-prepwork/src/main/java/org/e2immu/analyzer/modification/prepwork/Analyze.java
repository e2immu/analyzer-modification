package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.Assignment;
import org.e2immu.language.cst.api.expression.Expression;
import org.e2immu.language.cst.api.expression.InstanceOf;
import org.e2immu.language.cst.api.expression.VariableExpression;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.variable.LocalVariable;
import org.e2immu.language.cst.api.variable.Variable;
import org.e2immu.support.Either;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.StatementIndex.*;
import static org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer.NOT_YET_READ;

/*
do all the analysis of this phase

 */
public class Analyze {
    private final Runtime runtime;

    public Analyze(Runtime runtime) {
        this.runtime = runtime;
    }

    private static class InternalVariables {
        final ReturnVariable rv;
        final Stack<LocalVariable> breakVariables = new Stack<>();


        final Stack<Statement> loopSwitchStack = new Stack<>();
        final Map<String, String> labelToStatementIndex = new HashMap<>();
        final Map<String, Integer> breakCountsInLoop = new HashMap<>();

        InternalVariables(ReturnVariable rv) {
            this.rv = rv;
        }

        List<Variable> currentVariables() {
            if (breakVariables.isEmpty()) return List.of(rv);
            return List.of(rv, breakVariables.peek());
        }

        void handleStatement(String index, Statement statement) {
            if (statement.label() != null) {
                labelToStatementIndex.put(statement.label(), index);
            }
            if (statement instanceof SwitchStatementOldStyle || statement instanceof LoopStatement) {
                loopSwitchStack.push(statement);
            } else if (statement instanceof BreakStatement bs) {
                String loopIndex;
                if (bs.label() == null) {
                    loopIndex = loopSwitchStack.peek().source().index();
                } else {
                    loopIndex = labelToStatementIndex.get(bs.label());
                }
                breakCountsInLoop.merge(loopIndex, 1, Integer::sum);
            }
        }

        void endHandleStatement(Statement statement) {
            if (statement instanceof SwitchStatementOldStyle || statement instanceof LoopStatement) {
                loopSwitchStack.pop();
            }
        }

        void popBreakVariable(LocalVariable bv) {
            LocalVariable top = breakVariables.pop();
            assert top == bv;
        }

        void pushBreakVariable(LocalVariable bv) {
            breakVariables.push(bv);
        }

        LocalVariable bv() {
            return breakVariables.isEmpty() ? null : breakVariables.peek();
        }
    }

    private record ReadWriteData(VariableData previous,
                                 String index,
                                 Map<Variable, String> seenFirstTime,
                                 Map<Variable, String> read,
                                 Map<Variable, List<String>> assigned) {
        public Assignments assignmentIds(Variable v, VariableInfo previous) {
            Assignments prev;
            if (previous == null) {
                assert !(v instanceof LocalVariable);
                prev = new Assignments(BEFORE_METHOD);
            } else {
                prev = previous.assignments();
            }
            List<String> assignIds = assigned.get(v);
            if (assignIds != null) {
                return Assignments.newAssignment(assignIds, prev);
            }
            return prev;
        }

        public String isRead(Variable v, VariableInfo previous) {
            String i = read.get(v);
            return i != null ? i : previous == null ? NOT_YET_READ : previous.readId();
        }
    }

    public void doMethod(MethodInfo methodInfo) {
        // even if the method does not return a value, we'll compute "assignments" to the return variable,
        // in order to know when the method exits (we'll track 'throw' and 'return' statements)
        ReturnVariable rv = new ReturnVariableImpl(methodInfo);
        // start analysis, and copy results of last statement into method
        InternalVariables iv = new InternalVariables(rv);
        VariableData lastOfMainBlock = doBlock(methodInfo, methodInfo.methodBody(), null, iv);
        if (lastOfMainBlock != null) {
            methodInfo.analysis().set(VariableDataImpl.VARIABLE_DATA, lastOfMainBlock);
        } // else: empty
    }

    private Map<String, VariableData> doBlocks(MethodInfo methodInfo,
                                               Statement parentStatement,
                                               VariableData vdOfParent,
                                               InternalVariables iv) {
        return parentStatement.subBlockStream()
                .filter(b -> !b.isEmpty())
                .collect(Collectors.toUnmodifiableMap(
                        block -> block.statements().get(0).source().index(),
                        block -> doBlock(methodInfo, block, vdOfParent, iv)));
    }

    private VariableData doBlock(MethodInfo methodInfo,
                                 Block block,
                                 VariableData vdOfParent,
                                 InternalVariables iv) {
        VariableData previous = vdOfParent;
        boolean first = true;
        for (Statement statement : block.statements()) {
            previous = doStatement(methodInfo, statement, previous, first, iv);
            if (first) first = false;
        }
        return previous;
    }

    private VariableData doStatement(MethodInfo methodInfo,
                                     Statement statement,
                                     VariableData previous,
                                     boolean first,
                                     InternalVariables iv) {
        String index = statement.source().index();
        ReadWriteData readWriteData = analyzeEval(previous, index, statement, iv);
        VariableDataImpl vdi = new VariableDataImpl();
        boolean hasMerge = statement.hasSubBlocks();
        Stage stageOfPrevious = first ? Stage.EVALUATION : Stage.MERGE;
        Stream<VariableInfoContainer> streamOfPrevious;
        if (previous == null) {
            streamOfPrevious = Stream.of();
        } else {
            streamOfPrevious = previous.variableInfoContainerStream();
        }

        readWriteData.seenFirstTime.forEach((v, i) -> {
            VariableInfoContainer vic = initialVariable(i, v, readWriteData,
                    hasMerge && !(v instanceof LocalVariable));
            vdi.put(v, vic);
        });

        streamOfPrevious.forEach(vic -> {
            VariableInfo vi = vic.best(stageOfPrevious);
            if (Util.inScopeOf(vi.assignments().indexOfDefinition(), index)) {
                Variable variable = vi.variable();

                VariableInfoImpl eval = new VariableInfoImpl(variable, readWriteData.assignmentIds(variable, vi),
                        readWriteData.isRead(variable, vi));
                boolean specificHasMerge = hasMerge && !readWriteData.seenFirstTime.containsKey(variable);
                VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vic.variableNature(),
                        Either.left(vic), eval, specificHasMerge);
                vdi.put(variable, newVic);
            }
        });

        iv.handleStatement(index, statement);

        if (statement instanceof SwitchStatementOldStyle oss) {
            doOldStyleSwitchBlock(methodInfo, oss, vdi, iv);
        } else {
            // sub-blocks
            if (statement.hasSubBlocks()) {
                Map<String, VariableData> lastOfEachSubBlock = doBlocks(methodInfo, statement, vdi, iv);

                boolean noBreakStatementsInside;
                if (statement instanceof LoopStatement) {
                    noBreakStatementsInside = !iv.breakCountsInLoop.containsKey(index);
                } else {
                    noBreakStatementsInside = false;
                }
                addMerge(index, statement, vdi, noBreakStatementsInside, lastOfEachSubBlock, iv, Map.of());
            }
        }
        iv.endHandleStatement(statement);

        statement.analysis().set(VariableDataImpl.VARIABLE_DATA, vdi);
        return vdi;
    }

    private static final VariableNature SYNTHETIC = new VariableNature() {
    };

    /*
     completely custom block-code
     */
    private void doOldStyleSwitchBlock(MethodInfo methodInfo,
                                       SwitchStatementOldStyle oss,
                                       VariableDataImpl vdOfParent,
                                       InternalVariables iv) {
        Set<String> startOfNewLabels = oss.switchLabelMap().keySet();
        String index = oss.source().index();
        assert vdOfParent != null;
        LocalVariable bv = runtime.newLocalVariable("bv-" + index, runtime.booleanParameterizedType(),
                runtime.newEmptyExpression());
        iv.pushBreakVariable(bv);
        Assignments notYetAssigned = new Assignments(index + EVAL);
        VariableInfoImpl vii = new VariableInfoImpl(bv, notYetAssigned, NOT_YET_READ);
        vdOfParent.put(bv, new VariableInfoContainerImpl(bv, SYNTHETIC, Either.right(vii), null,
                false));
        VariableData previous = vdOfParent;
        boolean first = true;
        String indexOfFirstStatement = null;
        Map<String, VariableData> lastOfEachSubBlock = new HashMap<>();

        List<VariableData> fallThrough = new ArrayList<>();
        Map<String, List<VariableData>> fallThroughRecord = new HashMap<>();

        for (Statement statement : oss.block().statements()) {
            String statementIndex = statement.source().index();
            if (indexOfFirstStatement == null) {
                indexOfFirstStatement = statementIndex;
            }
            if (!first && startOfNewLabels.contains(statementIndex)) {
                // fall-through, we'll start again, but append the current data
                assert previous != vdOfParent;
                fallThrough.add(previous);
                indexOfFirstStatement = statementIndex;
                previous = vdOfParent;
                first = true;
            }
            VariableData vd = doStatement(methodInfo, statement, previous, first, iv);
            if (statement instanceof BreakStatement
                || statement instanceof ReturnStatement
                || statementGuaranteedToExit(indexOfFirstStatement, vd, iv)) {
                if (indexOfFirstStatement != null) {
                    lastOfEachSubBlock.put(indexOfFirstStatement, vd);
                    if (!fallThrough.isEmpty()) {
                        fallThroughRecord.put(indexOfFirstStatement, List.copyOf(fallThrough));
                        fallThrough.clear();
                    }
                    indexOfFirstStatement = null;
                }
                first = true;
                previous = vdOfParent;
            } else {
                previous = vd;
                first = false;
            }
        }
        if (indexOfFirstStatement != null) {
            lastOfEachSubBlock.put(indexOfFirstStatement, previous);
        }
        // noBreakStatementsInside irrelevant for switch
        addMerge(index, oss, vdOfParent, false, lastOfEachSubBlock, iv, fallThroughRecord);
        iv.popBreakVariable(bv);
    }

    private boolean statementGuaranteedToExit(String index, VariableData vd, InternalVariables iv) {
        for (Variable v : iv.currentVariables()) {
            VariableInfoContainer vic = vd.variableInfoContainerOrNull(v.fullyQualifiedName());
            if (vic != null) {
                VariableInfo vi = vic.best();
                if (vi.assignments().lastAssignmentIsMergeInBlockOf(index)) return true;
            }
        }
        return false;
    }

    /*
    Variables can be local to a block, in which case they are NOT transferred to the merge.
    Fields occurring in one of the blocks are kept in the merge.
    Scopes of fields that and array variables may survive in the following conditions: TODO
    Pattern variables may survive, if they occurred in a "negative" expression: "if(!(e instanceof X x)) { throw ... }"

    Some variables already exist, but are not referenced in any of the blocks at all.
     */
    private void addMerge(String index,
                          Statement statement,
                          VariableDataImpl vdStatement,
                          boolean noBreakStatementsInside,
                          Map<String, VariableData> lastOfEachSubBlock,
                          InternalVariables iv,
                          Map<String, List<VariableData>> fallThroughRecord) {
        Map<Variable, Map<String, VariableInfo>> map = new HashMap<>();
        for (Map.Entry<String, VariableData> entry : lastOfEachSubBlock.entrySet()) {
            String subIndex = entry.getKey();
            VariableData vd = entry.getValue();
            vd.variableInfoStream().forEach(vi -> {
                if (copyToMerge(index, vi)) {
                    map.computeIfAbsent(vi.variable(), v -> new TreeMap<>()).put(subIndex, vi);
                }
            });
        }
        if (map.isEmpty()) {
            // copy best(EVAL) into merge, if a merge is present
            vdStatement.variableInfoContainerStream().forEach(vic -> {
                if (vic.hasMerge()) {
                    VariableInfoContainerImpl vici = (VariableInfoContainerImpl) vic;
                    vici.setMerge((VariableInfoImpl) vic.best(Stage.EVALUATION));
                }
            });
        } else {
            map.forEach((v, vis) -> {
                Map<String, Assignments> assignmentsPerBlock = vis.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().assignments()));
                // the return variable passed on for corrections is 'null' when we're computing for the return variable
                ReturnVariable returnVariable = v.equals(iv.rv) ? null : iv.rv;
                Assignments.CompleteMerge assignmentsRequiredForMerge = Assignments.assignmentsRequiredForMerge(statement,
                        lastOfEachSubBlock, returnVariable, noBreakStatementsInside);
                // only old-style switch needs this 'lastOfEachSubBlock; only loops need "noBreakStatementsInside

                Map<String, List<Assignments>> fallThroughForV = computeFallThrough(fallThroughRecord, v);
                Assignments assignments = Assignments.mergeBlocks(index, assignmentsRequiredForMerge, assignmentsPerBlock,
                        fallThroughForV);
                String readId = vis.values().stream().map(VariableInfo::readId).reduce(NOT_YET_READ,
                        (s1, s2) -> s1.compareTo(s2) <= 0 ? s2 : s1);
                VariableInfoImpl merge = new VariableInfoImpl(v, assignments, readId);
                VariableInfoContainer inMap = vdStatement.variableInfoContainerOrNull(v.fullyQualifiedName());
                VariableInfoContainerImpl vici;
                if (inMap == null) {
                    String indexOfDefinition = v instanceof LocalVariable ? index : BEFORE_METHOD;
                    Assignments notYetAssigned = new Assignments(indexOfDefinition);
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
    }

    private static Map<String, List<Assignments>> computeFallThrough(Map<String, List<VariableData>> fallThroughRecord, Variable v) {
        if (fallThroughRecord.isEmpty()) return Map.of();
        Map<String, List<Assignments>> res = new HashMap<>();
        fallThroughRecord.forEach((index, vds) -> vds.forEach(vd -> {
            VariableInfoContainer vic = vd.variableInfoContainerOrNull(v.fullyQualifiedName());
            if (vic != null) {
                VariableInfo vi = vic.best();
                Assignments a = vi.assignments();
                if (a.size() > 0) {
                    List<Assignments> list = res.computeIfAbsent(index, ii -> new ArrayList<>());
                    list.add(a);
                }
            }
        }));
        return res;
    }

    private boolean copyToMerge(String index, VariableInfo vi) {
        return !(vi.variable() instanceof LocalVariable) || index.compareTo(vi.assignments().indexOfDefinition()) >= 0;
    }

    private VariableInfoContainer initialVariable(String index, Variable v, ReadWriteData readWriteData, boolean hasMerge) {
        String indexOfDefinition = v instanceof LocalVariable ? index : StatementIndex.BEFORE_METHOD;
        Assignments notYetAssigned = new Assignments(indexOfDefinition);
        VariableInfoImpl initial = new VariableInfoImpl(v, notYetAssigned, NOT_YET_READ);
        VariableInfoImpl eval = new VariableInfoImpl(v, readWriteData.assignmentIds(v, initial),
                readWriteData.isRead(v, initial));
        return new VariableInfoContainerImpl(v, NormalVariableNature.INSTANCE,
                Either.right(initial), eval, hasMerge);
    }

    private ReadWriteData analyzeEval(VariableData previous, String indexIn, Statement statement,
                                      InternalVariables iv) {
        String index = statement.hasSubBlocks() ? indexIn + StatementIndex.EVAL : indexIn;
        Set<String> knownVariableNames = previous == null ? Set.of() : previous.knownVariableNames();
        Visitor v = new Visitor(index, knownVariableNames);
        if (statement instanceof ReturnStatement || statement instanceof ThrowStatement) {
            v.assignedAdd(iv.rv);
            if (!v.knownVariableNames.contains(iv.rv.fullyQualifiedName())) {
                v.seenFirstTime.put(iv.rv, v.index);
            }
        } else {
            LocalVariable bv = iv.bv();
            if (statement instanceof BreakStatement && bv != null) {
                v.assignedAdd(bv);
                if (!v.knownVariableNames.contains(bv.fullyQualifiedName())) {
                    v.seenFirstTime.put(bv, v.index);
                }
            } else if (statement instanceof LocalVariableCreation lvc) {
                handleLvc(lvc, v);
            } else if (statement instanceof ForStatement fs) {
                for (Element initializer : fs.initializers()) {
                    if (initializer instanceof LocalVariableCreation lvc) {
                        handleLvc(lvc, v.withIndex(indexIn + EVAL_INIT));
                    } else if (initializer instanceof Expression e) {
                        e.visit(v.withIndex(indexIn + EVAL_INIT));
                    } else throw new UnsupportedOperationException();
                }
                for (Expression updater : fs.updaters()) {
                    updater.visit(v.withIndex(indexIn + EVAL_UPDATE));
                }
            } else if (statement instanceof ForEachStatement fe) {
                handleLvc(fe.initializer(), v.withIndex(indexIn + EVAL_INIT));
                v.assignedAdd(fe.initializer().localVariable());
            } else if (statement instanceof AssertStatement as) {
                if (as.message() != null) as.message().visit(v);
            } else if (statement instanceof ExplicitConstructorInvocation eci) {
                eci.parameterExpressions().forEach(e -> e.visit(v));
            } else if (statement instanceof TryStatement ts) {
                ts.resources().forEach(r -> {
                    if (r instanceof LocalVariableCreation lvc) {
                        handleLvc(lvc, v);
                    } else {
                        r.visit(v);
                    }
                });
            }
        }
        Expression expression = statement.expression();
        if (expression != null && !expression.isEmpty()) expression.visit(v.withIndex(index));
        return new ReadWriteData(previous, index, v.seenFirstTime, v.read, v.assigned);
    }

    private static void handleLvc(LocalVariableCreation lvc, Visitor v) {
        v.seenFirstTime.put(lvc.localVariable(), v.index);
        Expression assignmentExpression = lvc.localVariable().assignmentExpression();
        if (!assignmentExpression.isEmpty()) {
            v.assignedAdd(lvc.localVariable());
            assignmentExpression.visit(v);
        }
        for (LocalVariable lv : lvc.otherLocalVariables()) {
            v.seenFirstTime.put(lv, v.index);
            if (!lv.assignmentExpression().isEmpty()) {
                v.assignedAdd(lv);
                lv.assignmentExpression().visit(v);
            }
        }
    }

    private static class Visitor implements Predicate<Element> {
        String index;
        final Map<Variable, String> read = new HashMap<>();
        final Map<Variable, List<String>> assigned = new HashMap<>();
        final Map<Variable, String> seenFirstTime = new HashMap<>();
        final Set<String> knownVariableNames;

        Visitor(String index, Set<String> knownVariableNames) {
            this.index = index;
            this.knownVariableNames = knownVariableNames;
        }

        public void assignedAdd(Variable variable) {
            assigned.computeIfAbsent(variable, v -> new ArrayList<>()).add(index);
        }

        @Override
        public boolean test(Element e) {
            if (e instanceof VariableExpression ve) {
                ve.variable().variableStreamDescend().forEach(v -> {
                    read.put(v, index);
                    if (!knownVariableNames.contains(v.fullyQualifiedName()) && !seenFirstTime.containsKey(v)) {
                        seenFirstTime.put(v, index);
                    }
                });
                return false;
            }
            if (e instanceof InstanceOf instanceOf && instanceOf.patternVariable() != null) {
                seenFirstTime.put(instanceOf.patternVariable(), index);
                assignedAdd(instanceOf.patternVariable());
            }
            if (e instanceof Assignment a) {
                assignedAdd(a.variableTarget());
                a.value().visit(this);
                return false;
            }
            return true;
        }

        public Visitor withIndex(String s) {
            this.index = s;
            return this;
        }
    }
}
