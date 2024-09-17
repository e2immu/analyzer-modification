package org.e2immu.analyzer.modification.prepwork.variable.impl;


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
        return assignments.isEmpty();
    }

    public record I(String index, List<String> actualAssignmentIndices) implements Comparable<I> {
        @Override
        public String toString() {
            return index + "=" + actualAssignmentIndices;
        }

        @Override
        public int compareTo(I o) {
            return index.compareTo(o.index);
        }
    }

    private final String indexOfDefinition;
    private final List<I> assignments;

    public Assignments(String indexOfDefinition) {
        this.indexOfDefinition = indexOfDefinition;
        assignments = List.of();
    }

    private Assignments(String indexOfDefinition, List<I> assignments) {
        this.assignments = assignments;
        this.indexOfDefinition = indexOfDefinition;
    }

    private Assignments(String indexOfDefinition, List<I> previous, I i) {
        this.assignments = Stream.concat(previous.stream(), Stream.of(i)).toList();
        this.indexOfDefinition = indexOfDefinition;
    }

    @Override
    public String toString() {
        return "D:" + indexOfDefinition + ", A:" + assignments.toString();
    }

    public static Assignments newAssignment(String index, Assignments previous) {
        List<String> allActualAssignmentIndices = previous.assignments.isEmpty()
                ? List.of(index)
                : Stream.concat(previous.assignments.get(previous.assignments.size() - 1).actualAssignmentIndices.stream(),
                Stream.of(index)).toList();
        I i = new I(index, allActualAssignmentIndices);
        return new Assignments(previous.indexOfDefinition, previous.assignments, i);
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
                                          Map<String, Assignments> assignmentsInBlocks) {
        List<I> unrelatedToMerge = new ArrayList<>();
        List<I> inSubBlocks = new ArrayList<>();
        boolean first = true;
        Assignments aFirst = null;
        for (Map.Entry<String, Assignments> entry : assignmentsInBlocks.entrySet()) {
            Assignments a = entry.getValue();
            String subIndex = entry.getKey();
            boolean haveAssignment = false;
            if (first) {
                aFirst = a;
                for (I i : a.assignments) {
                    if (i.index.compareTo(index) < 0) unrelatedToMerge.add(i);
                    else {
                        haveAssignment |= Util.atSameLevel(subIndex, i.index);
                        inSubBlocks.add(i);
                    }
                }
                first = false;
            } else {
                for (I i : a.assignments) {
                    if (i.index.compareTo(index) > 0) {
                        haveAssignment |= Util.atSameLevel(subIndex, i.index);
                        inSubBlocks.add(i);
                    }
                }
            }
            if (haveAssignment) {
                // nothing was added, so no assignment
                completeMerge.add(subIndex);
            }
        }
        assert aFirst != null;
        if (completeMerge.complete()) {
            String mergeIndex = index + ":M";
            List<String> actual = inSubBlocks.stream().flatMap(i -> i.actualAssignmentIndices.stream())
                    .distinct().sorted().toList();
            I merge = new I(mergeIndex, actual);
            return new Assignments(aFirst.indexOfDefinition, unrelatedToMerge, merge);
        }
        // just concat everything...
        return new Assignments(aFirst.indexOfDefinition,
                Stream.concat(unrelatedToMerge.stream(), inSubBlocks.stream()).sorted().toList());
    }

    public static CompleteMerge assignmentsRequiredForMerge(Statement statement,
                                                            Map<String, VariableData> lastOfEachStatement,
                                                            ReturnVariable returnVariable) {
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
            boolean haveFinally = !ts.finallyBlock().isEmpty();
            String haveFinallyIndex = haveFinally ? ts.source().index() + "." + target + ".0" : null;
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
            if (statement.expression().isEmpty() || statement.expression().isBoolValueTrue()) {
                n = 0; // anything goes, infinite loop, so there is no need for a value
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

    private boolean lastAssignmentIsMergeInBlockOf(String firstStatementIndex) {
        if (assignments.isEmpty()) return false;
        I last = assignments.get(assignments.size() - 1);
        return Util.atSameLevel(firstStatementIndex, last.index);
    }

    public List<I> assignments() {
        return assignments;
    }

    public I latest() {
        return assignments.isEmpty() ? null : assignments.get(assignments.size() - 1);
    }

    public String indexOfDefinition() {
        return indexOfDefinition;
    }

    public boolean hasBeenDefined(String index) {
        if (indexOfDefinition.compareTo(index) > 0) {
            throw new UnsupportedOperationException("not yet defined");
        }
        for (I i : assignments) {
            if (Util.inScopeOf(i.index, index)) return true;
            if (i.index.endsWith(":M")) {
                String withoutM = i.index.substring(0, i.index.length() - 2);
                if (withoutM.equals(index)) {
                    return true;
                }
                if (index.startsWith(withoutM)) {
                    // we may have to check with the individual definition points
                    for (String aai : i.actualAssignmentIndices) {
                        if (Util.inScopeOf(aai, index)) return true;
                    }
                }
            }
        }
        return false;
    }

    public List<String> mergeIndices() {
        return assignments.stream()
                .filter(i -> i.index.endsWith(":M"))
                .map(i -> Util.stripStage(i.index)).toList();
    }
}

