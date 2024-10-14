package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.e2immu.language.cst.api.variable.This;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_METHOD;
import static org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl.STATIC_VALUES_PARAMETER;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.junit.jupiter.api.Assertions.*;

public class TestLinkThis extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Iterator;
            import java.util.function.Function;
            class X {
            
                interface LoopData {
                    LoopData next();
                }
            
                static class LoopDataImpl {
                    private Iterator<?> loop;
                    private Object loopValue;
                    private Function<LoopData, LoopData> body;
            
                    LoopDataImpl(Iterator<?> loop, Function<LoopData, LoopData> body, Object loopValue) {
                        this.loop = loop;
                        this.loopValue = loopValue;
                        this.body = body;
                    }
            
                    @Override
                    public LoopData next() {
                        if (loop != null) {
                            Object loopValue = loop.next();
                            LoopData nextLd = withLoopValue(loopValue);
                            return body.apply(nextLd);
                        }
                        return null;// body.apply(this);
                    }
            
                    private LoopData withLoopValue(Object loopValue) {
                        return new LoopDataImpl(loop, body, loopValue);
                    }
                }
            }
            """;

    @DisplayName("links between this and self-referencing objects")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo function = javaInspector.compiledTypesManager().get(Function.class);
        MethodInfo apply = function.singleAbstractMethod();
        assertTrue(apply.isModifying());
        ParameterInfo apply0 = apply.parameters().get(0);
        assertTrue(apply0.isModified());

        TypeInfo loopDataImpl = X.findSubType("LoopDataImpl");
        HiddenContentTypes ldiHct = loopDataImpl.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
        assertEquals("0=Iterator, 1=Object, 2=Function, 3=LoopData", ldiHct.detailedSortedTypes());

        This thisVar = runtime.newThis(loopDataImpl);
        FieldInfo loop = loopDataImpl.getFieldByName("loop", true);
        assertFalse(loop.isFinal());
        assertTrue(loop.isPropertyFinal());
        assertSame(ValueImpl.BoolImpl.TRUE, loop.analysis().getOrDefault(PropertyImpl.MODIFIED_FIELD,
                ValueImpl.BoolImpl.FALSE));

        MethodInfo constructor = loopDataImpl.findConstructor(3);
        ParameterInfo c0 = constructor.parameters().get(0);
        assertEquals("loop", c0.simpleName());
        assertEquals("E=this.loop", c0.analysis().getOrDefault(STATIC_VALUES_PARAMETER, StaticValuesImpl.NONE).toString());

        MethodInfo withLoopValue = loopDataImpl.findUniqueMethod("withLoopValue", 1);
        assertEquals("Type a.b.X.LoopDataImpl E=new LoopDataImpl(this.loop,this.body,loopValue)",
                withLoopValue.analysis().getOrDefault(STATIC_VALUES_METHOD, StaticValuesImpl.NONE).toString());
        Value.Independent wlvIndependent = withLoopValue.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT);
        assertTrue(wlvIndependent.isDependent());

        MethodInfo next = loopDataImpl.findUniqueMethod("next", 0);
        {
            Statement s0 = next.methodBody().statements().get(0);
            {
                Statement s000 = s0.block().statements().get(0);
                VariableData vd000 = VariableDataImpl.of(s000);
                VariableInfo vi000This = vd000.variableInfo(thisVar);
                assertEquals("", vi000This.linkedVariables().toString());
                VariableInfo vi000LoopValue = vd000.variableInfo("loopValue");
                assertEquals("*-4-0:loop", vi000LoopValue.linkedVariables().toString());
            }
            {
                Statement s001 = s0.block().statements().get(1);
                VariableData vd001 = VariableDataImpl.of(s001);
                VariableInfo vi001This = vd001.variableInfo(thisVar);
                VariableInfo vi001NextLd = vd001.variableInfo("nextLd");
                assertEquals("Type a.b.X.LoopDataImpl ? ? ?", vi001NextLd.staticValues().toString());

                assertEquals("-2-:this", vi001NextLd.linkedVariables().toString());
                assertEquals("-2-:nextLd", vi001This.linkedVariables().toString());
            }
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0This = vd0.variableInfo(thisVar);
            assertEquals("F-4-0;1:body,FM-2-*M:nextLd", vi0This.linkedVariables().toString());
        }
    }
}
