package org.e2immu.analyzer.modification.prepwork.callgraph;


import org.e2immu.language.cst.api.analysis.Property;
import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.expression.*;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.cst.api.statement.LocalVariableCreation;
import org.e2immu.language.cst.api.type.ParameterizedType;
import org.e2immu.language.cst.api.variable.FieldReference;
import org.e2immu.language.cst.impl.analysis.PropertyImpl;
import org.e2immu.language.cst.impl.analysis.ValueImpl;
import org.e2immu.util.internal.graph.G;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.e2immu.language.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;

/*
call & reference graphs.

direction of arrow: I need you to exist first (I, from -> you, to)
 */
public class ComputeCallGraph {
    public static final Property RECURSIVE_METHOD = new PropertyImpl("recursiveMethod", ValueImpl.BoolImpl.FALSE);
    private final Runtime runtime;
    private final Set<TypeInfo> primaryTypes;
    private final Set<MethodInfo> recursive = new HashSet<>();
    private final G.Builder<Info> builder = new G.Builder<>(Long::sum);
    private final Predicate<TypeInfo> externalsToAccept;

    private G<Info> graph;

    public ComputeCallGraph(Runtime runtime, TypeInfo primaryType) {
        this(runtime, Set.of(primaryType), t -> false);
    }

    public ComputeCallGraph(Runtime runtime,
                            Set<TypeInfo> primaryTypes,
                            Predicate<TypeInfo> externalsToAccept) {
        this.runtime = runtime;
        this.primaryTypes = primaryTypes;
        this.externalsToAccept = externalsToAccept;
    }

    public ComputeCallGraph go() {
        primaryTypes.forEach(this::go);
        graph = builder.build();
        return this;
    }

    public G<Info> graph() {
        return graph;
    }

    public Set<MethodInfo> recursiveMethods() {
        return recursive;
    }

    public void setRecursiveMethods() {
        recursive.forEach(mi -> {
            if (!mi.analysis().haveAnalyzedValueFor(RECURSIVE_METHOD)) {
                mi.analysis().set(RECURSIVE_METHOD, TRUE);
            }
        });
    }

    /*
    Edge types:
    A. from type to its methods, fields, interfaces, parent, type-parameter bounds
    B. from non-static subtype to its enclosing type (this holds for inner classes and anonymous classes, lambdas)
    C. from method/constructor/field to any type referenced in it, different from the owner (see A, other direction)
        this includes the types of method parameters
    D. from method body/field initializer to any method or field referenced (as method call, constructor call, method reference).

    FIXME
        we don't want types referenced as static type expressions, or types of 'this' when represents the type itself

    Calls from a lambda/anonymous class inside method M to M are marked as recursive. No edge in the graph will be generated.
    Reason: we're already generating an edge from M into the anonymous type (B), from the anonymous type to the method (A)
     */
    private void go(TypeInfo typeInfo) {
        builder.addVertex(typeInfo);

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
            mi.exceptionTypes().forEach(pt -> addType(mi, pt)); // C
            mi.parameters().forEach(pi -> addType(mi, pi.parameterizedType())); // C
            if (mi.hasReturnValue()) { // C
                addType(mi, mi.returnType()); // needed because of immutable computation in independent
            }

            builder.add(typeInfo, List.of(mi)); // A
            Visitor visitor = new Visitor(mi);
            mi.methodBody().visit(visitor); // D
        });
        typeInfo.fields().forEach(fi -> {
            addType(fi, fi.type()); // C
            builder.addVertex(fi);
            builder.add(typeInfo, List.of(fi)); // A
            if (fi.initializer() != null && !fi.initializer().isEmpty()) {
                Visitor visitor = new Visitor(fi);
                fi.initializer().visit(visitor); // D
            }
        });
    }

    class Visitor implements Predicate<Element> {
        private final Info info;

        Visitor(Info info) {
            this.info = info;
        }

        public boolean test(Element e) {
            if (e instanceof VariableExpression ve
                && ve.variable() instanceof FieldReference fr
                && accept(fr.fieldInfo().owner())) {
                // inside a type, an accessor should come before its field
                // outside a type, we want the field to have been processed first
                // see e.g. TestStaticValuesRecord,2
                handleFieldAccess(info, fr);
            }
            if (e instanceof Assignment a
                && a.variableTarget() instanceof FieldReference fr
                && accept(fr.fieldInfo().owner().primaryType())) {
                handleFieldAccess(info, fr);
            }
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
                TypeInfo anonymousType = cc.anonymousClass();
                // important: check anonymous type first, it can have constructor != null
                if (anonymousType != null) {
                    handleAnonymousType(info, anonymousType); // B
                    for (MethodInfo mi : anonymousType.constructorsAndMethods()) {
                        handleMethodCall(info, mi);
                    }
                    return false;
                }
                if (cc.constructor() != null) {
                    handleMethodCall(info, cc.constructor());
                    cc.parameterExpressions().forEach(pe -> pe.visit(this));
                    if (cc.object() != null && !(cc.object() instanceof VariableExpression || cc.object() instanceof TypeExpression)) {
                        cc.object().visit(this);
                    }
                    return false;
                }
                return true;
            }
            if (e instanceof Lambda lambda) {
                TypeInfo anonymousType = lambda.methodInfo().typeInfo();
                handleAnonymousType(info, anonymousType); // B
                handleMethodCall(info, lambda.methodInfo());
                return false;
            }
            return true;
        }
    }

    private void handleFieldAccess(Info info, FieldReference fr) {
        if (info instanceof MethodInfo mi && mi.typeInfo() == fr.fieldInfo().owner()) {
            builder.add(fr.fieldInfo(), List.of(info));
        } else {
            builder.add(info, List.of(fr.fieldInfo()));
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
        } else if (accept(to.typeInfo())) {
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
        if (!from.typeInfo().asParameterizedType().isAssignableFrom(runtime, pt)) {
            TypeInfo best = pt.bestTypeInfo();
            if (best != null) {
                if (best != from && accept(best)) {
                    builder.add(from, List.of(best));
                }
                for (ParameterizedType parameter : pt.parameters()) {
                    addType(from, parameter);
                }
            }
        } // else: avoid links to self, we want the type at the end
    }

    private boolean accept(TypeInfo typeInfo) {
        return primaryTypes.contains(typeInfo.primaryType()) || externalsToAccept.test(typeInfo);
    }
}
