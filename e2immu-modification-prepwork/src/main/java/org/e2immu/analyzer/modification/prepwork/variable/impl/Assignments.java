package org.e2immu.analyzer.modification.prepwork.variable.impl;


import org.e2immu.analyzer.modification.prepwork.StatementIndex;
import org.e2immu.analyzer.modification.prepwork.Util;
import org.e2immu.analyzer.modification.prepwork.variable.ReturnVariable;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.language.cst.api.statement.*;

import java.util.*;
import java.util.stream.Stream;

public class Assignments {

    public boolean hasNotYetBeenAssigned() {
        return assignmentIndices.length == 0;
    }

    private final String indexOfDefinition;
    private final String[] assignmentIndices;

    public Assignments(String indexOfDefinition) {
        this.indexOfDefinition = indexOfDefinition;
        assignmentIndices = new String[0];
    }

    private Assignments(Assignments previous, String[] assignmentIndices) {
        this.assignmentIndices = assignmentIndices;
        this.indexOfDefinition = previous.indexOfDefinition;
    }

    private Assignments(Assignments previous, List<String> indices) {
        this.assignmentIndices = Stream.concat(Arrays.stream(previous.assignmentIndices),
                indices.stream()).toArray(String[]::new);
        this.indexOfDefinition = previous.indexOfDefinition;
    }

    public int size() {
        return assignmentIndices.length;
    }

    @Override
    public String toString() {
        return "D:" + indexOfDefinition + ", A:" + Arrays.toString(assignmentIndices);
    }

    public static Assignments newAssignment(List<String> indices, Assignments previous) {
        return new Assignments(previous, indices);
    }

    // for testing
    public static Assignments newAssignment(String index, Assignments previous) {
        return new Assignments(previous, List.of(index));
    }

    public interface CompleteMerge {
        void add(String subIndex);

        boolean complete();
    }

    static class CompleteMergeByCounting implements CompleteMerge {
        int count;
        final int target;

        CompleteMergeByCounting(int target) {
            this.target = target;
        }

        @Override
        public void add(String subIndex) {
            ++count;
        }

        @Override
        public boolean complete() {
            return count >= target;
        }
    }

    static class CompleteMergeForTry implements CompleteMerge {
        int count;
        boolean hitFinally;
        final int target;
        final String indexFinally;

        CompleteMergeForTry(int target, String indexFinally) {
            this.target = target;
            this.indexFinally = indexFinally;
        }

        @Override
        public void add(String subIndex) {
            if (subIndex.equals(indexFinally)) {
                hitFinally = true;
            } else {
                ++count;
            }
        }

        @Override
        public boolean complete() {
            return hitFinally || count >= target;
        }
    }

    /*
    There are different "modes" depending on the statement.

    1- If, For, ForEach, While: 1 block, conditionally
    2- IfElse: either block 1, or block 2
    3- Synchronized, Block, Do-While, While(true), For(..;;..): 1 block, unconditionally
    4- Try: 1 block unconditionally, n blocks conditionally, exclusive, 0-1 block unconditionally
     */
    public static Assignments mergeBlocks(String index,
                                          CompleteMerge completeMerge,
                                          Map<String, Assignments> assignmentsInBlocks,
                                          Map<String, List<Assignments>> assignmentsToAddFromFallThrough) {
        List<String> list = new ArrayList<>();
        Assignments aFirst = null;
        for (Map.Entry<String, Assignments> entry : assignmentsInBlocks.entrySet()) {
            Assignments a = entry.getValue();
            String subIndex = entry.getKey();

            List<Assignments> fromFallThrough = assignmentsToAddFromFallThrough.get(subIndex);
            if (fromFallThrough != null) {
                fromFallThrough.forEach(ff -> list.addAll(Arrays.asList(ff.assignmentIndices)));
            }

            boolean haveAssignment = false;
            for (String s : a.assignmentIndices) {
                if (s.compareTo(index) >= 0) {
                    haveAssignment |= Util.atSameLevel(subIndex, s);
                }
                list.add(s);
            }
            if (aFirst == null) aFirst = a;
            if (haveAssignment) {
                // nothing was added, so no assignment
                completeMerge.add(subIndex);
            }
        }
        assert aFirst != null;
        if (completeMerge.complete()) {
            String mergeIndex = index + StatementIndex.MERGE;
            list.add(mergeIndex);
        }
        return new Assignments(aFirst, list.stream().distinct().sorted().toArray(String[]::new));
    }

