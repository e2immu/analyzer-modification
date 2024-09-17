package org.e2immu.analyzer.modification.prepwork.variable.impl;


import org.e2immu.analyzer.modification.prepwork.Util;
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

    /*
    There are different "modes" depending on the statement.

    1- If, For, ForEach, While: 1 block, conditionally
    2- IfElse: either block 1, or block 2
    3- Synchronized, Block, Do-While, While(true), For(..;;..): 1 block, unconditionally
    4- Try: 1 block unconditionally, n blocks conditionally, exclusive, 0-1 block unconditionally
     */
    public static Assignments mergeBlocks(String index,
                                          int assignmentsRequiredForMerge,
                                          Map<String, Assignments> assignmentsInBlocks) {
        int blocksWithAssignment = 0;
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
                ++blocksWithAssignment;
            }
        }
        assert aFirst != null;
        if (blocksWithAssignment == assignmentsRequiredForMerge) {
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

    public static int assignmentsRequiredForMerge(Statement statement) {
        if (statement instanceof IfElseStatement) {
            return 2;
        }
        if (statement instanceof SwitchStatementNewStyle) {
            return (int) statement.subBlockStream().count();
        }
        // NOTE: 'finally' blocks will be handled separately, this is about the main block here
        if (statement instanceof TryStatement
            || statement instanceof WhileStatement && statement.expression().isBoolValueTrue()
            || statement instanceof ForStatement && (statement.expression().isBoolValueTrue() || statement.expression().isEmpty())
            || statement instanceof Block
            || statement instanceof SynchronizedStatement
            || statement instanceof DoStatement) {
            return 1;
        }
        if (statement instanceof LoopStatement) {
            return -1; // there can be no merge
        }
        if (statement instanceof SwitchStatementOldStyle) {
            throw new UnsupportedOperationException("Should be handled separately, too complex");
        }
        throw new UnsupportedOperationException("NYI");
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
                if(withoutM.equals(index)) {
                    return true;
                }
                if(index.startsWith(withoutM)) {
                    // we may have to check with the individual definition points
                    for (String aai : i.actualAssignmentIndices) {
                        if (Util.inScopeOf(aai, index)) return true;
                    }
                }
            }
        }
        return false;
    }

}

