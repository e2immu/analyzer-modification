package org.e2immu.analyzer.modification.prepwork.variable;

import org.e2immu.analyzer.modification.prepwork.variable.impl.Assignments;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestAssignments {

    @Test
    public void test() {
        Assignments a = new Assignments("0", false);
        assertEquals(0, a.assignments().size());
        Assignments a0 = Assignments.newAssignment("0", a);
        assertEquals("[0]", a0.latest().actualAssignmentIndices().toString());

        Assignments a101 = Assignments.newAssignment("1.0.1", a0);
        assertEquals(a0.latest(), a101.assignments().get(0));
        assertEquals("[0, 1.0.1]", a101.latest().actualAssignmentIndices().toString());

        Assignments a102 = Assignments.newAssignment("1.0.2", a101);
        assertEquals(3, a102.assignments().size());
        assertEquals("[0, 1.0.1, 1.0.2]", a102.latest().actualAssignmentIndices().toString());

        // there have been assignments in 1.0.x, but none in 1.1.x -> keep as is
        Assignments a1 = Assignments.mergeBlocks("1", List.of(a102, a0));
        assertEquals(3, a1.assignments().size());
        assertSame(a0.latest(), a1.assignments().get(0));
        assertSame(a101.latest(), a1.assignments().get(1));
        assertSame(a102.latest(), a1.assignments().get(2));

        Assignments a110 = Assignments.newAssignment("1.1.0", a0);
        assertEquals(a0.latest(), a110.assignments().get(0));
        assertEquals("[0, 1.1.0]", a110.latest().actualAssignmentIndices().toString());

        // in this situation, there has been a full merge. we drop information about sub-blocks in the main
        // array, but store it in actualAssignmentIndices
        Assignments b1 = Assignments.mergeBlocks("1", List.of(a102, a110));
        assertEquals(2, b1.assignments().size());
        Assignments.I b1i1 = b1.assignments().get(1);
        assertEquals("1:M", b1i1.index());
        assertEquals("[0, 1.0.1, 1.0.2, 1.1.0]", b1i1.actualAssignmentIndices().toString());
    }
}
