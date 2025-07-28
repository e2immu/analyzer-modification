package org.e2immu.analyzer.modification.prepwork.hcs;

import org.e2immu.analyzer.modification.prepwork.CommonTest;
import org.e2immu.analyzer.modification.prepwork.hct.ComputeHiddenContent;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestTypeParameterOfParent extends CommonTest {

    @Language("java")
    private static final String INPUT5 = """
            package a.b;
            
            import java.util.ArrayList;
            class JavaParser {
                NodeScope currentNodeScope;
                interface Node { }
                @SuppressWarnings("serial")
                class NodeScope extends ArrayList<Node> {
                    NodeScope parentScope;
            
                    NodeScope() {
                        this.parentScope = JavaParser.this.currentNodeScope;
                        JavaParser.this.currentNodeScope = this;
                    }
            
                    public NodeScope clone() {
                        NodeScope clone = (NodeScope) super.clone();
                        if (parentScope != null) {
                            clone.parentScope = parentScope.clone();
                        }
                        return clone;
                    }
                }
            }
            """;

    @DisplayName("hct still has formal type parameter")
    @Test
    public void test5() {
        ComputeHiddenContent chc = new ComputeHiddenContent(javaInspector.runtime());
        TypeInfo javaParser = javaInspector.parse(INPUT5);
        HiddenContentTypes hctJp = chc.compute(javaParser);
        assertEquals("0=NodeScope", hctJp.detailedSortedTypes());

        TypeInfo nodeScope = javaParser.findSubType("NodeScope");
        HiddenContentTypes hct = chc.compute(nodeScope);
        assertEquals("0=NodeScope, 1=Node", hct.detailedSortedTypes());
        assertEquals("E=1, Node=1, NodeScope=0", hct.detailedSortedTypeToIndex());
        MethodInfo clone = nodeScope.findUniqueMethod("clone", 0);
        HiddenContentSelector hcsClone = new ComputeHCS(runtime).doHiddenContentSelector(clone);
        assertEquals("0=*", hcsClone.detailed());

        ParameterizedType formalObject = nodeScope.asParameterizedType();
        HiddenContentSelector hcsCloneObject = HiddenContentSelector.selectAll(hct, formalObject);
        assertEquals("0=*,1=1", hcsCloneObject.detailed());
    }
}
