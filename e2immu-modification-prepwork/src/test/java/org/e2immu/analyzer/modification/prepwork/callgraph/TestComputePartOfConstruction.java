package org.e2immu.analyzer.modification.prepwork.callgraph;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.FieldInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.e2immu.analyzer.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestComputePartOfConstruction extends CommonTest {

    @DisplayName("part of construction of CallGraph test 3")
    @Test
    public void test3b() {
        TypeInfo X = javaInspector.parse(TestCallGraph.INPUT3);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        Value.SetOfInfo setOfInfo = X.analysis().getOrNull(PART_OF_CONSTRUCTION, ValueImpl.SetOfInfoImpl.class);
        assertNotNull(setOfInfo);
        assertEquals("[a.b.X.X(int), a.b.X.initList(int)]",
                setOfInfo.infoSet().stream().map(Object::toString).sorted().toList().toString());

        FieldInfo list = X.getFieldByName("list", true);
        assertSame(TRUE, list.analysis().getOrDefault(PropertyImpl.FINAL_FIELD, FALSE));
    }
}
