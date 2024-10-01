package org.e2immu.analyzer.modification.prepwork.callgraph;


import org.e2immu.language.cst.api.element.Element;
import org.e2immu.language.cst.api.element.Visitor;
import org.e2immu.language.cst.api.expression.ConstructorCall;
import org.e2immu.language.cst.api.expression.Lambda;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.expression.MethodReference;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.util.internal.graph.G;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private void go(TypeInfo typeInfo) {
        typeInfo.subTypes().forEach(st -> {
            if (!st.isStatic()) {
                builder.add(st, List.of(typeInfo));
            }
            go(st);
        });
        typeInfo.constructorAndMethodStream().forEach(mi -> {
            List<Info> dependent = mi.typesReferenced()
                    .map(Element.TypeReference::typeInfo)
                    .filter(ti -> ti.primaryType().equals(primaryType))
                    .map(ti -> (Info) ti)
                    .toList();
            builder.add(mi, dependent);
            mi.methodBody().visit(e -> visit(mi, e));
        });
        typeInfo.fields().forEach(fi -> {
            List<Info> dependent = fi.typesReferenced()
                    .map(Element.TypeReference::typeInfo)
                    .filter(ti -> ti.primaryType().equals(primaryType))
                    .map(ti -> (Info) ti)
                    .toList();
            builder.add(fi, dependent);
            if (fi.initializer() != null && !fi.initializer().isEmpty()) {
                fi.initializer().visit(e -> visit(fi, e));
            }
        });
    }

    private boolean visit(Info info, Element e) {
        if (e instanceof MethodCall mc) {
            handleMethodCall(info, mc.methodInfo());
        } else if (e instanceof MethodReference mr) {
            handleMethodCall(info, mr.methodInfo());
        } else if (e instanceof ConstructorCall cc) {
            if (cc.constructor() != null) {
                handleMethodCall(info, cc.constructor());
            } else {
                TypeInfo anonymousType = cc.anonymousClass();
                if (anonymousType != null) {
                    builder.add(anonymousType, List.of(info));
                    go(anonymousType);
                }
            }
        } else if (e instanceof Lambda lambda) {
            TypeInfo anonymousType = lambda.methodInfo().typeInfo();
            builder.add(anonymousType, List.of(info));
            go(anonymousType);
        }
        return true;
    }

    private void handleMethodCall(Info from, MethodInfo to) {
        if (from == to) {
            recursive.add(to);
        } else if (to.typeInfo().primaryType().equals(primaryType)) {
            builder.add(from, List.of(to));
        }
    }
}
