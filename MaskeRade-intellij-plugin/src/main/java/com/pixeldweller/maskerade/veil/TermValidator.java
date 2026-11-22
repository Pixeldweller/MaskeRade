package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.Words;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Checks single terms and the list as a whole before anything is veiled. */
public final class TermValidator {

    private TermValidator() {}

    /** Problems of one term that make it unusable (the engine skips such terms). */
    public static List<String> problemsOf(Term t) {
        var problems = new ArrayList<String>();
        var real = t.real == null ? "" : t.real.trim();
        var placeholder = t.placeholder == null ? "" : t.placeholder.trim();
        if (real.isEmpty()) problems.add("term is empty");
        if (placeholder.isEmpty()) problems.add("placeholder is empty");
        if (!problems.isEmpty()) return problems;
        if (real.equalsIgnoreCase(placeholder)) problems.add("placeholder equals the term");
        if (t.isLiteral()) {
            if (t.kind == TermKind.SECRET && real.length() != placeholder.length()) {
                problems.add("a secret's dummy must have the same length (" + real.length() + ")");
            }
            return problems;
        }
        var realWords = t.realWords();
        var placeholderWords = t.placeholderWords();
        if (!Words.letterParts(placeholder).equals(Words.split(placeholder))
                || !placeholder.matches("\\p{L}+(?:[ _.\\-]?\\p{L}+)*")) {
            problems.add("placeholder may only contain letters (and separators between words)");
        } else if (realWords.size() != placeholderWords.size()) {
            problems.add("placeholder needs " + realWords.size() + " word(s) like the term, has " + placeholderWords.size());
        } else if (placeholderWords.stream().anyMatch(w -> w.length() < 2)) {
            problems.add("placeholder words need at least 2 letters");
        }
        return problems;
    }

    /** Problems between terms: duplicates, chains, prefixes. */
    public static List<String> listProblems(List<Term> terms) {
        var problems = new ArrayList<String>();
        var active = terms.stream().filter(t -> t.active && problemsOf(t).isEmpty()).toList();
        Map<String, Term> byReal = new HashMap<>();
        Map<String, Term> byPlaceholder = new HashMap<>();
        for (var t : active) {
            var realKey = t.key();
            var placeholderKey = placeholderKey(t);
            var other = byReal.putIfAbsent(realKey, t);
            if (other != null) problems.add("\"" + t.real + "\" is in the list twice");
            var sameP = byPlaceholder.putIfAbsent(placeholderKey, t);
            if (sameP != null) {
                problems.add("\"" + t.real + "\" and \"" + sameP.real + "\" share the placeholder \"" + t.placeholder + "\"");
            }
        }
        for (var t : active) {
            var asReal = byReal.get(placeholderKey(t));
            if (asReal != null) {
                problems.add("placeholder \"" + t.placeholder + "\" of \"" + t.real + "\" is itself a term (\"" + asReal.real
                        + "\") — unveiling would chain");
            }
        }
        // a placeholder word that is a prefix of another placeholder word makes mistakes hard to spot
        var placeholderWords = new ArrayList<String[]>();
        for (var t : active) {
            if (t.isLiteral()) continue;
            for (var w : t.placeholderWords()) placeholderWords.add(new String[]{w.toLowerCase(Locale.ROOT), t.real});
        }
        for (var a : placeholderWords) {
            for (var b : placeholderWords) {
                if (!a[0].equals(b[0]) && b[0].startsWith(a[0])) {
                    problems.add("placeholder word \"" + a[0] + "\" (" + a[1] + ") is a prefix of \"" + b[0] + "\" (" + b[1] + ")");
                }
            }
        }
        return problems.stream().distinct().toList();
    }

    private static String placeholderKey(Term t) {
        return t.isLiteral() ? t.placeholder.trim().toLowerCase(Locale.ROOT) : Words.key(t.placeholder);
    }
}
