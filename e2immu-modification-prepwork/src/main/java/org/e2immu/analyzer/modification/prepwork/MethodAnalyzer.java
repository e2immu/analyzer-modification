package org.e2immu.analyzer.modification.prepwork;

import org.e2immu.analyzer.modification.prepwork.escape.ComputeAlwaysEscapes;
import org.e2immu.analyzer.modification.prepwork.variable.*;
import org.e2immu.analyzer.modification.prepwork.variable.impl.*;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.ParameterInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.*;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.support.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.e2immu.analyzer.modification.prepwork.StatementIndex.*;

/*
do all the analysis of this phase

 */
public class MethodAnalyzer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodAnalyzer.class);

    private final Runtime runtime;

    public MethodAnalyzer(Runtime runtime) {
        this.runtime = runtime;
    }

    private static class InternalVariables {
        final InternalVariables parent;

        // variables that are copied
        final ReturnVariable rv;
        final Stack<Statement> loopSwitchStack;
        final Map<String, String> labelToStatementIndex;
        final Map<String, Integer> breakCountsInLoop;

        // variables that are searched via parent
        LocalVariable breakVariable;
        final Map<Variable, String> limitedScopeOfPatternVariables = new HashMap<>();

        InternalVariables(ReturnVariable rv) {
            this.rv = rv;
            this.parent = null;
            loopSwitchStack = new Stack<>();
            labelToStatementIndex = new HashMap<>();
            breakCountsInLoop = new HashMap<>();
        }

        InternalVariables(InternalVariables parent) {
            this.parent = parent;
            this.rv = parent.rv;
            this.loopSwitchStack = parent.loopSwitchStack;
            this.labelToStatementIndex = parent.labelToStatementIndex;
            this.breakCountsInLoop = parent.breakCountsInLoop;
        }

        public boolean acceptLimitedScope(Variable variable, String index) {
            String i = limitedScopeOfPatternVariables.get(variable);
            if (i != null) {
                return Util.inScopeOf(i, index);
            }
            // go up, maybe it was defined higher up
            if (parent != null) {
                return parent.acceptLimitedScope(variable, index);
            }
            return true;
        }

        List<Variable> currentVariables() {
            if (breakVariable != null) return List.of(rv, breakVariable);
            if (parent == null) return List.of(rv);
            return parent.currentVariables();
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

        void setBreakVariable(LocalVariable bv) {
            breakVariable = bv;
        }

        LocalVariable bv() {
            if (breakVariable != null) return breakVariable;
            if (parent == null) return null;
            return parent.bv();
        }
    }

    private record ReadWriteData(VariableData previous,
                                 Map<Variable, String> seenFirstTime,
                                 Map<Variable, String> accessorSeenFirstTime,
                                 Map<Variable, List<String>> read,
                                 Map<Variable, List<String>> assigned,
                                 Map<Variable, String> restrictToScope) {
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

        public Reads isRead(Variable v, VariableInfo previous) {
            List<String> i = read.get(v);
            if (i == null) {
                return previous == null ? Reads.NOT_YET_READ : previous.reads();
            }
            return previous == null ? new Reads(i) : previous.reads().with(i);
        }
    }

    public void doMethod(MethodInfo methodInfo) {
        doMethod(methodInfo, methodInfo.methodBody());
    }

    public void doMethod(MethodInfo methodInfo, Block methodBody) {
        LOGGER.debug("Do method {}", methodInfo);
        // even if the method does not return a value, we'll compute "assignments" to the return variable,
        // in order to know when the method exits (we'll track 'throw' and 'return' statements)
        ReturnVariable rv = new ReturnVariableImpl(methodInfo);
        // start analysis, and copy results of last statement into method
        InternalVariables iv = new InternalVariables(rv);
        try {
            VariableData lastOfMainBlock = doBlock(methodInfo, methodBody, null, iv);
            if (lastOfMainBlock != null) {
                if (!methodInfo.analysis().haveAnalyzedValueFor(VariableDataImpl.VARIABLE_DATA)) {
                    methodInfo.analysis().set(VariableDataImpl.VARIABLE_DATA, lastOfMainBlock);
                }
            } // else: empty

            // always escapes
            ComputeAlwaysEscapes.go(methodBody);
        } catch (Throwable t) {
            LOGGER.error("Caught exception in method {}", methodInfo);
            throw t;
        }
    }

    private Map<String, VariableData> doBlocks(MethodInfo methodInfo,
                                               Statement parentStatement,
                                               VariableData vdOfParent,
                                               InternalVariables iv) {
        Stream<Block> blockStream;
        if (parentStatement instanceof TryStatement ts && !(ts.resources().isEmpty())) {
            Block.Builder bb = runtime.newBlockBuilder();
            bb.addStatements(ts.resources());
            bb.addStatements(ts.block().statements());
            blockStream = Stream.concat(Stream.of(bb.build()), ts.otherBlocksStream());
        } else {
            blockStream = parentStatement.subBlockStream();
        }
        return blockStream
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
            try {
                previous = doStatement(methodInfo, statement, previous, first, iv);
                if (first) first = false;
            } catch (Throwable t) {
                LOGGER.error("Caught exception in statement {}", statement.source());
                throw t;
            }
        }
        return previous;
    }

    private VariableData doStatement(MethodInfo methodInfo,
                                     Statement statement,
                                     VariableData previous,
                                     boolean first,
                                     InternalVariables ivIn) {
        InternalVariables iv = new InternalVariables(ivIn);
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
            String limitedScope = readWriteData.restrictToScope.get(v);
            if (limitedScope != null) {
                iv.limitedScopeOfPatternVariables.put(v, limitedScope);
            }
        });

        streamOfPrevious.forEach(vic -> {
            VariableInfo vi = vic.best(stageOfPrevious);
            if (Util.inScopeOf(vi.assignments().indexOfDefinition(), index) && iv.acceptLimitedScope(vi.variable(), index)) {
                Variable variable = vi.variable();

                VariableInfoImpl eval = new VariableInfoImpl(variable, readWriteData.assignmentIds(variable, vi),
                        readWriteData.isRead(variable, vi));
                boolean specificHasMerge = hasMerge && !readWriteData.seenFirstTime.containsKey(variable);
                VariableInfoContainer newVic = new VariableInfoContainerImpl(variable, vic.variableNature(),
                        Either.left(vic), eval, specificHasMerge);
                vdi.put(variable, newVic);
            }
        });

        readWriteData.accessorSeenFirstTime
                .entrySet().stream().filter(e -> !vdi.isKnown(e.getKey().fullyQualifiedName()))
                .forEach(e -> {
                    String i = e.getValue();
                    Variable v = e.getKey();
                    Assignments firstAssigned = new Assignments(i);
                    VariableInfoImpl initial = new VariableInfoImpl(v, firstAssigned, Reads.NOT_YET_READ);
                    Reads reads = new Reads(i);
                    VariableInfoImpl eval = new VariableInfoImpl(v, readWriteData.assignmentIds(v, initial), reads);
                    VariableInfoContainer vic = new VariableInfoContainerImpl(v, NormalVariableNature.INSTANCE,
                            Either.right(initial), eval, false);
                    vdi.put(v, vic);
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
        iv.setBreakVariable(bv);
        Assignments notYetAssigned = new Assignments(index + EVAL);
        VariableInfoImpl vii = new VariableInfoImpl(bv, notYetAssigned, Reads.NOT_YET_READ);
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
                VariableInfoContainer vic = vdStatement.variableInfoContainerOrNull(vi.variable().fullyQualifiedName());
                if (vic == null || vic.hasMerge()) {
                    if (copyToMerge(index, vi) && iv.acceptLimitedScope(vi.variable(), index)) {
                        map.computeIfAbsent(vi.variable(), v -> new TreeMap<>()).put(subIndex, vi);
                    }
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
                List<String> readIds = vis.values().stream().flatMap(vi -> vi.reads().indices().stream()).distinct().sorted().toList();
                VariableInfoImpl merge = new VariableInfoImpl(v, assignments, new Reads(readIds));
                VariableInfoContainer inMap = vdStatement.variableInfoContainerOrNull(v.fullyQualifiedName());
                VariableInfoContainerImpl vici;
                if (inMap == null) {
                    String indexOfDefinition = v instanceof LocalVariable ? index : BEFORE_METHOD;
                    Assignments notYetAssigned = new Assignments(indexOfDefinition);
                    VariableInfoImpl initial = new VariableInfoImpl(v, notYetAssigned, Reads.NOT_YET_READ);
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
        return !isLocal(vi.variable()) || index.compareTo(vi.assignments().indexOfDefinition()) >= 0;
    }

    private static boolean isLocal(Variable v) {
        return v instanceof LocalVariable
               || v instanceof FieldReference fr && isLocal(fr.scopeVariable())
               || v instanceof DependentVariable dv
                  && (isLocal(dv.arrayVariable()) || isLocal(dv.indexVariable()));
    }

    private VariableInfoContainer initialVariable(String index, Variable v, ReadWriteData readWriteData,
                                                  boolean hasMerge) {
        String indexOfDefinition = v instanceof LocalVariable ? index : StatementIndex.BEFORE_METHOD;
        Assignments notYetAssigned = new Assignments(indexOfDefinition);
        VariableInfoImpl initial = new VariableInfoImpl(v, notYetAssigned, Reads.NOT_YET_READ);
        Reads reads = readWriteData.isRead(v, initial);
        VariableInfoImpl eval = new VariableInfoImpl(v, readWriteData.assignmentIds(v, initial), reads);
        return new VariableInfoContainerImpl(v, NormalVariableNature.INSTANCE,
                Either.right(initial), eval, hasMerge);
    }

    private ReadWriteData analyzeEval(VariableData previous, String indexIn, Statement statement, InternalVariables iv) {
        String index;
        if (statement.hasSubBlocks()) {
            String suffix = statement instanceof DoStatement ? EVAL_UPDATE : EVAL;
            index = indexIn + suffix;
        } else {
            index = indexIn;
        }
        Set<String> knownVariableNames = previous == null ? Set.of() : previous.knownVariableNames();
        Visitor v = new Visitor(index, knownVariableNames, statement);
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
                // the condition is evaluated 2x
                fs.expression().visit(v.withIndex(indexIn + EVAL_AFTER_UPDATE));
            } else if (statement instanceof WhileStatement ws) {
                ws.expression().visit(v.withIndex(indexIn + EVAL_AFTER_UPDATE));
            } else if (statement instanceof ForEachStatement fe) {
                handleLvc(fe.initializer(), v.withIndex(indexIn + EVAL_INIT));
                v.assignedAdd(fe.initializer().localVariable());
            } else if (statement instanceof AssertStatement as) {
                if (as.message() != null) as.message().visit(v);
            } else if (statement instanceof ExplicitConstructorInvocation eci) {
                eci.parameterExpressions().forEach(e -> e.visit(v));
            }
        }
        Expression expression = statement.expression();
        if (expression != null && !expression.isEmpty()) expression.visit(v.withIndex(index));
        return new ReadWriteData(previous, v.seenFirstTime, v.accessorSeenFirstTime, v.read, v.assigned,
                v.restrictToScope);
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

    private class Visitor implements org.e2immu.language.cst.api.element.Visitor {
        String index;
        int inNegative;

        final Map<Variable, List<String>> read = new HashMap<>();
        final Map<Variable, List<String>> assigned = new HashMap<>();
        final Map<Variable, String> seenFirstTime = new HashMap<>();
        final Map<Variable, String> accessorSeenFirstTime = new HashMap<>();
        final Map<Variable, String> restrictToScope = new HashMap<>();
        final Set<String> knownVariableNames;
        final Statement statement;

        Visitor(String index, Set<String> knownVariableNames, Statement statement) {
            this.index = index;
            this.knownVariableNames = Set.copyOf(knownVariableNames); // make sure we do not modify it
            this.statement = statement;
        }

        public void assignedAdd(Variable variable) {
            assigned.computeIfAbsent(variable, v -> new ArrayList<>()).add(index);
            if (!knownVariableNames.contains(variable.fullyQualifiedName()) && !seenFirstTime.containsKey(variable)) {
                seenFirstTime.put(variable, index);
            }
        }

        @Override
        public void afterExpression(Expression e) {
            if (e instanceof Negation || e instanceof UnaryOperator u && u.parameterizedType().isBoolean()) {
                --inNegative;
            }
        }

        @Override
        public boolean beforeExpression(Expression e) {
            if (e instanceof Lambda lambda) {
                // we plan to catch all variables that we already know, but not to introduce NEW variables
                handleAnonymousMethod(lambda.methodBody(), lambda.parameters(), Set.of(), Set.of(lambda.methodInfo().typeInfo()));
                return false;
            }
            if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) {
                Set<FieldInfo> localFields = new HashSet<>(cc.anonymousClass().fields());
                Set<TypeInfo> typeHierarchy = new HashSet<>();
                typeHierarchy.add(cc.anonymousClass());
                typeHierarchy.addAll(cc.anonymousClass().superTypesExcludingJavaLangObject());
                typeHierarchy.add(runtime.objectTypeInfo());
                cc.anonymousClass().superTypesExcludingJavaLangObject().forEach(st -> localFields.addAll(st.fields()));
                cc.anonymousClass().fields().forEach(f -> {
                    if (f.initializer() != null && !f.initializer().isEmpty()) {
                        Block block = runtime.newBlockBuilder()
                                .addStatement(runtime.newExpressionAsStatement(f.initializer())).build();
                        handleAnonymousMethod(block, List.of(), localFields, typeHierarchy);
                    }
                });
                cc.anonymousClass().constructorAndMethodStream()
                        .forEach(mi -> handleAnonymousMethod(mi.methodBody(), mi.parameters(), localFields, typeHierarchy));
                return true; // so that the arguments get processed; the current visitor ignores the anonymous class
            }
            if (e instanceof Negation || e instanceof UnaryOperator u && u.parameterizedType().isBoolean()) {
                ++inNegative;
                return true;
            }

            if (e instanceof VariableExpression ve) {
                Variable variable = ve.variable();
                markRead(variable);
                if (ve.variable() instanceof DependentVariable dv) {
                    if (!(dv.indexExpression() instanceof VariableExpression)) dv.indexExpression().visit(this);
                    if (!(dv.arrayExpression() instanceof VariableExpression)) dv.arrayExpression().visit(this);
                } else if (ve.variable() instanceof FieldReference fr && !(fr.scope() instanceof VariableExpression)) {
                    fr.scope().visit(this);
                }
                return false;
            }

            if (e instanceof InstanceOf instanceOf && instanceOf.patternVariable() != null) {
                seenFirstTime.put(instanceOf.patternVariable(), index);
                assignedAdd(instanceOf.patternVariable());
                String scope = computePatternVariableScope();
                restrictToScope.put(instanceOf.patternVariable(), scope);
            }
            if (e instanceof Assignment a) {
                assignedAdd(a.variableTarget());
                if (a.assignmentOperator() != null) {
                    // +=, ++, ...
                    markRead(a.variableTarget());
                }
                if (a.variableTarget() instanceof DependentVariable dv) {
                    dv.indexExpression().visit(this);
                    dv.arrayExpression().visit(this);
                    if (dv.arrayVariable() != null) {
                        markRead(dv.arrayVariable());
                    }
                    if (dv.indexVariable() != null) {
                        markRead(dv.indexVariable());
                    }
                } else if (a.variableTarget() instanceof FieldReference fr) {
                    fr.scope().visit(this);
                }
                a.value().visit(this);
                return false;
            }
            if (e instanceof MethodCall mc) {
                Value.FieldValue getSet = mc.methodInfo().getSetField();
                if (getSet.field() != null && !getSet.setter()) {
                    // getter
                    if (markGetterRecursion(mc) != null) {
                        return false; // and getters do not have modified components
                    }
                }
                // also, simply ensure that modified component variables exist
                Value.VariableBooleanMap modifiedComponents = mc.methodInfo().analysis()
                        .getOrDefault(PropertyImpl.MODIFIED_COMPONENTS_METHOD, ValueImpl.VariableBooleanMapImpl.EMPTY);
                modifiedComponents.map().keySet().forEach(v -> {
                    if (v instanceof FieldReference fr) {
                        FieldReference newFr = runtime.newFieldReference(fr.fieldInfo(), mc.object(), fr.parameterizedType());
                        markRead(newFr);
                    }
                });
            }
            return true;
        }

        private void handleAnonymousMethod(Block methodBody,
                                           List<ParameterInfo> parameters,
                                           Set<FieldInfo> localFields,
                                           Set<TypeInfo> typeHierarchy) {
            Set<Variable> definedInLambda = new HashSet<>(parameters);
            methodBody.visit(v -> {
                if (v instanceof Statement s) {
                    if (v instanceof LocalVariableCreation lvc) {
                        definedInLambda.addAll(lvc.newLocalVariables());
                    } else if (!(v instanceof Block)) {
                        Visitor sub = new Visitor(index, knownVariableNames, s);
                        s.visit(sub);
                        sub.read.keySet().stream()
                                .filter(vv -> !(vv instanceof FieldReference fr && localFields.contains(fr.fieldInfo())))
                                .filter(vv -> !(vv instanceof This thisVar && typeHierarchy.contains(thisVar.typeInfo())))
                                .filter(vv -> !definedInLambda.contains(vv)).forEach(this::markRead);
                    }
                }
                return true;
            });
        }

        // NOTE: pretty similar code in Factory.getterVariable(MethodCall methodCall);
        private Variable markGetterRecursion(MethodCall mc) {
            Value.FieldValue getSet = mc.methodInfo().getSetField();
            if (getSet.field() != null && !getSet.setter()) {
                Variable object;

                Expression unwrappedObject = unwrap(mc.object());
                if (unwrappedObject instanceof VariableExpression ve) {
                    object = ve.variable();
                    markRead(object);
                } else if (unwrappedObject instanceof MethodCall mc2) {
                    object = markGetterRecursion(mc2);
                } else {
                    object = null;
                }
                if (object != null) {
                    FieldReference fr;
                    ParameterizedType type = mc.concreteReturnType();
                    if (mc.parameterExpressions().isEmpty()) {
                        fr = runtime.newFieldReference(getSet.field(), runtime.newVariableExpression(object), type);
                        accessorSeenFirstTime.put(fr, index);
                        markRead(fr.scope());
                    } else {
                        ParameterizedType arrayType = type.copyWithArrays(type.arrays() + 1);
                        fr = runtime.newFieldReference(getSet.field(), runtime.newVariableExpression(object), arrayType);
                        accessorSeenFirstTime.put(fr, index);
                        markRead(fr.scope());
                        assert mc.methodInfo().parameters().get(0).parameterizedType().isInt();
                        Expression indexExpression = mc.parameterExpressions().get(0);
                        DependentVariable dv = runtime.newDependentVariable(fr, type, indexExpression);
                        accessorSeenFirstTime.put(dv, index);
                        markRead(indexExpression);
                    }
                    return fr;
                }
            }
            return null;
        }

        // see TestCast,2
        private Expression unwrap(Expression object) {
            if (object instanceof EnclosedExpression ee) return unwrap(ee.inner());
            if (object instanceof Cast cast && cast.expression() instanceof VariableExpression ve) {
                return unwrap(ve);
            }
            return object;
        }

        private void markRead(Variable variable) {
            variable.variableStreamDescend().forEach(v -> {
                read.computeIfAbsent(v, vv -> new ArrayList<>()).add(index);
                if (!knownVariableNames.contains(v.fullyQualifiedName()) && !seenFirstTime.containsKey(v)) {
                    seenFirstTime.put(v, index);
                }
            });
        }

        private void markRead(Expression expression) {
            expression.variableStreamDescend().forEach(v -> {
                read.computeIfAbsent(v, vv -> new ArrayList<>()).add(index);
                if (!knownVariableNames.contains(v.fullyQualifiedName()) && !seenFirstTime.containsKey(v)) {
                    seenFirstTime.put(v, index);
                }
            });
        }

        /*
            create local variables 'YYY y = x' for every sub-expression x instanceof YYY y

            the scope of the variable is determined as follows:
            (1) if the expression is an if-statement, without else: pos = then block, neg = rest of current block
            (2) if the expression is an if-statement with else: pos = then block, neg = else block
            (3) otherwise, only the current expression is accepted (we set to then block)
            ==> positive: always then block
            ==> negative: either else or rest of block
        */

        private String computePatternVariableScope() {
            boolean isNegative = (inNegative % 2) == 1;
            String stmtIndex = statement.source().index();
            if (statement instanceof IfElseStatement ie) {
                if (isNegative) {
                    if (ie.elseBlock().isEmpty()) {
                        // rest of the current block
                        return Util.beyond(stmtIndex);
                    }
                    return stmtIndex + ".1.0";
                }
            }
            // then block, or restrict to current statement
            return stmtIndex + ".0.0";
        }

        public Visitor withIndex(String s) {
            this.index = s;
            return this;
        }
    }
}
