package com.llmgateway.routing;

/** Bounded dynamic programming; no regex engine and no catastrophic backtracking. */
public final class WildcardMatcher {
    private WildcardMatcher() {}
    public static void validate(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 255) throw new IllegalArgumentException("Pattern must contain 1 to 255 characters");
    }
    public static int specificity(String pattern) { return (int) pattern.chars().filter(c -> c != '*' && c != '?').count(); }
    public static boolean matches(String pattern, String name) {
        validate(pattern);
        if (name == null || name.length() > 255) return false;
        boolean[] previous = new boolean[name.length() + 1]; previous[0] = true;
        for (char token : pattern.toCharArray()) {
            boolean[] current = new boolean[name.length() + 1]; current[0] = token == '*' && previous[0];
            for (int i = 1; i <= name.length(); i++) current[i] = token == '*' ? previous[i] || current[i - 1]
                    : previous[i - 1] && (token == '?' || token == name.charAt(i - 1));
            previous = current;
        }
        return previous[name.length()];
    }
}
