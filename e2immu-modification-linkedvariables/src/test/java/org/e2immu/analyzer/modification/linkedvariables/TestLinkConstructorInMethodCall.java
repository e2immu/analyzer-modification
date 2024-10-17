package org.e2immu.analyzer.modification.linkedvariables;

import org.e2immu.analyzer.modification.linkedvariables.lv.StaticValuesImpl;
import org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes;
import org.e2immu.analyzer.modification.prepwork.variable.StaticValues;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.analyzer.modification.prepwork.variable.VariableInfo;
import org.e2immu.analyzer.modification.prepwork.variable.impl.VariableDataImpl;
import org.e2immu.language.cst.api.analysis.Value;
import org.e2immu.language.cst.api.info.*;
import org.e2immu.language.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.HIDDEN_CONTENT_TYPES;
import static org.e2immu.analyzer.modification.prepwork.hct.HiddenContentTypes.NO_VALUE;
import static org.e2immu.language.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static org.e2immu.language.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.junit.jupiter.api.Assertions.*;

public class TestLinkConstructorInMethodCall extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                interface Exit { }
            
                record ExceptionThrown(Exception exception) implements Exit { }
            
                interface LoopData {
                    LoopData withException(Exception e);
                }
            
                static class LoopDataImpl {
                    private Exit exit;
                    LoopDataImpl(Exit exit) {
                        this.exit = exit;
                    }
            
                    @Override
                    public LoopData withException(Exception e) {
                        Exit ee = new ExceptionThrown(e);
                        return new LoopDataImpl(ee);
                    }
                }
            }
            """;

    @DisplayName("construction separate from method call")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo exceptionThrown = X.findSubType("ExceptionThrown");
        {
            HiddenContentTypes exceptionThrownHct = exceptionThrown.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
            assertEquals("0=Exception", exceptionThrownHct.detailedSortedTypes());
            MethodInfo exceptionAccessor = exceptionThrown.findUniqueMethod("exception", 0);
            HiddenContentTypes exceptionAccessorHct = exceptionAccessor.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
            assertEquals("0=Exception - ", exceptionAccessorHct.detailedSortedTypes());

            FieldInfo exception = exceptionThrown.getFieldByName("exception", true);
            assertTrue(exception.isPropertyFinal());

            Value.Immutable etImmutable = exceptionThrown.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
            assertTrue(etImmutable.isMutable());
            assertSame(MUTABLE, etImmutable); // TODO for later, should be FINAL_FIELDS
        }

        TypeInfo loopDataImpl = X.findSubType("LoopDataImpl");
        {
            HiddenContentTypes ldiHct = loopDataImpl.analysis().getOrDefault(HIDDEN_CONTENT_TYPES, NO_VALUE);
            assertEquals("0=Exit", ldiHct.detailedSortedTypes());

            Value.Immutable ldiImmutable = exceptionThrown.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
            assertTrue(ldiImmutable.isMutable());

            MethodInfo withException = loopDataImpl.findUniqueMethod("withException", 1);
            {
                Statement s0 = withException.methodBody().statements().get(0);
                VariableData vd0 = VariableDataImpl.of(s0);
                VariableInfo vi0Ee = vd0.variableInfo("ee");
                assertEquals("Type a.b.X.ExceptionThrown E=new ExceptionThrown(e) this.exception=e", vi0Ee.staticValues().toString());
                // the "M" is there because the hidden content type "Exit" gets a concrete modifiable instance, ExceptionThrown
                // the -2- is there because the objects are of different types (ExceptionThrown vs Exception)
                // the "F" and "*" indicate that a field in ET is linked to the whole Exception object
                assertEquals("0M-2-*M|0-*:e", vi0Ee.linkedVariables().toString());
            }
            {
                Statement s1 = withException.methodBody().lastStatement();
                VariableData vd1 = VariableDataImpl.of(s1);
                VariableInfo vi1Rv = vd1.variableInfo(withException.fullyQualifiedName());
                assertEquals("Type a.b.X.LoopDataImpl E=new LoopDataImpl(ee) this.exit=ee", vi1Rv.staticValues().toString());
                assertEquals("0M-2-*M|0.0-*:e, 0M-2-*M|0-*:ee", vi1Rv.linkedVariables().toString());
            }
        }
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                interface Exit { }
            
                record ExceptionThrown(Exception exception) implements Exit { }
            
                interface LoopData {
                    LoopData withException(Exception e);
                }
            
                static class LoopDataImpl {
                    private final Exit exit;
            
                    LoopDataImpl(Exit exit) {
                        this.exit = exit;
                    }
            
                    @Override
                    public LoopData withException(Exception e) {
                        return new LoopDataImpl(new ExceptionThrown(e)); // sole difference is here
                    }
                }
            }
            """;

    @DisplayName("construction as argument to method call")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        TypeInfo loopDataImpl = X.findSubType("LoopDataImpl");
        MethodInfo ldConstructor = loopDataImpl.findConstructor(1);
        ParameterInfo ldConstructor0 = ldConstructor.parameters().get(0);
        StaticValues sv0 = ldConstructor0.analysis().getOrDefault(StaticValuesImpl.STATIC_VALUES_PARAMETER, StaticValuesImpl.NONE);
        assertEquals("E=this.exit", sv0.toString());

        MethodInfo withException = loopDataImpl.findUniqueMethod("withException", 1);
        {
            Statement s0 = withException.methodBody().statements().get(0);
            VariableData vd0 = VariableDataImpl.of(s0);
            VariableInfo vi0Ee = vd0.variableInfo(withException.fullyQualifiedName());
            assertEquals("0M-2-*M|0.0-*:e", vi0Ee.linkedVariables().toString());
        }
    }

}
