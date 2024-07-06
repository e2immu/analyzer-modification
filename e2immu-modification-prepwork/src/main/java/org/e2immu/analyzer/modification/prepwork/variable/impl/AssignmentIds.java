package org.e2immu.analyzer.modification.prepwork.variable.impl;

import org.e2immu.analyzer.modification.prepwork.Util;

import java.util.Iterator;
import java.util.TreeSet;
import java.util.stream.Stream;

public class AssignmentIds implements Comparable<AssignmentIds>, Iterable<String> {
    public static AssignmentIds NOT_YET_ASSIGNED = new AssignmentIds();

    private final TreeSet<String> ids;

    private AssignmentIds() {
        this.ids = new TreeSet<>();
    }

    public AssignmentIds(String id) {
        this.ids = new TreeSet<>();
        ids.add(id);
    }

    public AssignmentIds(String assignmentId, Stream<AssignmentIds> stream) {
        this.ids = new TreeSet<>();
        this.ids.add(assignmentId);
        stream.forEach(a -> ids.addAll(a.ids));
    }

    public AssignmentIds(String assignmentId, AssignmentIds previous) {
        this.ids = new TreeSet<>();
        this.ids.add(assignmentId);
        this.ids.addAll(previous.ids);
    }

    private AssignmentIds(TreeSet<String> ids) {
        this.ids = ids;
    }

    public boolean hasNotYetBeenAssigned() {
        return ids.isEmpty();
    }

    public String getLatestAssignment() {
        return ids.isEmpty() ? "-" : ids.floor("~");
    }

    public String getLatestAssignmentNullWhenEmpty() {
        return ids.isEmpty() ? null : ids.floor("~");
    }

    @Override
    public int compareTo(AssignmentIds o) {
        return getLatestAssignment().compareTo(o.getLatestAssignment());
    }

    public String getLatestAssignmentIndex() {
        return ids.isEmpty() ? "-" : Util.stripStage(getLatestAssignment());
    }

    public AssignmentIds merge(AssignmentIds other) {
        TreeSet<String> ts = new TreeSet<>(ids);
        ts.addAll(other.ids);
        return new AssignmentIds(ts);
    }

    @Override
    public String toString() {
        return String.join(",", ids);
    }

    public String getEarliestAssignmentIndex() {
        return ids.isEmpty() ? "-" : ids.ceiling("-");
    }

    @Override
    public Iterator<String> iterator() {
        // in this way, it is read-only
        return ids.stream().iterator();
    }
}

