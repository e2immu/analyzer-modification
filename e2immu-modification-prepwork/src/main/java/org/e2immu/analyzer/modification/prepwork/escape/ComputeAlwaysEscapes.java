package org.e2immu.analyzer.modification.prepwork.escape;

import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.statement.*;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;


public class ComputeAlwaysEscapes {

    private enum Escape {ALWAYS, MAYBE, NO}

    public static void go(MethodInfo methodInfo) {
        alwaysEscapes(methodInfo.methodBody());
    }

    private static Escape alwaysEscapes(Statement statement) {
        if (statement instanceof Block block) {
            Escape escape = Escape.NO;
            for (Statement st : block.statements()) {
                escape = alwaysEscapes(st);
            }
            return escape;
        }
        Escape escape;
        if (statement instanceof ThrowStatement || statement instanceof ReturnStatement) {
            escape = Escape.ALWAYS;
        } else if (statement instanceof BreakOrContinueStatement) {
            escape = Escape.MAYBE;
        } else if (statement instanceof IfElseStatement ifElse) {
            Escape e1 = alwaysEscapes(ifElse.block());
            Escape e2 = alwaysEscapes(ifElse.elseBlock());
            if (e1 == Escape.ALWAYS && e2 == Escape.ALWAYS) {
                escape = Escape.ALWAYS;
            } else if (e1 == Escape.ALWAYS || e2 == Escape.ALWAYS || e1 == Escape.MAYBE || e2 == Escape.MAYBE) {
                escape = Escape.MAYBE;
            } else {
                escape = Escape.NO;
            }
        } else if (statement instanceof LoopStatement loop) {
            Escape e1 = alwaysEscapes(loop.block());
            if (e1 == Escape.ALWAYS) {
                escape = Escape.ALWAYS;
            } else if (loop.expression().isBoolValueTrue() && e1 == Escape.NO) {
                escape = Escape.ALWAYS; // infinite loop, we'll not go further
            } else {
                escape = Escape.NO;
            }
        } else {
            escape = Escape.NO;
        }

        if (escape == Escape.ALWAYS && !(statement.analysis().haveAnalyzedValueFor(PropertyImpl.ALWAYS_ESCAPES))) {
            statement.analysis().set(PropertyImpl.ALWAYS_ESCAPES, ValueImpl.BoolImpl.TRUE);
        }
        return escape;
    }

}
