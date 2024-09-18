package org.e2immu.analyzer.modification.prepwork;

public class Util {

    public static boolean atSameLevel(String i0, String i1) {
        int d0 = i0.lastIndexOf('.');
        int d1 = i1.lastIndexOf('.');
        return d0 > 0 && d1 > 0 && i0.substring(0, d0).equals(i1.substring(0, d1));
    }

    /**
     * all
     *
     * @param scope an index designating the scope (of a variable)
     * @param index an index
     * @return true when the index is in the scope
     */
    public static boolean inScopeOf(String scope, String index) {
        if ("-".equals(scope)) return true;
        int dashScope = Math.max(scope.lastIndexOf("-"), scope.lastIndexOf("+"));
        if (dashScope >= 0) {
            // 0-E -> in scope means starting with 0
            String sub = scope.substring(0, dashScope);
            return index.startsWith(sub);
        }
        int lastDotScope = scope.lastIndexOf('.');
        if (lastDotScope < 0) {
            // scope = 3 --> 3.0.0 ok, 3 ok, 4 ok
            return index.compareTo(scope) >= 0;
        }
        // scope = 3.0.2 --> 3.0.3 ok, 3.0.2.0.0 OK,  but 3.1.3 is not OK; 4 is not OK
        String withoutDot = scope.substring(0, lastDotScope);
        if (!index.startsWith(withoutDot)) return false;
        return index.compareTo(scope) >= 0;
    }

    // 3.0.0-E, -I
    public static String stage(String assignmentId) {
        int dash = assignmentId.lastIndexOf('-');
        if (dash >= 0) return assignmentId.substring(dash);
        int colon = assignmentId.lastIndexOf(':');
        if (colon >= 0) return assignmentId.substring(colon);
        throw new UnsupportedOperationException();
    }

    public static String stripStage(String index) {
        int dash = index.lastIndexOf('-');
        if (dash >= 0) return index.substring(0, dash);
        int colon = index.lastIndexOf(':');
        if (colon >= 0) return index.substring(0, colon);
        return index;
    }

    // add a character so that we're definitely beyond this index
    public static String beyond(String index) {
        return index + "~";
    }
}
