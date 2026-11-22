package com.pixeldweller.maskerade.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splitting identifiers and phrases into word parts, and keeping their upper/lower-case pattern. Pure Java. */
public final class Words {

    private Words() {}

    /** Upper/lower-case pattern of one word part. */
    public enum Case { LOWER, UPPER, CAPITAL, OTHER }

    /**
     * Splits at camelCase / PascalCase humps, ACRONYMWord boundaries, letter↔digit changes and every character
     * that is neither a letter nor a digit: {@code FalkenrathTourPlanner} → Falkenrath, Tour, Planner;
     * {@code FALKENRATH_API_URL} → FALKENRATH, API, URL; {@code slf4j} → slf, 4, j.
     */
    public static List<String> split(String s) {
        var out = new ArrayList<String>();
        var cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                if (!cur.isEmpty()) {
                    char p = cur.charAt(cur.length() - 1);
                    boolean hump = Character.isLowerCase(p) && Character.isUpperCase(c);
                    boolean acronymEnd = Character.isUpperCase(p) && Character.isUpperCase(c)
                            && i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
                    if (hump || acronymEnd || Character.isDigit(p)) flush(cur, out);
                }
                cur.append(c);
            } else if (Character.isDigit(c)) {
                if (!cur.isEmpty() && !Character.isDigit(cur.charAt(cur.length() - 1))) flush(cur, out);
                cur.append(c);
            } else {
                flush(cur, out);
            }
        }
        flush(cur, out);
        return out;
    }

    /** {@link #split} without the digit parts. */
    public static List<String> letterParts(String s) {
        return split(s).stream().filter(w -> Character.isLetter(w.charAt(0))).toList();
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (!cur.isEmpty()) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    public static Case caseOf(String word) {
        boolean anyLetter = false, allLower = true, allUpper = true;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!Character.isLetter(c)) continue;
            anyLetter = true;
            if (Character.isUpperCase(c)) allLower = false;
            else if (Character.isLowerCase(c)) allUpper = false;
        }
        if (!anyLetter) return Case.OTHER;
        if (allLower) return Case.LOWER;
        // a single upper-case letter counts as Capital ("X" in "XService")
        if (allUpper && word.length() > 1) return Case.UPPER;
        if (Character.isUpperCase(word.charAt(0)) && isLower(word.substring(1))) return Case.CAPITAL;
        return Case.OTHER;
    }

    private static boolean isLower(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) return false;
        }
        return true;
    }

    /** Applies a case pattern to a word; {@link Case#OTHER} keeps the word as it is. */
    public static String apply(Case c, String word) {
        return switch (c) {
            case LOWER -> word.toLowerCase(Locale.ROOT);
            case UPPER -> word.toUpperCase(Locale.ROOT);
            case CAPITAL -> word.isEmpty() ? word
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
            case OTHER -> word;
        };
    }

    public static String capitalize(String word) {
        return apply(Case.CAPITAL, word);
    }

    /** Lower-cased word parts joined with spaces — the identity of a term ("FalkenrathLogistik" = "falkenrath logistik"). */
    public static String key(String s) {
        return String.join(" ", letterParts(s)).toLowerCase(Locale.ROOT);
    }

    public static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7E) return false;
        }
        return true;
    }
}
