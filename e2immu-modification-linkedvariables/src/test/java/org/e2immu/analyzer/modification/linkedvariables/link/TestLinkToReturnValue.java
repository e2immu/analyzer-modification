package org.e2immu.analyzer.modification.linkedvariables.link;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestLinkToReturnValue extends CommonTest {

    @Language("java")
    public static final String INPUT1 = """
            package a.b;
            import java.lang.annotation.Annotation;
            class X {
                public static void method(Annotation annotation) {
                    Class<? extends Annotation> annotationClazz = annotation.annotationType();
                }
            }
            """;


    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.doPrimaryType(X, ao);

        TypeInfo clazz = javaInspector.compiledTypesManager().get(Class.class);
        assertTrue(clazz.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE).isImmutable());

        MethodInfo method = X.findUniqueMethod("method", 1);
        Statement s0 = method.methodBody().statements().get(0);
        VariableData vd0 = VariableDataImpl.of(s0);
        VariableInfo viAc0 = vd0.variableInfo("annotationClazz");
        // an immutable type cannot be linked to anything
        assertEquals("", viAc0.linkedVariables().toString());
    }
}
