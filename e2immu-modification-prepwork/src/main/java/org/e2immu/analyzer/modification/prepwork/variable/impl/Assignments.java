package org.e2immu.analyzer.modification.prepwork.variable.impl;


import org.e2immu.analyzer.modification.prepwork.variable.VariableInfoContainer;

import java.util.*;
import java.util.stream.Stream;

public class Assignments {
    public static Assignments NOT_YET_ASSIGNED = new Assignments(List.of());

    public boolean hasNotYetBeenAssigned() {
        return assignments.isEmpty();
    }

    public record I(String index, List<String> actualAssignmentIndices) {
    }

    private final List<I> assignments;

    private Assignments(List<I> assignments) {
        this.assignments = assignments;
    }

    private Assignments(List<I> previous, I i) {
        this.assignments = Stream.concat(previous.stream(), Stream.of(i)).toList();
    }

    public static Assignments newAssignment(String index, Assignments previous) {
        List<String> allActualAssignmentIndices = previous.assignments.isEmpty()
                ? List.of(index)
                : Stream.concat(previous.assignments.get(previous.assignments.size() - 1).actualAssignmentIndices.stream(),
                Stream.of(index)).toList();
        I i = new I(index, allActualAssignmentIndices);
        return new Assignments(previous.assignments, i);
    }

    public static Assignments mergeBlocks(String index, List<Assignments> assignmentsInBlocks) {
        boolean doMerge = true;
        List<I> unrelatedToMerge = new ArrayList<>();
        List<I> inSubBlocks = new ArrayList<>();
        boolean first = true;
        for (Assignments a : assignmentsInBlocks) {
            int size = inSubBlocks.size();
            if (first) {
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
            if (inSubBlocks.size() == size) {
                // nothing was added, so no assignment
                doMerge = false;
            }
        }
        if (doMerge) {
            String mergeIndex = index + ":M";
            List<String> actual = inSubBlocks.stream().flatMap(i -> i.actualAssignmentIndices.stream()).distinct().toList();
            I merge = new I(mergeIndex, actual);
            return new Assignments(unrelatedToMerge, merge);
        }
        // just concat everything...
        return new Assignments(Stream.concat(unrelatedToMerge.stream(), inSubBlocks.stream()).toList());
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
}