    public static CompleteMerge assignmentsRequiredForMerge(Statement statement,
                                                            Map<String, VariableData> lastOfEachStatement,
                                                            ReturnVariable returnVariable,
                                                            boolean noBreakStatements) {
        int blocksWithReturn;
        if (returnVariable == null) {
            // when we're computing this for the return variable, no corrections allowed
            blocksWithReturn = 0;
        } else {
            blocksWithReturn = (int) lastOfEachStatement.entrySet().stream()
                    .filter(e -> {
                        String firstStatementIndex = e.getKey();
                        VariableData vd = e.getValue();
                        VariableInfoContainer vic = vd.variableInfoContainerOrNull(returnVariable.fullyQualifiedName());
                        if (vic == null) return false;
                        VariableInfo vi = vic.best();
                        return vi.assignments().lastAssignmentIsMergeInBlockOf(firstStatementIndex);
                    })
                    .count();
        }
        if (statement instanceof TryStatement ts) {
            int target = 1 + ts.catchClauses().size() - blocksWithReturn;
            int finallyIndex = (ts.resources().isEmpty() ? 1 : 2) + ts.catchClauses().size();
            boolean haveFinally = !ts.finallyBlock().isEmpty();
            String haveFinallyIndex = haveFinally
                    ? ts.source().index() + StatementIndex.DOT + finallyIndex + StatementIndex.DOT_ZERO
                    : null;
            return new CompleteMergeForTry(target, haveFinallyIndex);
        }
        int n;
        if (statement instanceof IfElseStatement) {
            n = 2;
        } else if (statement instanceof SwitchStatementNewStyle) {
            n = (int) statement.subBlockStream().count();
        } else if (statement instanceof Block
                   || statement instanceof SynchronizedStatement
                   || statement instanceof DoStatement && !statement.expression().isBoolValueTrue()) {
            n = 1;
        } else if (statement instanceof LoopStatement) {
            if ((statement.expression().isEmpty() || statement.expression().isBoolValueTrue())
                && noBreakStatements) {
                n = 0; // we'll always return, or never continue
            } else {
                n = Integer.MAX_VALUE;// there can be no merge
            }
        } else if (statement instanceof SwitchStatementOldStyle) {
            n = lastOfEachStatement.size();
        } else {
            throw new UnsupportedOperationException("NYI");
        }
        return new CompleteMergeByCounting(n - blocksWithReturn);
    }

    /*
    first statement: 1.0.0, last assignment: 1.0.5-> OK, 1.0.4.0.1 not OK!
     */
    public boolean lastAssignmentIsMergeInBlockOf(String firstStatementIndex) {
        if (assignmentIndices.length == 0) return false;
        String last = assignmentIndices[assignmentIndices.length - 1];
        return Util.atSameLevel(firstStatementIndex, last);
    }

    public String indexOfDefinition() {
        return indexOfDefinition;
    }

    /*
    has the variable been assigned at this index?
     */
    public boolean hasAValueAt(String index) {
        if (indexOfDefinition.compareTo(index) > 0) {
            throw new UnsupportedOperationException("not yet defined");
        }
        if (assignmentIndices.length == 0) return false;
        int pos = Arrays.binarySearch(assignmentIndices, index);
        if (pos >= 0) return true; // we're hitting a definition point
        int insert = -(pos + 1);
        if (insert == 0) return false;
        for (int k = insert - 1; k >= 0; k--) {
            String s = assignmentIndices[k];
            if (StatementIndex.seenBy(s, index)) return true;
        }
        return false;
    }

    /*
    do we have an assignment after 'after', seen by 'seenBy'?


    examples:
    0 - 1 - 2 --> yes
    0 - 1.0.0 - 1.1.0 --> no
    1.1.0 - 2 - 2.0.1 --> yes
     */
    public boolean hasBeenAssignedAfterFor(String after, String seenBy) {
        if (indexOfDefinition.compareTo(after) >= 0) {
            throw new UnsupportedOperationException("must be defined earlier");
        }
        if (assignmentIndices.length == 0) return false;
        int pos = Arrays.binarySearch(assignmentIndices, after);
        int start;
        if (pos >= 0) {
            start = pos + 1;
        } else {
            start = -(pos + 1);
        }
        for (int i = start; i < assignmentIndices.length; i++) {
            if (StatementIndex.seenBy(assignmentIndices[i], seenBy)) return true;
        }
        return false;
    }

    public List<String> mergeIndices() {
        return Arrays.stream(assignmentIndices).map(Util::stripStage).toList();
    }

    public Stream<String> definitionAndAssignmentsBefore(String upper) {
        if (indexOfDefinition.compareTo(upper) >= 0) return Stream.of();
        return Stream.concat(Stream.of(indexOfDefinition),
                Arrays.stream(assignmentIndices).takeWhile(s -> s.compareTo(upper) < 0));
    }

    public boolean between(String fromIncl, String toExcl) {
        if (fromIncl.compareTo(toExcl) >= 0) return false;

        int pos0 = Arrays.binarySearch(assignmentIndices, fromIncl);
        int p0 = pos0 >= 0 ? pos0 : -(pos0 + 1);
        if (p0 >= assignmentIndices.length) return false;
        String s0 = assignmentIndices[p0];
        if (s0.compareTo(fromIncl) < 0) return false;
        return s0.compareTo(toExcl) < 0;
    }
}

