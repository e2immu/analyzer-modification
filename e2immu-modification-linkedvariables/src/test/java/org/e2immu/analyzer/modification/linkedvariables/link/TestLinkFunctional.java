package org.e2immu.analyzer.modification.linkedvariables.link;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.analyzer.modification.linkedvariables.lv.LinkedVariablesImpl;
import org.e2immu.analyzer.modification.prepwork.variable.VariableData;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestLinkFunctional extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.function.Supplier;
            class X {
                static class M { private int i; int getI() { return i; } void setI(int i) { this.i = i; } }
                static final class N { private int i; int getI() { return i; } void setI(int i) { this.i = i; } }
            
                static <X> X m0(Supplier<X> supplier) {
                    return supplier.get();
                }
            
                static <X> X m0b(Supplier<X> supplier) {
                    //noinspection ALL
                    Supplier<X> s = () -> supplier.get();
                    return s.get();
                }
            
                static M m1(Supplier<M> supplier) {
                    return supplier.get();
                }
            
                static Integer m2(Supplier<Integer> supplier) {
                    return supplier.get();
                }
    
                static N m3(Supplier<N> supplier) {
                    return supplier.get();
                }
            }
            """;

    @DisplayName("supplier basics")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse(INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo m0 = X.findUniqueMethod("m0", 1);
        assertEquals("*-4-0:supplier", lvs(m0));

        MethodInfo m0b = X.findUniqueMethod("m0b", 1);
        assertEquals("*-4-0:supplier", lvs(m0b));

        MethodInfo m1 = X.findUniqueMethod("m1", 1);
        assertEquals("*M-4-0M:supplier", lvs(m1));

        MethodInfo m2 = X.findUniqueMethod("m2", 1);
        assertEquals("", lvs(m2));

        MethodInfo m3 = X.findUniqueMethod("m3", 1);
        assertEquals("*M-4-0M:supplier", lvs(m3));
    }

    private static String lvs(MethodInfo methodInfo) {
        return methodInfo.analysis().getOrDefault(LinkedVariablesImpl.LINKED_VARIABLES_METHOD, LinkedVariablesImpl.EMPTY).toString();
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            import java.util.stream.Stream;
            import java.util.Optional;
            class X {
                static class M { private int i; int getI() { return i; } void setI(int i) { this.i = i; } }
            
                static Stream<M> m3(Stream<M> stream) {
                    return stream.filter(m -> m.i == 3);
                }
            
                static Optional<M> m4(Stream<M> stream) {
                    return stream.filter(m -> m.i == 3).findFirst();
                }
            
                static M m5(Stream<M> stream) {
                    return stream.filter(m -> m.i == 3).findFirst().orElseThrow();
                }
            
                static Stream<Integer> m6(Stream<Integer> stream) {
                    return stream.filter(i -> i == 3);
                }
            
                static Optional<Integer> m7(Stream<Integer> stream) {
                    return stream.filter(i -> i == 3).findFirst();
                }
            
                static Integer m8(Stream<Integer> stream) {
                    return stream.filter(i -> i == 3).findFirst().orElseThrow();
                }
            }
            """;

    @DisplayName("stream basics")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse(INPUT2);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);

        MethodInfo m3 = X.findUniqueMethod("m3", 1);
        assertEquals("*M-4-0M:supplier", lvs(m3));
    }

    @Language("java")
    private static final String INPUT3 = """
            package a.b;
            import java.util.Optional;
            import java.util.function.Predicate;
            import java.util.stream.Stream;
            
            class X {
                // prep test for the m9-m11
                static <X> boolean mPredicate(X x, Predicate<X> predicate) {
                    return predicate.test(x);
                }
            
                static <X> Stream<X> m9(Stream<X> stream, Predicate<X> predicate) {
                    //noinspection ALL
                    return stream.filter(i -> predicate.test(i));
                }
            
                static <X> Stream<X> m9b(Stream<X> stream, Predicate<X> predicate) {
                    //noinspection ALL
                    Predicate<X> p = i -> predicate.test(i);
                    return stream.filter(p);
                }
            
                // lambda is type $8
                static <X> Optional<X> m10(Stream<X> stream, Predicate<X> predicate) {
                    //noinspection ALL
                    return stream.filter(x -> predicate.test(x)).findFirst();
                }
            
                static <X> X m11(Stream<X> stream, Predicate<X> predicate) {
                    //noinspection ALL
                    return stream.filter(i -> predicate.test(i)).findFirst().orElseThrow();
                }
            }
            """;

    @DisplayName("predicate basics")
    @Test
    public void test3() {
        TypeInfo X = javaInspector.parse(INPUT3);
        List<Info> analysisOrder = prepWork(X);
        analyzer.doPrimaryType(X, analysisOrder);
    }

    @Language("java")
    private static final String INPUT4 = """
            package a.b;
            import java.util.stream.Stream;
            import java.util.Optional;
            class X {
                static class M { private int i; int getI() { return i; } void setI(int i) { this.i = i; } }
            
                static <X, Y> Stream<Y> m12(Stream<X> stream, Function<X, Y> function) {
                    //noinspection ALL
                    return stream.map(x -> function.apply(x));
                }
            
                static <X, Y> Stream<Y> m12b(Stream<X> stream, Function<X, Y> function) {
                    //noinspection ALL
                    Function<X, Y> f = x -> function.apply(x);
                    return stream.map(f);
                }
            
                static <X, Y> Stream<Y> m13(Stream<X> stream, Function<X, Y> function) {
                    //noinspection ALL
                    return stream.map(function::apply);
                }
            
                static List<String> m14(List<String> in, List<String> out) {
                    //noinspection ALL
                    in.forEach(out::add);
                    return out;
                }
            
                static <X> List<X> m15(List<X> in, List<X> out) {
                    //noinspection ALL
                    in.forEach(out::add);
                    return out;
                }
            
                /*
                    static <X> List<X> m15b(List<X> in, List<X> out) {
                        //noinspection ALL
                        Consumer<X> add = out::add;
                        in.forEach(add);
                        return out;
                    }
                */
                static List<M> m16(List<M> in, List<M> out) {
                    //noinspection ALL
                    in.forEach(out::add);
                    return out;
                }
            
                static <X> Stream<X> m17(Supplier<X> supplier) {
                    return IntStream.of(3).mapToObj(i -> supplier.get());
                }
            
                static <X> Stream<X> m17b(Supplier<X> supplier) {
                    IntFunction<X> f = i -> supplier.get();
                    return IntStream.of(3).mapToObj(f);
                }
            
                static Stream<M> m18(Supplier<M> supplier) {
                    return IntStream.of(3).mapToObj(i -> supplier.get());
                }
            
                static <X> Stream<X> m19(List<X> list) {
                    //noinspection ALL
                    return IntStream.of(3).mapToObj(i -> list.get(i));
                }
            
                static Stream<M> m20(List<M> list) {
                    //noinspection ALL
                    return IntStream.of(3).mapToObj(i -> list.get(i));
                }
            
                static <X> Stream<X> m21(List<X> list) {
                    return IntStream.of(3).mapToObj(list::get);
                }
            
                static Stream<M> m22(List<M> list) {
                    return IntStream.of(3).mapToObj(list::get);
                }
            
                static Stream<M> m22b(List<M> list) {
                    IntFunction<M> get = list::get;
                    return IntStream.of(3).mapToObj(get);
                }
            
                static <X> Stream<X> m23(List<X> list) {
                    return IntStream.of(3).mapToObj(new IntFunction<X>() {
                        @Override
                        public X apply(int value) {
                            return list.get(value);
                        }
                    });
                }
            
                static <X> Stream<X> m23b(List<X> list) {
                    IntFunction<X> f = new IntFunction<>() {
                        @Override
                        public X apply(int value) {
                            return list.get(value);
                        }
                    };
                    IntStream intStream = IntStream.of(3);
                    return intStream.mapToObj(f);
                }
            
                static <X> Stream<X> m23c(List<X> list) {
                    IntFunction<X> f = new IntFunction<>() {
                        @Override
                        public X apply(int value) {
                            return list.get(value);
                        }
                    };
                    return IntStream.of(3).mapToObj(f);
                }
            
                static Stream<M> m24(List<M> list) {
                    return IntStream.of(3).mapToObj(new IntFunction<M>() {
                        @Override
                        public M apply(int value) {
                            return list.get(value);
                        }
                    });
                }
            
            
                // We're contracting the anonymous type's method (the SAM) to be @NotModified.
                // As a consequence, we'll prevent creating an inlined method, because that
                // inlined method will link xx to selector (see m27).
            
                static <X> boolean m25(@Independent X xx, Predicate<X> selector) {
                    Predicate<X> independentSelector = new Predicate<X>() {
                        @NotModified(contract = true)
                        @Override
                        public boolean test(@Independent(contract = true) X x) {
                            return selector.test(x);
                        }
                    };
                    return independentSelector.test(xx);
                }
            
                // same as 25, but without the explicit @Independent, which is not relevant
                // because selector is not a field.
                static <X> boolean m26(@Independent X xx, Predicate<X> selector) {
                    Predicate<X> independentSelector = new Predicate<X>() {
                        @NotModified(contract = true)
                        @Override
                        public boolean test(X x) {
                            return selector.test(x);
                        }
                    };
                    return independentSelector.test(xx);
                }
            
                //Here, xx -4- selector in statement 1.
                //xx is still @Independent, because that is wrt the fields (that are absent).
                //The cross-link from xx to selector is not computed (only contracted as of now, 202404)
                static <X> boolean m27(@Independent X xx, Predicate<X> selector) {
                    Predicate<X> independentSelector = new Predicate<X>() {
                        @Override
                        public boolean test(X x) {
                            return selector.test(x);
                        }
                    };
                    return independentSelector.test(xx);
                }
            
            
                //Corresponds to Linking_2.m4
                static boolean m28(@NotModified M m, @Container(contract = true) Predicate<M> selector) {
                    return selector.test(m);
                }
            
                interface X {
                }
            
                interface Y {
                }
            
                // examples of different linkings for actual function implementations
            
                // y = f1.apply(x)  --> y 0-4-1 f1
                static class F1 implements Function<X, Y> {
                    List<Y> ys;
            
                    @Override
                    public Y apply(X x) {
                        int index = Math.abs(x.hashCode()) % ys.size();
                        return ys.get(index);
                    }
                }
            
                interface T {
                }
            
                // y = f2.apply(x)  --> y *-4-0 x
                static class F2 implements Function<List<T>, T> {
                    private int i;
            
                    @Override
                    public T apply(List<T> ts) {
                        return ts.get((++i) % ts.size());
                    }
                }
            
                // y = f3.apply(x)  --> y 0-4-0 x
                static class F3 implements Function<List<T>, List<T>> {
                    private int i;
            
                    @Override
                    public List<T> apply(List<T> ts) {
                        return List.copyOf(ts.subList(0, ++i % ts.size()));
                    }
                }
            
                // y = f3.apply(x)  --> y 0-4-0 x, 0-4-0 f4
                static class F4 implements Function<List<T>, List<T>> {
                    private int i;
                    private List<T> altList;
            
                    @Override
                    public List<T> apply(List<T> ts) {
                        int index = ++i % ts.size();
                        if (index % 2 == 0) return List.of(altList.get(i % altList.size()));
                        return List.copyOf(ts.subList(0, index));
                    }
                }
            }
            """;
}
