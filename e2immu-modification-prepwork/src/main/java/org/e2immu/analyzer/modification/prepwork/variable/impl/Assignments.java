package org.e2immu.analyzer.modification.prepwork.variable.impl;


import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;
import org.e2immu.language.cst.api.statement.*;

import java.util.*;
import java.util.stream.Stream;

public class Assignments {

    public boolean hasNotYetBeenAssigned() {
        return assignments.isEmpty();
    }

    public record I(String index, List<String> actualAssignmentIndices) {
        @Override
        public String toString() {
            return index + "=" + actualAssignmentIndices;
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
    public static Assignments mergeBlocks(String index, int assignmentsRequiredForMerge, List<Assignments> assignmentsInBlocks) {
        int blocksWithAssignment = 0;
        List<I> unrelatedToMerge = new ArrayList<>();
        List<I> inSubBlocks = new ArrayList<>();
        boolean first = true;
        Assignments aFirst = null;
        for (Assignments a : assignmentsInBlocks) {
            int size = inSubBlocks.size();
            if (first) {
                aFirst = a;
                for (I i : a.assignments) {
                    if (i.index.compareTo(index) < 0) unrelatedToMerge.add(i);
                    else inSubBlocks.add(i);
                }
                first = false;
            } else {
                for (I i : a.assignments) {
                    if (i.index.compareTo(index) > 0) inSubBlocks.add(i);
                }
            }
            boolean haveAssignment = inSubBlocks.size() > size;
            if (haveAssignment) {
                // nothing was added, so no assignment
                ++blocksWithAssignment;
            }
        }
        assert aFirst != null;
        if (blocksWithAssignment == assignmentsRequiredForMerge) {
            String mergeIndex = index + ":M";
            List<String> actual = inSubBlocks.stream().flatMap(i -> i.actualAssignmentIndices.stream()).distinct().toList();
            I merge = new I(mergeIndex, actual);
            return new Assignments(aFirst.indexOfDefinition, unrelatedToMerge, merge);
        }
        // just concat everything...
        return new Assignments(aFirst.indexOfDefinition,
                Stream.concat(unrelatedToMerge.stream(), inSubBlocks.stream()).toList());
    }

    public static int assignmentsRequiredForMerge(Statement statement) {
        if (statement instanceof IfElseStatement) {
            return 2;
        }
        if (statement instanceof DoStatement
            || statement instanceof WhileStatement && statement.expression().isBoolValueTrue()
            || statement instanceof ForStatement && (statement.expression().isBoolValueTrue() || statement.expression().isEmpty())
            || statement instanceof Block
            || statement instanceof SynchronizedStatement) {
            return 1;
        }
        return -1; // there can be no merge
    }

    public List<I> assignments() {
        return assignments;
    }

    public String getLatestAssignmentIndex() {
        return assignments.isEmpty()
                ? VariableInfoContainer.NOT_YET_READ
                : assignments.get(assignments.size() - 1).index;
    }

    public I latest() {
        return assignments.isEmpty() ? null : assignments.get(assignments.size() - 1);
    }

    public String indexOfDefinition() {
        return indexOfDefinition;
    }
}

