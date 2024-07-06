package org.e2immu.analyzer.modification.prepwork.variable;

// suffixes in assignment id; these act as the 3 levels for setProperty
public enum Stage {
    INITIAL("-C"), // C for creation, but essentially, it should be < E
    EVALUATION("-E"), // the - comes before the digits
    MERGE(":M"); // the : comes after the digits
    public final String label;

    Stage(String label) {
        this.label = label;
    }

    public static boolean isPresent(String s) {
        return s != null && (s.endsWith(INITIAL.label) || s.endsWith(EVALUATION.label) || s.endsWith(MERGE.label));
    }

    public static String without(String s) {
        if (isPresent(s)) {
            return s.substring(0, s.length() - 2);
        }
        return s;
    }

    public static Stage from(String stage) {
        return switch (stage) {
            case "-C" -> INITIAL;
            case "-E" -> EVALUATION;
            case ":M" -> MERGE;
            default -> throw new UnsupportedOperationException();
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
