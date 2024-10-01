package org.e2immu.analyzer.modification.prepwork.callgraph;


import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.element.Visitor;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.statement.LocalVariableCreation;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.util.internal.graph.G;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/*
call & reference graphs.

direction of arrow: I need you to exist first (I, from -> you, to)
 */
public class ComputeCallGraph {

    private final TypeInfo primaryType;
    private final Set<MethodInfo> recursive = new HashSet<>();
    private final G.Builder<Info> builder = new G.Builder<>(Long::sum);

    public ComputeCallGraph(TypeInfo primaryType) {
        this.primaryType = primaryType;
    }

    public G<Info> go() {
        go(primaryType);
        return builder.build();
    }

    public Set<MethodInfo> recursiveMethods() {
        return recursive;
    }

    /*
    Edge types:
    A. from type to its methods, fields, interfaces, parent, type-parameter bounds
    B. from non-static subtype to its enclosing type (this holds for inner classes and anonymous classes, lambdas)
    C. from method/constructor/field to any type referenced in it, different from the owner (see A, other direction)
    D. from method body/field initializer to any method referenced (as method call, constructor call, method reference).

    FIXME
        we don't want types referenced as static type expressions, or types of 'this' when represents the type itself

    Calls from a lambda/anonymous class inside method M to M are marked as recursive. No edge in the graph will be generated.
    Reason: we're already generating an edge from M into the anonymous type (B), from the anonymous type to the method (A)
     */
    private void go(TypeInfo typeInfo) {
        typeInfo.subTypes().forEach(st -> {
            if (!st.isStatic()) {
                builder.add(st, List.of(typeInfo)); // B
            }
            go(st);
        });

        typeInfo.interfacesImplemented().forEach(pt -> addType(typeInfo, pt)); // A
        if (typeInfo.parentClass() != null) addType(typeInfo, typeInfo.parentClass()); // A
        typeInfo.typeParameters().forEach(tp -> tp.typeBounds().forEach(pt -> addType(typeInfo, pt))); // A

        typeInfo.constructorAndMethodStream().forEach(mi -> {
            mi.exceptionTypes().forEach(pt -> addType(mi, pt));
            mi.parameters().forEach(pi -> addType(mi, pi.parameterizedType()));

            builder.add(typeInfo, List.of(mi)); // A
            Visitor visitor = new Visitor(mi);
            mi.methodBody().visit(visitor);
        });
        typeInfo.fields().forEach(fi -> {
            addType(fi, fi.type());
            builder.add(typeInfo, List.of(fi)); // A
            if (fi.initializer() != null && !fi.initializer().isEmpty()) {
                Visitor visitor = new Visitor(fi);
                fi.initializer().visit(visitor);
            }
        });
    }

    class Visitor implements Predicate<Element> {
        private final Info info;

        Visitor(Info info) {
            this.info = info;
        }

        public boolean test(Element e) {
            if (e instanceof LocalVariableCreation lvc) {
                addType(info, lvc.localVariable().parameterizedType());
                return true; // into assignment expression(s)
            }
            if (e instanceof MethodCall mc) {
                handleMethodCall(info, mc.methodInfo());
                mc.parameterExpressions().forEach(pe -> pe.visit(this));
                if (!(mc.object() instanceof VariableExpression || mc.object() instanceof TypeExpression)) {
                    mc.object().visit(this);
                }
                return false;
            }
            if (e instanceof MethodReference mr) {
                handleMethodCall(info, mr.methodInfo());
                return false;
            }
            if (e instanceof ConstructorCall cc) {
                if (cc.constructor() != null) {
                    handleMethodCall(info, cc.constructor());
                    cc.parameterExpressions().forEach(pe -> pe.visit(this));
                    if (cc.object() != null && !(cc.object() instanceof VariableExpression || cc.object() instanceof TypeExpression)) {
                        cc.object().visit(this);
                    }
                    return false;
                }
                TypeInfo anonymousType = cc.anonymousClass();
                if (anonymousType != null) {
                    handleAnonymousType(info, anonymousType); // B
                    return false;
                }
            } else if (e instanceof Lambda lambda) {
                TypeInfo anonymousType = lambda.methodInfo().typeInfo();
                handleAnonymousType(info, anonymousType); // B
                return false;
            }
            return true;
        }
    }

    private void handleAnonymousType(Info from, TypeInfo anonymousType) {
        builder.add(anonymousType, List.of(from));
        go(anonymousType);
    }

    private void handleMethodCall(Info from, MethodInfo to) {
        if (from == to) {
            recursive.add(to);
        } else if (from instanceof MethodInfo mi && isRecursion(mi, to)) {
            recursive.add(mi);
            recursive.add(to);
        } else if (to.typeInfo().primaryType().equals(primaryType)) {
            builder.add(from, List.of(to)); // D
        }
    }

    private static boolean isRecursion(MethodInfo from, MethodInfo to) {
        if (from == to) return true;
        TypeInfo owner = from.typeInfo();
        if (owner.enclosingMethod() != null) {
            return isRecursion(owner.enclosingMethod(), to);
        }
        return false;
    }

    private void addType(Info from, ParameterizedType pt) {
        TypeInfo best = pt.bestTypeInfo();
        if (best != null) {
            if (best != from && best.primaryType().equals(primaryType)) {
                builder.add(from, List.of(best));
            }
            for (ParameterizedType parameter : pt.parameters()) {
                addType(from, parameter);
            }
        }
    }
}
